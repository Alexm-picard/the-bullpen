"""Faithful rolling-origin CV evidence for the SERVED ``battedball_outcome`` champion (Phase 4).

The promotion driver's ``--model batted_ball_mlp`` CVs a ``PerParkMLP`` on home-park parquet folds
(30 independent per-park models) and reconciles it BY NAME to ``battedball_outcome``. But the model
actually SERVED is the shared-backbone ``mlp.architecture.BattedBallMLP`` (the only producer of the
single ``[N,30,5]`` graph the Java reader loads; also writes ``model_name="battedball_outcome"``).
So the driver's evidence is for a DIFFERENT architecture than what's deployed - a pre-existing
evidence-vs-served gap.

This module closes that gap for the carry champion: it evaluates the ACTUAL served architecture,
training the real ``BattedBallMLP`` (+ the Phase-4 carry head) on the 30-park retrodicted labels
(``mlp.dataset.load_arrays``) and SCORING it on the home-park realized outcome - apples-to-apples
with the LR baseline (both scored on the same home-park test rows). It
emits the SAME ``experiment_results``-shaped artifact the driver does (reuses :class:`EvidenceRun` +
:func:`experiment_results_artifact`), so ``promote-model`` reads it 1:1. It performs NO promotion
(rule 6) - evidence only.

It also runs a carry sanity gate (the carry head's per-park feet must be physically plausible) so a
mis-standardised or broken carry head is caught at evidence time - the served-path load gate
(``ModelLoadValidator``) only exercises output[0] (registry-guard NOTE).

KNOWN LIMITATION (calibration): this scores the model's RAW per-park softmax. The served path
additionally applies per-park isotonic calibration (decision [51]). Raw scoring is apples-to-apples
for the Brier PRIMARY (the LR baseline is likewise its own raw ``predict_proba``), but the ABSOLUTE
ECE bar (0.02) here reflects the UNCALIBRATED model - the served calibrated ECE is lower. So a fail
on the absolute ECE bar is NOT disqualifying on its own; read it alongside the served calibrator.
Applying the per-park isotonic calibration (fit on the val fold, like the driver's
``per_park_isotonic`` path) is the natural follow-up to make the ECE bar fully served-faithful.

BOX-ONLY end-to-end: ``load_arrays`` is ``docker exec clickhouse`` and training wants the GPU. The
pure scoring + carry gate + evidence assembly are dependency-injected so they unit-test on the Mac
with synthetic data; ``main`` wires the real ClickHouse + GPU implementations for the box run.

Rule 13: never evaluates 2026 - the rolling-origin folds (cv_harness.FOLDS) span 2015-2025 only.
"""

from __future__ import annotations

import argparse
import json
import logging
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from bullpen_training.battedball.mlp.train import CARRY_MEAN_FT, CARRY_STD_FT
from bullpen_training.eval.cv_harness import FOLDS, CVResult, FoldResult, FoldSpec
from bullpen_training.eval.metrics import multiclass_brier
from bullpen_training.eval.promotion.criteria import (
    PromotionCriteria,
    criteria_for,
    evaluate_challenger_vs_baseline,
)
from bullpen_training.eval.promotion.driver import (
    EvidenceRun,
    _aggregate_retro_ece,
    experiment_results_artifact,
)

log = logging.getLogger(__name__)

# The served champion (faithful) vs the driver's PerParkMLP proxy "batted_ball_mlp". The artifact
# keeps the criteria + reconciliation name "batted_ball_mlp" (the OUTCOME gate thresholds are
# architecture-agnostic, and the box's batted_ball_mlp -> battedball_outcome reconciliation must
# still match), but records the faithful challenger in the note.
CRITERIA_MODEL_NAME = "batted_ball_mlp"
BASELINE_NAME = "batted_ball_lr_baseline"
FAITHFUL_CHALLENGER_NAME = "battedball_outcome"

# Physically plausible per-park mean carry (feet) for the canonical scorcher. A head whose
# un-standardised output falls outside this is mis-standardised / broken - fail the gate loud.
CARRY_MIN_FT = 50.0
CARRY_MAX_FT = 550.0

N_OUTCOMES = 5


# --- pure scoring -----------------------------------------------------------


