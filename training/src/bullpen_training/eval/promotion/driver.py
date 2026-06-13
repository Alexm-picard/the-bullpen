"""Rolling-origin CV + experiment_results evidence driver (W5, Mac side).

This is the missing batted-ball-side analogue of the pitch
``production.py`` CV caller, generalised to ALSO emit the rule-5
``experiment_results``-shaped evidence artifact that a SHADOW -> CHAMPION
promotion reads.

For one model_name it:

  1. Runs the rolling-origin 4-fold CV (``eval.cv_harness.run``) for BOTH the
     co-registered BASELINE and the CHALLENGER on the sample data - same folds,
     same metrics - so the two models' per-fold metrics are directly
     comparable. (Rolling-origin only; 2026 excluded; no random splits - the
     harness + the per-year parquet loader enforce all three.)
  2. On the FINAL fold's held-out test year, scores both models on the SAME
     rows (paired predictions) and computes the challenger-vs-baseline verdict
     against the PRE-DECLARED criteria (``promotion.criteria``), using the same
     verdict math the Java gate uses.
  3. Writes an ``experiment_results``-shaped JSON artifact carrying the
     pre-declared criteria, the observed champion/challenger metrics, the
     guardrail deltas, and the verdict (passed/failed). A human reads this and
     promotes (rule 6: NO auto-promotion here).

Model wiring:

  - ``pitch_outcome_pre``  : challenger = LightGBM, baseline = LR (sklearn).
  - ``pitch_outcome_post`` : challenger = LightGBM (Tier-4 features),
                             baseline = LR.
  - ``batted_ball_lr_baseline`` : challenger = the LR baseline itself,
                             baseline = the DEGENERATE constant
                             marginal-class predictor (the floor any
                             registered model must beat). This evidences the
                             rule-9 baseline clears the worth-registering bar.

The real LIVE/CHAMPION promotion re-runs this on full BOX data (operator
hand-off H2): pass ``--data-source full`` (pointing ``--sample-root`` at the
full-box parquet) so the artifact is labelled ``full`` and written as
``<model>_experiment_results_full.json``. A SAMPLE-data passing row clears only
the SHADOW-stage evidence per the locked decision - it is NOT a LIVE promotion.
The artifact's ``data_source`` field records the label loudly (default
"sample") so no one mistakes sample evidence for the production verdict. The
flag is a LABEL ONLY - it changes no metric, threshold, verdict, or status.
"""

from __future__ import annotations

import json
import logging
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import click
import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.eval.cv_harness import FOLDS, CVResult
from bullpen_training.eval.cv_harness import run as cv_run
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)
from bullpen_training.eval.promotion.criteria import (
    PromotionCriteria,
    Verdict,
    criteria_for,
    evaluate_challenger_vs_baseline,
)
from bullpen_training.eval.promotion.sample_loader import (
    N_CLASSES,
    SEGMENT_COLS,
    ParquetSampleLoader,
    feature_cols_for,
    generate_sample_dataset,
)
from bullpen_training.pitch.isotonic import IsotonicCalibrator

log = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[5]
DEFAULT_SAMPLE_ROOT = REPO_ROOT / "training" / "data" / "samples" / "dev"
DEFAULT_OUT_DIR = REPO_ROOT / "training" / "data" / "eval" / "promotion"

# Reproducible model fits (NOT a data split seed - these seed the LightGBM
# booster / the LR solver, never the rolling-origin splits, which are pure
# date windows. No random_state ever touches a split. rule: no random splits).
_LGBM_SEED = 42

_CV_METRICS = (multiclass_brier, multiclass_log_loss, expected_calibration_error)


# ---------------------------------------------------------------------------
# Predictors. Each exposes .predict_proba(X) -> (N, K) for the harness.
# ---------------------------------------------------------------------------


class _MarginalPredictor:
    """Constant predictor: the train-set class marginals for every row. The
    DEGENERATE baseline floor the batted-ball LR must beat to be worth
    registering."""

    def __init__(self, marginals: np.ndarray) -> None:
        self._m = np.asarray(marginals, dtype=np.float64)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        return np.tile(self._m, (len(X), 1))


