"""OFFLINE batted-ball backfill accuracy (Phase 3 PR-alpha).

Scores historical in-play batted balls through the ``battedball_outcome`` champion
and reports REAL predicted-vs-actual accuracy: per-class confusion, HR precision /
recall, multiclass Brier, log-loss, calibration / ECE, and the per-park grid. This
is a pure OFFLINE held-out eval - it is NEVER "live", and it never feeds a serving
surface.

THE HONESTY CONTRACT (load-bearing - this whole module exists to avoid a placebo):

  - We score the model's distribution against the REAL realized integer ``label``
    (the home-park ``observed_outcome`` 0..4, out/1b/2b/3b/hr), NEVER against the
    ``retro_*`` physics distribution the per-park MLP TRAINED on. Scoring vs
    ``retro_*`` would recreate a placebo - the model trained to match that
    distribution, so it would look beautifully calibrated against it. The point of
    this eval is the gap between the physics-trained distribution and what actually
    happened on the field.
  - The report carries ``data_source="historical_pitches_offline"``, an ``eval_kind``
    computed from the span (``offline_in_sample`` for 2015-2025 - the years the MLP
    trained on - vs ``offline_holdout_unseen`` for the 2026 holdout), and a
    ``disclaimer`` string. The per-park MLP trained on the retro distribution of these
    SAME home-park BIPs, so scoring on 2015-2025 is an IN-SAMPLE read; the 2026 holdout
    is the only genuinely UNSEEN out-of-sample read.
  - Rule 13: a span touching season >= ``HOLDOUT_YEAR`` (2026) is refused, exactly like
    ``export_batted_ball_full`` - EXCEPT the narrow ``allow_holdout_eval`` opt-in (the
    CLI's ``--holdout-accuracy-2026``), which permits 2026 for a post-training accuracy
    READ only (never train/val/export). Per rule 13, "the 2026 Statcast pull exists
    exclusively for post-training, post-validation accuracy testing against unseen data".

ONNX vs torch for the box predictor (:class:`OnnxMlpPredictor`):

  We use the SERVED ONNX graph (``model.onnx``), not the torch ``model.pt`` path
  from ``run_2c9_comparison.py``. Rationale:

    * The honesty contract wants the number the PRODUCTION model would produce. The
      exported ONNX bakes in the per-park softmax (``train._ProbaExport``) and is the
      exact graph the Java serving layer runs (``BattedBallOnnxModel``); the per-park
      isotonic calibrators are applied OUTSIDE the graph in both Python and Java
      (``calibration.transform``). Scoring the served graph means this offline number
      and the live serving path read the same arithmetic - no "eval used a different
      code path than prod" skew.
    * The per-park isotonic calibration is applied OUTSIDE the graph. Forgetting it
      understates calibration badly (run_2c9's WARNING: raw-softmax ECE is ~10x the
      calibrated per-park ECE). :class:`OnnxMlpPredictor` always applies it.
    * torch <-> ONNX parity is verified SEPARATELY (the all-parks parity fixture,
      ``battedball.mlp.parity_fixture_allparks`` + the Java
      ``BattedBallAllParksParityTest``, |java - expected| < 1e-5), so picking the ONNX
      path here costs no fidelity - it inherits that guarantee.

  :class:`OnnxMlpPredictor` is BOX-ONLY and is NOT imported by the unit tests (it
  needs a real ``InferenceSession`` + a registered bundle). The pure scoring
  surface (:func:`score_backfill`, the report dataclass, the dict round-trip) is
  fully unit-testable with a fake :class:`Predictor`.
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Final, Protocol

import numpy as np
import pandas as pd

from bullpen_training.battedball.eval import comparison
from bullpen_training.battedball.eval.comparison import ClassMetrics, ParkMetrics
from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.eval import metrics
from bullpen_training.eval.promotion.sample_loader import HOLDOUT_YEAR

if TYPE_CHECKING:  # pragma: no cover - typing-only imports
    from collections.abc import Sequence

log = logging.getLogger(__name__)

DEFAULT_N_BINS: Final[int] = 15
N_OUTCOMES: Final[int] = len(OUTCOME_NAMES)  # 5
HR_OUTCOME: Final[str] = "hr"

DATA_SOURCE: Final[str] = "historical_pitches_offline"
# eval_kind is computed per run from the season span (see _eval_kind). The MLP trained on
# 2015-2025, so those years are IN-SAMPLE; 2026 (the rule-13 holdout) is the only UNSEEN
# out-of-sample read. (The old single "offline_held_out" constant mislabeled an in-sample
# 2015-2025 run as held-out - the exact honesty trap this phase removes.)
EVAL_KIND_IN_SAMPLE: Final[str] = "offline_in_sample"
EVAL_KIND_HOLDOUT: Final[str] = "offline_holdout_unseen"
EVAL_KIND_MIXED: Final[str] = "offline_mixed_in_and_out_of_sample"
ARTIFACT_NAME: Final[str] = "battedball_backfill_accuracy"
SCHEMA_VERSION: Final[int] = 1


# --- predictor contract ---------------------------------------------------


class Predictor(Protocol):
    """A thing that maps a feature frame to per-row outcome probabilities.

    ``predict_proba(df)`` returns an ``(N, 5)`` float array whose rows sum to 1.
    Each row is the model's distribution for THAT row's OWN home park (the
    per-park MLP emits one distribution per park; the predictor is responsible
    for selecting each row's home-park column). The realized outcome the eval
    scores against is read from ``df["label"]`` by :func:`score_backfill`, NOT
    by the predictor.
    """

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray: ...


# --- report dataclass -----------------------------------------------------


@dataclass(frozen=True)
class BackfillAccuracyReport:
    """The full offline backfill-accuracy artifact for one model x season span."""

    model_name: str
    model_version: str
    season_from: int
    season_to: int
    park_order: tuple[str, ...]
    outcome_order: tuple[str, ...]
    n_samples: int
    # aggregate: {"brier", "log_loss", "ece", "accuracy"} scored vs the REAL label.
    aggregate: dict[str, float]
    per_class: list[ClassMetrics]
    hr_precision: float
    hr_recall: float
    per_park: list[ParkMetrics]
    confusion: list[list[int]]  # summed 5x5 [true][pred]
    data_source: str
    eval_kind: str
    disclaimer: str


# --- pure scoring ---------------------------------------------------------


def score_backfill(
    *,
    predictor: Predictor,
    df: pd.DataFrame,
    park_order: Sequence[str],
    model_name: str,
    model_version: str,
    season_from: int,
    season_to: int,
    disclaimer: str,
    n_bins: int = DEFAULT_N_BINS,
    allow_holdout_eval: bool = False,
) -> BackfillAccuracyReport:
    """Score ``df`` through ``predictor`` and build the offline accuracy report.

    HONESTY: aggregate Brier / log-loss / ECE / accuracy are computed against the
    REAL integer ``df["label"]`` (the realized home-park outcome), NEVER against
    ``retro_*`` (the physics distribution the MLP trained on). ``predictor`` only
    ever sees the feature columns; the truth comes from ``label`` here.

    Rule 13: refuses a span touching ``HOLDOUT_YEAR`` (2026).

    Steps:
      1. ``probs = predictor.predict_proba(df)`` -> ``(N, 5)``, rows sum to 1.
      2. ``labels = df["label"]`` as int -> ``(N,)`` realized outcomes.
      3. aggregate: ``multiclass_brier`` / ``multiclass_log_loss`` /
         ``expected_calibration_error`` vs ``labels``; accuracy = argmax == label.
      4. summed ``confusion`` via ``comparison._confusion`` (pred argmax vs label).
      5. ``per_park`` via ``comparison.per_park_metrics`` (label one-hot per row).
      6. ``per_class`` via ``comparison.class_precision_recall`` on the confusion;
         HR precision / recall pulled from the "hr" row.
    """
    _refuse_holdout(season_from, season_to, allow_holdout_eval=allow_holdout_eval)
    park_order_t = tuple(str(p) for p in park_order)
    outcome_order_t = tuple(OUTCOME_NAMES)

    if "label" not in df.columns:
        raise ValueError("df is missing the realized integer 'label' column to score against")
    if "park" not in df.columns:
        raise ValueError("df is missing the 'park' column needed for the per-park grid")

    probs = np.asarray(predictor.predict_proba(df), dtype=np.float64)
    n = int(probs.shape[0])
    if probs.ndim != 2 or probs.shape[1] != N_OUTCOMES:
        raise ValueError(f"predict_proba must return (N, {N_OUTCOMES}); got {probs.shape}")
    if n != len(df):
        raise ValueError(f"predict_proba returned {n} rows for a {len(df)}-row frame")

    labels = df["label"].to_numpy(dtype=np.int64)
    if labels.min(initial=0) < 0 or labels.max(initial=0) >= N_OUTCOMES:
        raise ValueError(
            f"label values must be in 0..{N_OUTCOMES - 1}; got "
            f"[{int(labels.min())}, {int(labels.max())}]"
        )

    pred_argmax = probs.argmax(axis=1)
    accuracy = float((pred_argmax == labels).mean()) if n else 0.0
    aggregate: dict[str, float] = {
        # metrics.py accepts int-index y_true and one-hots internally; we pass the
        # REAL realized label, which is the entire honesty point.
        "brier": metrics.multiclass_brier(labels, probs),
        "log_loss": metrics.multiclass_log_loss(labels, probs),
        "ece": metrics.expected_calibration_error(labels, probs, n_bins=n_bins),
        "accuracy": accuracy,
    }

    confusion = comparison._confusion(pred_argmax, labels, N_OUTCOMES)

    per_park = comparison.per_park_metrics(
        pred_probs=probs,
        label_distributions=comparison._onehot(labels, N_OUTCOMES),
        park_ids=df["park"].astype(str).tolist(),
        park_order=park_order_t,
        n_bins=n_bins,
        model=model_name,
    )

    per_class = comparison.class_precision_recall(confusion, outcome_order_t)
    hr = next(c for c in per_class if c.outcome == HR_OUTCOME)

    return BackfillAccuracyReport(
        model_name=model_name,
        model_version=model_version,
        season_from=season_from,
        season_to=season_to,
        park_order=park_order_t,
        outcome_order=outcome_order_t,
        n_samples=n,
        aggregate=aggregate,
        per_class=per_class,
        hr_precision=hr.precision,
        hr_recall=hr.recall,
        per_park=per_park,
        confusion=confusion,
        data_source=DATA_SOURCE,
        eval_kind=_eval_kind(season_from, season_to),
        disclaimer=disclaimer,
    )


def _eval_kind(season_from: int, season_to: int) -> str:
    """Honest in-sample vs holdout posture from the season span. The per-park MLP trained on
    2015-2025, so any span within those years is an IN-SAMPLE read; 2026 (the rule-13 holdout,
    excluded from every training/validation split) is the only genuinely UNSEEN out-of-sample
    read - the post-training accuracy test the holdout exists for."""
    if season_from >= HOLDOUT_YEAR:
        return EVAL_KIND_HOLDOUT
    if season_to < HOLDOUT_YEAR:
        return EVAL_KIND_IN_SAMPLE
    return EVAL_KIND_MIXED


def _refuse_holdout(season_from: int, season_to: int, *, allow_holdout_eval: bool = False) -> None:
    """Rule 13: 2026+ is holdout-only; refuse any span that touches it.

    ``allow_holdout_eval`` is the narrow, explicit opt-in for the rule-13 carve-out: 2026 may be
    scored for a POST-TRAINING, POST-VALIDATION accuracy test ("the 2026 Statcast pull exists
    exclusively for accuracy testing against unseen data"). It is set ONLY by the backfill-accuracy
    read path; training, validation, and the ``export_batted_ball_full`` producer never set it and
    keep the hard refusal."""
    if allow_holdout_eval:
        return
    if season_from >= HOLDOUT_YEAR or season_to >= HOLDOUT_YEAR:
        raise ValueError(
            f"rule 13: {HOLDOUT_YEAR}+ is holdout-only; backfill accuracy refuses a span "
            f"touching it (got {season_from}-{season_to}). Pass allow_holdout_eval=True ONLY "
            f"for the rule-13 post-training accuracy read."
        )


# --- serialisation --------------------------------------------------------


def report_to_dict(report: BackfillAccuracyReport) -> dict:
    """Render to a JSON-safe dict (mirrors comparison.report_to_dict conventions)."""
    return {
        "schema_version": SCHEMA_VERSION,
        "artifact_name": ARTIFACT_NAME,
        "artifact_version": "v1",
        "model_name": report.model_name,
        "model_version": report.model_version,
        "season_from": report.season_from,
        "season_to": report.season_to,
        "park_order": list(report.park_order),
        "outcome_order": list(report.outcome_order),
        "n_samples": report.n_samples,
        "aggregate": dict(report.aggregate),
        "per_class": [asdict(c) for c in report.per_class],
        "hr_precision": report.hr_precision,
        "hr_recall": report.hr_recall,
        "per_park": [asdict(p) for p in report.per_park],
        "confusion": [list(row) for row in report.confusion],
        "data_source": report.data_source,
        "eval_kind": report.eval_kind,
        "disclaimer": report.disclaimer,
    }


def report_from_dict(d: dict) -> BackfillAccuracyReport:
    """Round-trip a :func:`report_to_dict` payload back to a report."""
    if d.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unknown backfill-accuracy schema_version: {d.get('schema_version')}")
    return BackfillAccuracyReport(
        model_name=d["model_name"],
        model_version=d["model_version"],
        season_from=int(d["season_from"]),
        season_to=int(d["season_to"]),
        park_order=tuple(d["park_order"]),
        outcome_order=tuple(d["outcome_order"]),
        n_samples=int(d["n_samples"]),
        aggregate={k: float(v) for k, v in d["aggregate"].items()},
        per_class=[ClassMetrics(**c) for c in d["per_class"]],
        hr_precision=float(d["hr_precision"]),
        hr_recall=float(d["hr_recall"]),
        per_park=[ParkMetrics(**p) for p in d["per_park"]],
        confusion=[list(row) for row in d["confusion"]],
        data_source=d["data_source"],
        eval_kind=d["eval_kind"],
        disclaimer=d["disclaimer"],
    )


def save_report(
    report: BackfillAccuracyReport,
    json_path: Path,
    html_path: Path | None = None,
) -> None:
    """Persist the report as JSON + (optionally) a self-contained HTML page."""
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report_to_dict(report), indent=2))
    if html_path is not None:
        html_path.parent.mkdir(parents=True, exist_ok=True)
        html_path.write_text(render_html(report))


def render_html(report: BackfillAccuracyReport) -> str:
    """Render the report to a single self-contained HTML page (inline CSS only).

    Mirrors ``report.render_html``'s editorial-table style. Leads with the
    eval-kind + disclaimer banner so a reader cannot miss that this is an offline
    held-out read scored against the realized label.
    """
    agg = report.aggregate
    class_rows = "".join(
        "<tr>"
        f"<td>{c.outcome}</td>"
        f"<td>{c.precision:.3f}</td>"
        f"<td>{c.recall:.3f}</td>"
        f"<td>{c.f1:.3f}</td>"
        f"<td>{c.support}</td>"
        "</tr>"
        for c in report.per_class
    )
    park_rows = "".join(
        "<tr>"
        f"<td>{p.park_id}</td>"
        f"<td>{p.n_samples}</td>"
        f"<td>{p.brier:.4f}</td>"
        f"<td>{p.ece:.4f}</td>"
        f"<td>{p.accuracy:.3f}</td>"
        "</tr>"
        for p in report.per_park
    )
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Batted-ball backfill accuracy ({report.model_name} {report.model_version})</title>
  <style>
    body {{ font-family: -apple-system, system-ui, sans-serif; max-width: 1000px;
            margin: 2rem auto; padding: 0 1rem; color: #222; }}
    h1, h2 {{ color: #111; }}
    .banner {{ padding: 0.8rem 1rem; border-radius: 6px; background: #fff7e6;
               border-left: 4px solid #d48806; margin: 1rem 0; font-size: 0.95rem; }}
    table {{ border-collapse: collapse; width: 100%; margin-top: 1rem; }}
    th, td {{ padding: 0.4rem 0.6rem; border-bottom: 1px solid #eee;
              text-align: right; font-variant-numeric: tabular-nums; }}
    th {{ background: #fafafa; text-align: center; }}
    td:first-child, th:first-child {{ text-align: left; font-weight: 600; }}
    .agg span {{ display: inline-block; margin-right: 1.4rem; }}
  </style>
</head>
<body>
  <h1>Batted-ball backfill accuracy &mdash; {report.model_name} {report.model_version}</h1>
  <div class="banner">
    <strong>OFFLINE held-out eval</strong> ({report.eval_kind}, source {report.data_source}),
    seasons {report.season_from}-{report.season_to}, {report.n_samples} BIPs.
    Scored against the REALIZED outcome, not the physics distribution.<br />
    {report.disclaimer}
  </div>
  <h2>Aggregate (vs realized label)</h2>
  <div class="agg">
    <span><strong>Brier:</strong> {agg["brier"]:.4f}</span>
    <span><strong>Log-loss:</strong> {agg["log_loss"]:.4f}</span>
    <span><strong>ECE:</strong> {agg["ece"]:.4f}</span>
    <span><strong>Accuracy:</strong> {agg["accuracy"]:.3f}</span>
  </div>
  <h2>Per-class (precision / recall / F1 / support)</h2>
  <table>
    <thead><tr><th>Outcome</th><th>Precision</th><th>Recall</th><th>F1</th>
      <th>Support</th></tr></thead>
    <tbody>{class_rows}</tbody>
  </table>
  <h2>Per-park (lower Brier / ECE is better)</h2>
  <table>
    <thead><tr><th>Park</th><th>N</th><th>Brier</th><th>ECE</th><th>Accuracy</th></tr></thead>
    <tbody>{park_rows}</tbody>
  </table>
</body>
</html>
"""


# --- box-only predictor (NOT imported by unit tests) ----------------------


class OnnxMlpPredictor:
    """Served-graph predictor for the ``battedball_outcome`` champion (BOX-ONLY).

    Loads the registered bundle's ``model.onnx`` (per-park softmax baked in,
    emitting ``(N, n_parks, 5)``), the per-park isotonic ``calibrator.json``, and
    the ``FeatureScaler`` + ``park_order`` from ``metadata.json``. ``predict_proba``:

      1. builds + z-scores the 15-feature vector for each row (the production
         ``FeaturePipelineBattedBall`` order, via the metadata scaler);
      2. runs the served ONNX graph -> ``(N, n_parks, 5)`` per-park softmax;
      3. applies the per-park isotonic calibrators OUTSIDE the graph
         (``calibration.transform``) - forgetting this understates calibration
         ~10x (run_2c9's WARNING);
      4. selects each row's OWN home-park column -> ``(N, 5)``.

    A park not in ``park_order`` is routed to the pooled head if the architecture
    exposes one; this MLP has no pooled head (a backbone + per-park ``ModuleList``,
    see ``mlp.architecture``), so the fallback is the MEAN across the calibrated
    park distributions for that row, logged at WARNING. (The real 30-park bundle
    covers every MLB park, so the fallback is a guard, not an expected path.)

    NOT imported by the unit tests: it needs a real ``onnxruntime`` session + a
    registered bundle. torch <-> ONNX parity is verified separately by the
    all-parks parity fixture (see the module docstring).
    """

    def __init__(self, model_dir: Path) -> None:
        # Imported lazily so the pure scoring surface (and its tests) never pulls
        # onnxruntime / the heavy serving deps just to score a fake predictor.
        import onnxruntime as ort

        from bullpen_training.battedball.mlp.calibration import load_calibrator

        self.model_dir = Path(model_dir)
        metadata = json.loads((self.model_dir / "metadata.json").read_text())
        self.model_name = str(metadata.get("model_name", "battedball_outcome"))
        self.model_version = str(metadata.get("model_version", "v1"))
        self.feature_order: list[str] = list(metadata["feature_names"])
        self.park_order: tuple[str, ...] = tuple(str(p) for p in metadata["park_order"])
        self.outcome_order: tuple[str, ...] = tuple(
            str(o) for o in metadata.get("outcome_names", OUTCOME_NAMES)
        )
        scaler = metadata["feature_scaler"]
        self._means = np.asarray(scaler["means"], dtype=np.float64)
        self._stds = np.asarray(scaler["stds"], dtype=np.float64)
        self._park_index = {p: i for i, p in enumerate(self.park_order)}

        self._calibrators = load_calibrator(self.model_dir / "calibrator.json")
        self._session = ort.InferenceSession(str(self.model_dir / "model.onnx"))
        self._input_name = self._session.get_inputs()[0].name

    def _build_matrix(self, df: pd.DataFrame) -> np.ndarray:
        """Build the z-scored ``(N, n_features)`` matrix in ``feature_order``.

        The full-data export (``export_batted_ball_full.rows_to_frame``) already
        materialises every production feature column (the 4 physics measures +
        ``stand_R``/``stand_L`` + ``base_state_0..7`` + ``outs``); we select them in
        the contract order and z-score with the metadata scaler. One-hot columns
        carry identity mean/std, matching ``FeatureScaler`` + the Java pipeline.
        """
        missing = [c for c in self.feature_order if c not in df.columns]
        if missing:
            raise ValueError(
                f"feature frame is missing model features {missing}; present columns "
                f"do not cover the {len(self.feature_order)}-feature contract"
            )
        raw = df[list(self.feature_order)].to_numpy(dtype=np.float64)
        scaled = (raw - self._means) / self._stds
        return scaled.astype(np.float32)

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        scaled = self._build_matrix(df)
        # Lazy import keeps the pure scoring surface torch/onnx-free.
        from bullpen_training.battedball.mlp.calibration import transform as apply_calibrators

        raw = self._session.run(None, {self._input_name: scaled})[0]
        per_park = np.asarray(raw, dtype=np.float64)  # (N, n_parks, 5)
        calibrated = np.asarray(apply_calibrators(self._calibrators, per_park), dtype=np.float64)

        parks = df["park"].astype(str).to_numpy()
        out = np.empty((calibrated.shape[0], calibrated.shape[2]), dtype=np.float64)
        unknown: set[str] = set()
        for i, park in enumerate(parks):
            idx = self._park_index.get(park)
            if idx is None:
                # No pooled head in this architecture -> mean across parks (logged).
                out[i] = calibrated[i].mean(axis=0)
                unknown.add(park)
            else:
                out[i] = calibrated[i, idx]
        if unknown:
            log.warning(
                "backfill: %d row(s) had parks not in park_order %s; "
                "routed to the mean-across-parks fallback (no pooled head in the MLP)",
                len(unknown),
                sorted(unknown),
            )
        # Renormalise defensively (calibration.transform already renormalises each
        # park row; the mean-across-parks fallback can drift by float noise).
        out = np.clip(out, 1e-12, None)
        out = out / out.sum(axis=1, keepdims=True)
        return out


__all__ = (
    "ARTIFACT_NAME",
    "DATA_SOURCE",
    "DEFAULT_N_BINS",
    "EVAL_KIND_HOLDOUT",
    "EVAL_KIND_IN_SAMPLE",
    "EVAL_KIND_MIXED",
    "BackfillAccuracyReport",
    "OnnxMlpPredictor",
    "Predictor",
    "render_html",
    "report_from_dict",
    "report_to_dict",
    "save_report",
    "score_backfill",
)