def select_home_park_proba(
    proba_30: np.ndarray, parks: Sequence[str], park_order: Sequence[str]
) -> np.ndarray:
    """From a ``(N, n_parks, n_outcomes)`` per-park distribution, pick each BIP's OWN home-park row.

    Returns ``(N, n_outcomes)``. The served champion predicts all 30 parks; the realized outcome
    only exists at the BIP's home park, so the faithful score selects that column - the same value
    the served path surfaces for that park. Fails loud on an unknown park (a snapshot/data defect),
    never silently mis-scoring.
    """
    if proba_30.ndim != 3:
        raise ValueError(f"proba_30 must be (N, n_parks, n_outcomes), got shape {proba_30.shape}")
    if len(parks) != proba_30.shape[0]:
        raise ValueError(f"parks length {len(parks)} != N {proba_30.shape[0]}")
    if len(park_order) != proba_30.shape[1]:
        raise ValueError(
            f"park_order length {len(park_order)} != proba park axis {proba_30.shape[1]}"
        )
    index = {p: i for i, p in enumerate(park_order)}
    out = np.empty((proba_30.shape[0], proba_30.shape[2]), dtype=np.float64)
    for row, park in enumerate(parks):
        if park not in index:
            raise ValueError(f"home park {park!r} not in park_order")
        out[row] = proba_30[row, index[park]]
    return out


@dataclass(frozen=True)
class CarryGateResult:
    passed: bool
    per_park_ft: dict[str, float]
    reasons: tuple[str, ...]


def carry_gate(
    carry_standardised_by_park: dict[str, float],
    *,
    min_ft: float = CARRY_MIN_FT,
    max_ft: float = CARRY_MAX_FT,
) -> CarryGateResult:
    """Un-standardise the carry head's per-park output to feet and assert physical plausibility.

    ``carry_standardised_by_park`` is the raw (standardised) carry the head emits for a canonical
    barrel at each park. ft = raw * CARRY_STD_FT + CARRY_MEAN_FT. The gate passes iff EVERY park's
    carry is finite and in ``[min_ft, max_ft]`` - catching a mis-standardised / NaN / degenerate
    head that the output[0]-only served-path load gate would miss.
    """
    per_park_ft: dict[str, float] = {}
    reasons: list[str] = []
    for park, raw in sorted(carry_standardised_by_park.items()):
        ft = float(raw) * CARRY_STD_FT + CARRY_MEAN_FT
        per_park_ft[park] = ft
        if not np.isfinite(ft):
            reasons.append(f"{park}: carry not finite ({ft})")
        elif ft < min_ft or ft > max_ft:
            reasons.append(f"{park}: carry {ft:.1f} ft outside [{min_ft:.0f}, {max_ft:.0f}]")
    return CarryGateResult(passed=not reasons, per_park_ft=per_park_ft, reasons=tuple(reasons))


def _cv_result(per_fold_brier: list[tuple[int, int, float]]) -> CVResult:
    """Assemble a :class:`CVResult` (brier-only) from ``[(fold_id, test_rows, brier), ...]``."""
    fold_results = tuple(
        FoldResult(fold_id=fid, train_rows=0, val_rows=0, test_rows=rows, metrics={"brier": b})
        for fid, rows, b in per_fold_brier
    )
    briers = np.asarray([b for _f, _r, b in per_fold_brier], dtype=np.float64)
    summary = {"brier": (float(briers.mean()), float(briers.std()))}
    return CVResult(per_fold=fold_results, summary=summary)


# --- fold types (injected impure deps live behind these) --------------------


@dataclass(frozen=True)
class FoldPrediction:
    """One fold's home-park test scoring inputs for both models, plus row counts."""

    fold_id: int
    test_year: int
    test_rows: int
    y_true_int: np.ndarray  # (M,) realized outcome 0..4
    challenger_proba: np.ndarray  # (M, 5) served-arch home-park distribution
    baseline_proba: np.ndarray  # (M, 5) LR baseline home-park distribution
    # (M, 5) home-park retro distribution; used ONLY on the final fold for the per-class
    # ECE-vs-retro the absolute ECE bar is defined on (decision [141]). None -> the artifact falls
    # back to the realized-outcome ECE for the absolute bar.
    retro: np.ndarray | None = None