def _class_labels(n_classes: int) -> tuple[str, ...]:
    """Synthetic class-label names for the isotonic calibrator (it keys on
    count + order, not the strings)."""
    return tuple(str(i) for i in range(n_classes))


class _LRPredictor:
    """Standardised multinomial LR (sklearn) + per-class isotonic calibration,
    mirroring the production LR baseline (which always ships isotonic
    calibrators). Emits a full K-wide proba even if a class is unseen in train
    (anchor rows injected, as the production LR baseline does)."""

    def __init__(
        self,
        pipeline: Pipeline,
        feature_cols: tuple[str, ...],
        n_classes: int,
        calibrator: IsotonicCalibrator | None,
    ) -> None:
        self._pipe = pipeline
        self._cols = feature_cols
        self._k = n_classes
        self._cal = calibrator

    def _raw(self, X: pd.DataFrame) -> np.ndarray:
        # Predict on a bare ndarray (the pipeline was fit on ndarray) so sklearn
        # does not warn about feature-name mismatch.
        x = X[list(self._cols)].to_numpy(dtype=np.float64)
        raw = np.asarray(self._pipe.predict_proba(x), dtype=np.float64)
        if raw.shape[1] == self._k:
            return raw
        # Re-expand to K columns if sklearn dropped an unseen class.
        full = np.full((raw.shape[0], self._k), 1e-9, dtype=np.float64)
        for j, cls in enumerate(self._pipe.classes_):
            full[:, int(cls)] = raw[:, j]
        return full / full.sum(axis=1, keepdims=True)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = self._raw(X)
        return raw if self._cal is None else self._cal.transform(raw)


class _LGBMPredictor:
    """LightGBM multinomial + per-class isotonic calibration, mirroring the
    production pitch heads (``pitch.train_pre.model_factory`` always fits an
    isotonic calibrator on the fold's val year)."""

    def __init__(
        self, booster: Any, feature_cols: tuple[str, ...], calibrator: IsotonicCalibrator | None
    ) -> None:
        self._booster = booster
        self._cols = feature_cols
        self._cal = calibrator

    def _raw(self, X: pd.DataFrame) -> np.ndarray:
        return np.asarray(self._booster.predict(X[list(self._cols)]), dtype=np.float64)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = self._raw(X)
        return raw if self._cal is None else self._cal.transform(raw)


# ---------------------------------------------------------------------------
# Model factories (cv_harness.ModelFactory: (train_df, val_df) -> predictor).
# val_df is unused by the simple sample fits but kept for the harness contract.
# ---------------------------------------------------------------------------


def _marginal_factory(n_classes: int) -> Callable[[pd.DataFrame, pd.DataFrame], _MarginalPredictor]:
    def factory(train: pd.DataFrame, _val: pd.DataFrame) -> _MarginalPredictor:
        counts = np.bincount(np.asarray(train["label"], dtype=np.int64), minlength=n_classes)
        return _MarginalPredictor(counts / counts.sum())

    return factory


def _fit_isotonic_on_val(
    raw_val_proba: np.ndarray, y_val: np.ndarray, n_classes: int
) -> IsotonicCalibrator:
    """One-vs-rest isotonic on the val fold - the SAME calibration the
    production models fit (decision [38]). Calibrating on val (never test)
    keeps the calibrator leakage-clean."""
    return IsotonicCalibrator.fit(y_val, raw_val_proba, class_labels=_class_labels(n_classes))


def _lr_factory(
    feature_cols: tuple[str, ...], n_classes: int
) -> Callable[[pd.DataFrame, pd.DataFrame], _LRPredictor]:
    def factory(train: pd.DataFrame, val: pd.DataFrame) -> _LRPredictor:
        x = train[list(feature_cols)].to_numpy(dtype=np.float64)
        y = np.asarray(train["label"], dtype=np.int64)
        # Anchor an absent class with one mean-feature row (same trick the
        # production LR baseline uses) so predict_proba stays K-wide.
        present = set(int(v) for v in np.unique(y))
        absent = [c for c in range(n_classes) if c not in present]
        if absent:
            anchors = np.tile(x.mean(axis=0, keepdims=True), (len(absent), 1))
            x = np.vstack([x, anchors])
            y = np.concatenate([y, np.asarray(absent, dtype=y.dtype)])
        pipe = Pipeline(
            [("scale", StandardScaler()), ("lr", LogisticRegression(max_iter=2000))]
        ).fit(x, y)
        uncal = _LRPredictor(pipe, feature_cols, n_classes, calibrator=None)
        # Fit isotonic on the val fold (never test) - mirrors production.
        raw_val = uncal._raw(val)
        cal = _fit_isotonic_on_val(raw_val, np.asarray(val["label"], dtype=np.int64), n_classes)
        return _LRPredictor(pipe, feature_cols, n_classes, calibrator=cal)

    return factory


def _lgbm_factory(
    feature_cols: tuple[str, ...], n_classes: int
) -> Callable[[pd.DataFrame, pd.DataFrame], _LGBMPredictor]:
    import lightgbm as lgb

    def factory(train: pd.DataFrame, val: pd.DataFrame) -> _LGBMPredictor:
        params = {
            "objective": "multiclass",
            "num_class": n_classes,
            "metric": "multi_logloss",
            "learning_rate": 0.05,
            "num_leaves": 31,
            "min_data_in_leaf": 50,
            "seed": _LGBM_SEED,
            "deterministic": True,
            "force_row_wise": True,
            "verbosity": -1,
        }
        cols = list(feature_cols)
        dtrain = lgb.Dataset(train[cols], label=np.asarray(train["label"], dtype=np.int64))
        dval = lgb.Dataset(
            val[cols], label=np.asarray(val["label"], dtype=np.int64), reference=dtrain
        )
        booster = lgb.train(
            params,
            dtrain,
            num_boost_round=300,
            valid_sets=[dval],
            valid_names=["val"],
            callbacks=[lgb.early_stopping(30, verbose=False)],
        )
        uncal = _LGBMPredictor(booster, feature_cols, calibrator=None)
        # Per-class isotonic on val (never test) - the production pitch heads do
        # exactly this in pitch.train_pre.model_factory.
        raw_val = uncal._raw(val)
        cal = _fit_isotonic_on_val(raw_val, np.asarray(val["label"], dtype=np.int64), n_classes)
        return _LGBMPredictor(booster, feature_cols, calibrator=cal)

    return factory


@dataclass(frozen=True)
class _ModelPair:
    """The two co-registered models for one model_name's evidence run."""

    baseline_name: str
    baseline_factory: Callable[[pd.DataFrame, pd.DataFrame], Any]
    challenger_name: str
    challenger_factory: Callable[[pd.DataFrame, pd.DataFrame], Any]


def _model_pair(model_name: str, feature_cols: tuple[str, ...], n_classes: int) -> _ModelPair:
    if model_name in ("pitch_outcome_pre", "pitch_outcome_post"):
        return _ModelPair(
            baseline_name="pitch_outcome_lr_baseline",
            baseline_factory=_lr_factory(feature_cols, n_classes),
            challenger_name=model_name,
            challenger_factory=_lgbm_factory(feature_cols, n_classes),
        )
    if model_name == "batted_ball_lr_baseline":
        return _ModelPair(
            baseline_name="marginal_class_floor",
            baseline_factory=_marginal_factory(n_classes),
            challenger_name="batted_ball_lr_baseline",
            challenger_factory=_lr_factory(feature_cols, n_classes),
        )
    raise ValueError(f"no model pairing for {model_name!r}")


# ---------------------------------------------------------------------------
# The evidence run
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EvidenceRun:
    model_name: str
    criteria: PromotionCriteria
    baseline_cv: CVResult
    challenger_cv: CVResult
    verdict: Verdict
    baseline_name: str
    challenger_name: str
    final_fold_id: int
    final_test_year: int
    sample_root: Path
    rows_per_year: int