# A FoldRunner trains both models for a fold and returns the home-park test scoring inputs. The box
# implementation (main) trains the real BattedBallMLP via load_arrays + LR baseline; tests inject
# a synthetic one so the orchestration + verdict + artifact assembly run with no ClickHouse/GPU.
FoldRunner = Callable[[FoldSpec], FoldPrediction]


def run_faithful_cv(
    fold_runner: FoldRunner,
    *,
    carry_gate_result: CarryGateResult,
    folds: Sequence[FoldSpec] = FOLDS,
    rows_per_year: int = 0,
    criteria: PromotionCriteria | None = None,
    model_name: str = CRITERIA_MODEL_NAME,
    baseline_name: str = BASELINE_NAME,
    challenger_name: str = FAITHFUL_CHALLENGER_NAME,
) -> tuple[EvidenceRun, CarryGateResult]:
    """Run the faithful rolling-origin CV and build the ``experiment_results``-shaped EvidenceRun.

    The verdict is computed on the FINAL fold's home-park test (challenger vs baseline), exactly as
    the driver does; the per-fold Brier feeds the CV summary. ``carry_gate_result`` is the served
    architecture's carry sanity (built by the caller from the final-fold model). No promotion.

    Defaults reproduce the faithful eval (challenger=served MLP vs the LR ``baseline``, criteria
    ``batted_ball_mlp``). The carry-PROMOTION eval (``carry_promotion_eval``) reuses this
    orchestration with ``criteria`` = the NON-INFERIORITY criteria + a ``baseline`` that is the
    no-carry MLP recipe (v1's method) and the names relabelled accordingly - so the FoldRunner there
    returns (carry-MLP challenger, no-carry-MLP baseline) instead of (MLP, LR).
    """
    if not folds:
        raise ValueError("no folds")
    chal_fold: list[tuple[int, int, float]] = []
    base_fold: list[tuple[int, int, float]] = []
    final: FoldPrediction | None = None
    for fold in folds:
        pred = fold_runner(fold)
        chal_brier = multiclass_brier(pred.y_true_int, pred.challenger_proba)
        base_brier = multiclass_brier(pred.y_true_int, pred.baseline_proba)
        chal_fold.append((fold.fold_id, pred.test_rows, chal_brier))
        base_fold.append((fold.fold_id, pred.test_rows, base_brier))
        log.info(
            "fold %d (test %d): challenger brier=%.4f baseline brier=%.4f (n=%d)",
            fold.fold_id,
            pred.test_year,
            chal_brier,
            base_brier,
            pred.test_rows,
        )
        final = pred

    assert final is not None
    criteria = criteria if criteria is not None else criteria_for(CRITERIA_MODEL_NAME)
    verdict = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=final.y_true_int,
        baseline_proba=final.baseline_proba,
        challenger_proba=final.challenger_proba,
    )
    # Per-class ECE vs the RETRO distribution on the final fold - the convention the abs ECE bar
    # ([141]) is defined on (the served model is calibrated to retro, so realized-outcome ECE would
    # be an unfair bar). Matches the driver's _aggregate_retro_ece exactly.
    challenger_retro_ece: float | None = None
    baseline_retro_ece: float | None = None
    if final.retro is not None:
        challenger_retro_ece = _aggregate_retro_ece(final.challenger_proba, final.retro)
        baseline_retro_ece = _aggregate_retro_ece(final.baseline_proba, final.retro)
    run = EvidenceRun(
        model_name=model_name,
        criteria=criteria,
        baseline_cv=_cv_result(base_fold),
        challenger_cv=_cv_result(chal_fold),
        verdict=verdict,
        baseline_name=baseline_name,
        challenger_name=challenger_name,
        final_fold_id=final.fold_id,
        final_test_year=final.test_year,
        sample_root=Path("clickhouse://bbip_retrodicted_labels"),
        rows_per_year=rows_per_year,
        challenger_retro_ece=challenger_retro_ece,
        baseline_retro_ece=baseline_retro_ece,
    )
    return run, carry_gate_result