def run_evidence(
    model_name: str,
    *,
    sample_root: Path,
    rows_per_year: int,
) -> EvidenceRun:
    """Run the dual rolling-origin CV + compute the challenger-vs-baseline
    verdict for ``model_name`` on the sample mirror at ``sample_root``."""
    criteria = criteria_for(model_name)
    feature_cols = feature_cols_for(model_name)
    n_classes = N_CLASSES[model_name]
    pair = _model_pair(model_name, feature_cols, n_classes)

    loader = ParquetSampleLoader(sample_root, model_name)

    log.info("evidence: rolling-origin CV for baseline=%s", pair.baseline_name)
    baseline_cv = cv_run(
        model_factory=pair.baseline_factory,
        feature_loader=loader,
        eval_metrics=list(_CV_METRICS),
    )
    log.info("evidence: rolling-origin CV for challenger=%s", pair.challenger_name)
    challenger_cv = cv_run(
        model_factory=pair.challenger_factory,
        feature_loader=loader,
        eval_metrics=list(_CV_METRICS),
    )

    # Paired-prediction verdict on the FINAL fold's held-out test year. Both
    # models are refit on the final fold's train+val window and scored on the
    # SAME test rows (paired predictions, same order) so the gate compares like
    # with like - the box-side analogue is LIVE+SHADOW predictions on the same
    # requests, which is exactly what the Java fetcher returns.
    final = FOLDS[-1]
    train_df = loader(final.train_start_year, final.train_end_year, final.fold_id)
    val_df = loader(final.val_year, final.val_year, final.fold_id)
    test_df = loader(final.test_year, final.test_year, final.fold_id)
    y_test = np.asarray(test_df["label"], dtype=np.int64)

    baseline_model = pair.baseline_factory(train_df, val_df)
    challenger_model = pair.challenger_factory(train_df, val_df)
    baseline_proba = baseline_model.predict_proba(test_df)
    challenger_proba = challenger_model.predict_proba(test_df)

    verdict = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y_test,
        baseline_proba=baseline_proba,
        challenger_proba=challenger_proba,
    )

    return EvidenceRun(
        model_name=model_name,
        criteria=criteria,
        baseline_cv=baseline_cv,
        challenger_cv=challenger_cv,
        verdict=verdict,
        baseline_name=pair.baseline_name,
        challenger_name=pair.challenger_name,
        final_fold_id=final.fold_id,
        final_test_year=final.test_year,
        sample_root=Path(sample_root),
        rows_per_year=rows_per_year,
    )


# ---------------------------------------------------------------------------
# experiment_results-shaped artifact
# ---------------------------------------------------------------------------


def _git_commit_sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT), text=True
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _cv_summary_dict(cv: CVResult) -> dict[str, dict[str, float]]:
    return {name: {"mean": mean, "std": std} for name, (mean, std) in cv.summary.items()}


def experiment_results_artifact(run: EvidenceRun, data_source: str = "sample") -> dict[str, Any]:
    """The ``experiment_results``-shaped evidence dict (metrics + pre-declared
    criteria + verdict). Field names mirror the V012 ``experiment_results``
    columns so the box-side registration can map this 1:1 into a row.

    ``status`` is 'passed' | 'failed' exactly as the Java
    ``ExperimentService.complete`` would set it: passed iff the verdict is
    WOULD_PASS AND the sample-size target is met; failed otherwise. NO
    promotion is performed (rule 6) - this is evidence only.

    ``data_source`` is a LABEL ONLY (default 'sample' = the Mac/CI sample mirror;
    'full' = the box full-data H2 re-run for the LIVE/CHAMPION gate). It changes
    no metric, threshold, verdict, or status - only the artifact's data_source
    field + note, so a full-box evidence row is self-describing rather than
    silently carrying the 'sample' label.
    """
    if data_source not in ("sample", "full"):
        raise ValueError(f"data_source must be 'sample' or 'full', got {data_source!r}")
    v = run.verdict
    c = run.criteria
    sample_met = v.sample_size_observed >= c.sample_size_target

    # Supplementary ABSOLUTE checks that do not fit the relative
    # challenger-vs-baseline guardrail shape. Any model that declares an
    # ``absolute_ece_bar`` is gated on the challenger's ABSOLUTE ECE against the
    # Phase-2 exit bar (< bar), in addition to the relative guardrails. This is
    # leakage-free and baseline-agnostic - it does not depend on a possibly
    # degenerate baseline's ECE.
    supplementary: list[dict[str, Any]] = []
    if c.absolute_ece_bar is not None:
        observed_ece = v.challenger_metrics.ece
        passed_abs_ece = observed_ece < c.absolute_ece_bar
        supplementary.append(
            {
                "name": "absolute_ece_phase2_bar",
                "metric": "ece",
                "max_allowed": c.absolute_ece_bar,
                "observed": observed_ece,
                "passed": passed_abs_ece,
                "rationale": "Phase-2 exit bar ECE < bar per model; supplements the "
                "relative ECE guardrail (which is loose at sample scale and "
                "meaningless against a degenerately-calibrated baseline).",
            }
        )

    supplementary_ok = all(s["passed"] for s in supplementary)
    status = "passed" if (v.passed and sample_met and supplementary_ok) else "failed"

    return {
        "schema_version": 1,
        "artifact_name": "promotion_evidence",
        "data_source": data_source,  # LOUD: 'sample' (SHADOW-stage) or 'full' (H2 LIVE gate)
        "data_source_note": (
            (
                "SAMPLE-data evidence clears the SHADOW-stage rule-5 bar per the locked "
                "decision; the LIVE/CHAMPION promotion re-runs on full BOX data (operator "
                "hand-off H2). No promotion is performed here (rule 6)."
            )
            if data_source == "sample"
            else (
                "FULL-box data evidence for the LIVE/CHAMPION promotion gate (operator "
                "hand-off H2) - this is the row the promote-model skill reads. No promotion "
                "is performed here (rule 6); promotion stays human-gated."
            )
        ),
        "model_name": run.model_name,
        # experiment_results column analogues -------------------------------
        "champion_model_name": run.baseline_name,  # the thing the challenger must beat
        "challenger_model_name": run.challenger_name,
        "primary_metric": c.primary_metric.db_value,
        "primary_threshold": c.primary_threshold,
        "guardrails": c.guardrails_as_map(),
        "sample_size_target": c.sample_size_target,
        "sample_size_observed": v.sample_size_observed,
        "champion_metric": v.baseline_metrics.value_for(c.primary_metric),
        "challenger_metric": v.challenger_metrics.value_for(c.primary_metric),
        "guardrails_observed": v.guardrail_deltas,
        "guardrails_violated": v.guardrails_violated,
        "status": status,
        # verdict detail ----------------------------------------------------
        "verdict": {
            "outcome": v.outcome.value,
            "passed": v.passed,
            "sample_size_met": sample_met,
            "supplementary_checks_passed": supplementary_ok,
            "primary_margin_required": c.primary_threshold,
            "primary_margin_observed": (
                v.baseline_metrics.value_for(c.primary_metric)
                - v.challenger_metrics.value_for(c.primary_metric)
            ),
        },
        "supplementary_checks": supplementary,
        # pre-declared criteria (rule 5) - copied in so the gate is self-describing
        "pre_declared_criteria": {
            "primary_metric": c.primary_metric.db_value,
            "primary_threshold": c.primary_threshold,
            "sample_size_target": c.sample_size_target,
            "guardrails": [
                {
                    "metric": g.metric.db_value,
                    "max_delta": g.max_delta,
                    "rationale": g.rationale,
                }
                for g in c.guardrails
            ],
            "absolute_ece_bar": c.absolute_ece_bar,
            "rationale": c.rationale,
        },
        # full metric tables for both predictors ----------------------------
        "champion_full_metrics": {
            "brier": v.baseline_metrics.brier,
            "log_loss": v.baseline_metrics.log_loss,
            "ece": v.baseline_metrics.ece,
        },
        "challenger_full_metrics": {
            "brier": v.challenger_metrics.brier,
            "log_loss": v.challenger_metrics.log_loss,
            "ece": v.challenger_metrics.ece,
        },
        # rolling-origin CV summaries (4-fold mean+/-std, both models) -------
        "rolling_origin_cv": {
            "folds": [
                {
                    "fold_id": f.fold_id,
                    "train_start_year": f.train_start_year,
                    "train_end_year": f.train_end_year,
                    "val_year": f.val_year,
                    "test_year": f.test_year,
                }
                for f in FOLDS
            ],
            "champion_summary": _cv_summary_dict(run.baseline_cv),
            "challenger_summary": _cv_summary_dict(run.challenger_cv),
        },
        "verdict_fold": {
            "fold_id": run.final_fold_id,
            "test_year": run.final_test_year,
        },
        # provenance --------------------------------------------------------
        "provenance": {
            "git_commit": _git_commit_sha(),
            "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "sample_root": str(run.sample_root),
            "rows_per_year": run.rows_per_year,
            "segment_cols": list(SEGMENT_COLS[run.model_name]),
            "rule_13_holdout": "2026 excluded from every split (sample mirror is 2015-2025 only)",
            "split_discipline": "rolling-origin temporal CV; no random_state on any split",
        },
    }