def build_artifact(
    run: EvidenceRun, carry: CarryGateResult, *, data_source: str = "full"
) -> dict[str, Any]:
    """The driver's experiment_results artifact, annotated with the faithful-eval provenance + the
    carry gate result so the box's promote-model reads the SAME shape but sees this is the served
    BattedBallMLP (not the PerParkMLP proxy) and whether carry passed its sanity gate."""
    art = experiment_results_artifact(run, data_source=data_source)
    art["faithful_eval"] = {
        "challenger_arch": "battedball_outcome (shared-backbone BattedBallMLP, the served graph)",
        "note": (
            "Faithful rolling-origin CV of the ACTUAL served architecture (not the driver's "
            "PerParkMLP proxy): trained on 30-park retro labels, scored on home-park realized "
            "outcome. Carry head sanity-gated."
        ),
        # Self-describing provenance so the human reading this JSON at promotion time knows WHY the
        # absolute ECE bar may read 'failed' and that the reused provenance.loader label is generic.
        "calibration": "raw_softmax_uncalibrated (served path adds per-park isotonic; "
        "the absolute ECE bar here reflects the UNCALIBRATED model)",
        "loader": "clickhouse + gpu (load_arrays 30-park train, home-park realized scoring)",
        "carry_gate_passed": carry.passed,
        "carry_gate_reasons": list(carry.reasons),
        "carry_per_park_ft": carry.per_park_ft,
    }
    return art


# --- box wiring (ClickHouse + GPU; not exercised by the Mac unit tests) -----


def _box_fold_runner(
    park_order: tuple[str, ...], *, epochs: int, carry_weight: float
) -> FoldRunner:
    """The real fold runner: trains the served BattedBallMLP on 30-park CH labels + the LR baseline,
    scores both on the home-park realized outcome. ClickHouse + GPU; box-only."""
    import torch
    from sklearn.linear_model import LogisticRegression

    from bullpen_training.battedball.features_shared import FEATURE_NAMES
    from bullpen_training.battedball.mlp.dataset import (
        BBIPDataset,
        FeatureScaler,
        _run_clickhouse,
        load_arrays,
    )
    from bullpen_training.battedball.mlp.train import train_model
    from bullpen_training.eval.promotion.export_batted_ball_full import (
        build_year_query,
        rows_to_frame,
    )
    from bullpen_training.eval.promotion.sample_loader import RETRO_COLS

    def _home_park(year: int) -> tuple[np.ndarray, list[str], np.ndarray, np.ndarray]:
        # Production home-park rows for one season: the 15-feature vector + realized label + park id
        # + the retrodicted distribution (for the final-fold retro ECE).
        df = rows_to_frame(_run_clickhouse(build_year_query(year)))
        feats = df[list(FEATURE_NAMES)].to_numpy(dtype=np.float32)
        parks = [str(p) for p in df["park"].tolist()]
        labels = np.asarray(df["label"], dtype=np.int64)
        retro = df[list(RETRO_COLS)].to_numpy(dtype=np.float64)
        return feats, parks, labels, retro

    def runner(fold: FoldSpec) -> FoldPrediction:
        feat30, lab30, carry30 = load_arrays(
            season_from=fold.train_start_year,
            season_to=fold.train_end_year,
            park_order=park_order,
        )
        scaler = FeatureScaler.fit(feat30)
        train_ds = BBIPDataset(feat30, lab30, carry=carry30, scaler=scaler)
        model, _summary = train_model(
            train_ds, n_epochs=epochs, carry_weight=carry_weight, n_parks=len(park_order)
        )
        model.cpu().eval()

        test_feat, test_parks, y_true, test_retro = _home_park(fold.test_year)
        with torch.no_grad():
            logits, _carry = model(torch.from_numpy(scaler.transform(test_feat)))
            proba30 = torch.softmax(logits, dim=-1).numpy()
        challenger_proba = select_home_park_proba(proba30, test_parks, park_order)

        # LR baseline (rule 9): multinomial LR (sklearn auto-detects multinomial for multiclass; the
        # multi_class arg is removed in modern sklearn) on home-park train rows -> realized label.
        tr_feats: list[np.ndarray] = []
        tr_labels: list[np.ndarray] = []
        for yr in range(fold.train_start_year, fold.train_end_year + 1):
            f, _p, lab, _r = _home_park(yr)
            tr_feats.append(scaler.transform(f))
            tr_labels.append(lab)
        lr = LogisticRegression(max_iter=2000)
        lr.fit(np.concatenate(tr_feats), np.concatenate(tr_labels))
        baseline_proba = _align_proba(lr, scaler.transform(test_feat))

        return FoldPrediction(
            fold_id=fold.fold_id,
            test_year=fold.test_year,
            test_rows=int(y_true.shape[0]),
            y_true_int=y_true,
            challenger_proba=challenger_proba,
            baseline_proba=baseline_proba,
            retro=test_retro,
        )

    return runner


def _align_proba(clf: Any, x: np.ndarray) -> np.ndarray:
    """``predict_proba`` re-expanded to all 5 outcome columns (a class absent from the LR's train
    fold gets a 0 column), so the baseline matrix is always ``(M, 5)`` for the brier metric."""
    proba = clf.predict_proba(x)
    out = np.zeros((x.shape[0], N_OUTCOMES), dtype=np.float64)
    for col, cls in enumerate(clf.classes_):
        out[:, int(cls)] = proba[:, col]
    return out


def _box_carry_gate(
    park_order: tuple[str, ...], *, epochs: int, carry_weight: float
) -> CarryGateResult:
    """Train one final model on the last fold's train window + read the carry head on a canonical
    barrel at every park, then run :func:`carry_gate`. Box-only (CH + GPU)."""
    import torch

    from bullpen_training.battedball.mlp.dataset import BBIPDataset, FeatureScaler, load_arrays
    from bullpen_training.battedball.mlp.sanity import canonical_features
    from bullpen_training.battedball.mlp.train import train_model

    final_fold = FOLDS[-1]
    feat30, lab30, carry30 = load_arrays(
        season_from=final_fold.train_start_year,
        season_to=final_fold.train_end_year,
        park_order=park_order,
    )
    scaler = FeatureScaler.fit(feat30)
    train_ds = BBIPDataset(feat30, lab30, carry=carry30, scaler=scaler)
    model, _summary = train_model(
        train_ds, n_epochs=epochs, carry_weight=carry_weight, n_parks=len(park_order)
    )
    model.cpu().eval()
    canonical = scaler.transform(canonical_features()[None, :])
    with torch.no_grad():
        _logits, carry = model(torch.from_numpy(canonical))
    carry_std = carry.squeeze(-1)[0].numpy()  # (n_parks,) standardised
    by_park = {park_order[i]: float(carry_std[i]) for i in range(len(park_order))}
    return carry_gate(by_park)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(
        description="Faithful rolling-origin CV for the served battedball_outcome champion."
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("artifacts/battedball_outcome_faithful_experiment_results_full.json"),
        help="Where to write the experiment_results-shaped evidence artifact.",
    )
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--carry-weight", type=float, default=1.0)
    args = parser.parse_args()

    from bullpen_training.battedball.parks.loader import load_all_parks

    park_order = tuple(sorted(load_all_parks().keys()))
    log.info("faithful CV over %d folds, %d parks", len(FOLDS), len(park_order))

    runner = _box_fold_runner(park_order, epochs=args.epochs, carry_weight=args.carry_weight)
    # _box_carry_gate re-trains the final fold's model for the carry probe (it needs the model
    # object, not the scoring inputs the runner returns). Deliberate extra train, box-only - one
    # fold (~minutes at the box's ~1200 bips/s), not worth threading the model out of the runner.
    carry = _box_carry_gate(park_order, epochs=args.epochs, carry_weight=args.carry_weight)
    run, carry = run_faithful_cv(runner, carry_gate_result=carry)
    artifact = build_artifact(run, carry, data_source="full")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(artifact, indent=2) + "\n")
    log.info(
        "wrote %s (status=%s, carry_gate_passed=%s)",
        args.out,
        artifact.get("status"),
        carry.passed,
    )
    if not carry.passed:
        log.warning("CARRY GATE FAILED: %s", "; ".join(carry.reasons))


if __name__ == "__main__":
    main()


__all__ = (
    "CarryGateResult",
    "FoldPrediction",
    "build_artifact",
    "carry_gate",
    "run_faithful_cv",
    "select_home_park_proba",
)