def write_artifact(run: EvidenceRun, out_dir: Path, data_source: str = "sample") -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    # A non-sample run writes a distinct filename (e.g. <model>_experiment_results_full.json)
    # so the box full-data H2 row never clobbers the committed sample-stage row.
    suffix = "" if data_source == "sample" else f"_{data_source}"
    path = out_dir / f"{run.model_name}_experiment_results{suffix}.json"
    path.write_text(json.dumps(experiment_results_artifact(run, data_source), indent=2) + "\n")
    return path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

_MODEL_CHOICES = ("pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline", "all")


@click.command()
@click.option(
    "--model",
    type=click.Choice(_MODEL_CHOICES),
    default="all",
    show_default=True,
    help="Which model's evidence to produce ('all' runs the three).",
)
@click.option(
    "--sample-root",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_SAMPLE_ROOT,
    show_default=True,
    help="samples/dev parquet mirror root (per-year files under <root>/<model>/).",
)
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_OUT_DIR,
    show_default=True,
)
@click.option(
    "--generate-sample/--no-generate-sample",
    default=True,
    show_default=True,
    help="Generate the deterministic Mac proof-of-path sample if the mirror is absent.",
)
@click.option("--rows-per-year", type=int, default=1_500, show_default=True)
@click.option(
    "--data-source",
    type=click.Choice(["sample", "full"]),
    default="sample",
    show_default=True,
    help="Evidence LABEL only (no metric/threshold/verdict change). 'sample' = Mac/CI sample "
    "mirror (SHADOW-stage rule-5 bar); 'full' = box full-data H2 re-run for the LIVE/CHAMPION "
    "gate, written as <model>_experiment_results_full.json so it never clobbers the sample row. "
    "For 'full', point --sample-root at the full-box parquet.",
)
def main(
    model: str,
    sample_root: Path,
    out_dir: Path,
    generate_sample: bool,
    rows_per_year: int,
    data_source: str,
) -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    models = (
        ("pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline")
        if model == "all"
        else (model,)
    )
    for m in models:
        dataset_dir = Path(sample_root) / m
        if generate_sample and not dataset_dir.is_dir():
            log.info("evidence: generating sample mirror for %s under %s", m, dataset_dir)
            generate_sample_dataset(sample_root, m, rows_per_year=rows_per_year)
        run = run_evidence(m, sample_root=Path(sample_root), rows_per_year=rows_per_year)
        art = experiment_results_artifact(run, data_source)
        path = write_artifact(run, Path(out_dir), data_source=data_source)
        v = run.verdict
        # status is the FINAL gate result (relative verdict + sample-size +
        # supplementary absolute checks); verdict.outcome is the relative-gate
        # piece. Report the final status so it matches the artifact.
        click.echo(
            f"[{m}] data_source={data_source} status={art['status'].upper()} "
            f"relative_verdict={v.outcome.value} "
            f"primary({v.primary_metric.db_value}): "
            f"champion={v.baseline_metrics.value_for(v.primary_metric):.4f} "
            f"challenger={v.challenger_metrics.value_for(v.primary_metric):.4f} "
            f"(margin>={v.primary_threshold}); artifact -> {path}"
        )


if __name__ == "__main__":
    main()
