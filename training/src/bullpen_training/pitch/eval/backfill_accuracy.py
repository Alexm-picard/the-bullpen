"""OFFLINE pitch_outcome_post holdout accuracy on the 2026 season (M1 rider R2).

Scores labeled 2026 pitches through the registered ``pitch_outcome_post``
bundle and reports REAL predicted-vs-actual accuracy: top-1 / top-2 accuracy,
the marginal-baseline top-1 / top-2 (predicting the label distribution's most
frequent classes), and a per-class breakdown. This is a pure OFFLINE held-out
eval - it is NEVER "live", and it never feeds a serving surface.

THE RULE-13 CARVE-OUT (load-bearing framing, mirrors the battedball sibling
``battedball.eval.backfill_accuracy``): 2026 season data is holdout-only.
Per rule 13, "the 2026 Statcast pull exists exclusively for post-training,
post-validation accuracy testing against unseen data" - this module IS that
accuracy test for the post head. It reads 2026, computes accuracy, and does
nothing else: no training, no validation, no export.

THE INVERSE FENCE: unlike the sibling (which defaults to training years and
needs ``--holdout-accuracy-2026`` to touch 2026), this tool is HOLDOUT-ONLY
and refuses any season BEFORE ``HOLDOUT_YEAR`` (2026). A 2025 run would
re-score data the post head trained and validated on (rolling-origin folds,
isotonic fit) - double-dipping that would masquerade as a holdout number.
The fence is the exact inverse of ``sample_loader``'s rule-13 refusal.

MISSING-REFERENCE NOTE: the TD's reference implementation (``eval_post_2026.py``,
a one-off box script) was NOT delivered. This module is rebuilt from the
in-repo pieces that script reused:

  * window-scoped feature loaders: ``features.tier_1_2.load_labeled_pitches``
    (Tier 1 + realized label from ``pitches``), ``features.tier_3_form.
    load_tier3_for_window`` (rolling form, temporal cutoff baked into the
    SQL), ``features.tier_4_postpitch.load_tier4_for_window`` (post-pitch
    physics);
  * the SERVING-side preprocess: ``parity_fixture_post._preprocess`` - the
    exact Python mirror of Java's ``FeaturePipelinePitchPost.transform``
    (TE lookups with prior fallback, park / pitch-type integer mappings,
    NaN passthrough), so this offline number and the serving path read the
    same arithmetic;
  * the SERVED calibrator: ``IsotonicCalibrator.from_json(calibrator.json)``
    applied outside the ONNX graph, exactly as ``IsotonicCalibratorJava``
    does at serve time.

REFERENCE RUN (box, ``pitch_outcome_post`` v1 champion bundle, 2026 season to
the run date): n_pitches=237,396; top-1 accuracy 0.591; top-2 accuracy 0.808;
marginal baseline top-1 0.366 / top-2 0.554. A re-run on the box should
reproduce these numbers (modulo new 2026 pitches ingested since).

The committed JSON lands under ``training/data/eval/`` in the same shape
family as ``battedball_backfill_accuracy_v1.json`` so the backend's
``AccuracyEvidenceRepository`` / the ``/accuracy`` scorecard CAN bundle it
later with a one-line ``processResources`` include (deliberately NOT wired
in this change).

Box-only run (ADR-0006: authored + unit-tested on the Mac, the scoring RUN
happens on the box; DO NOT commit a Mac-fabricated result):

    uv run python -m bullpen_training.pitch.eval.backfill_accuracy \\
        --model-dir artifacts/pitch_outcome_post/v1 \\
        --season 2026 --out data/eval/pitch_post_backfill_accuracy_v1.json
"""

from __future__ import annotations

import argparse
import json
import logging
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from typing import Any, Final, cast

import numpy as np
import pandas as pd

from bullpen_training.eval.promotion.sample_loader import HOLDOUT_YEAR
from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.tier_1_2 import load_labeled_pitches
from bullpen_training.features.tier_3_form import load_tier3_for_window
from bullpen_training.features.tier_4_postpitch import (
    PK_JOIN,
    load_tier4_for_window,
    merge_tier4,
)
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.eval._shared import labels_to_int, onnx_probabilities

log = logging.getLogger(__name__)

N_CLASSES: Final[int] = len(LABEL_CLASSES)  # 5

DATA_SOURCE: Final[str] = "historical_pitches_offline"
EVAL_KIND_HOLDOUT: Final[str] = "offline_holdout_unseen"
ARTIFACT_NAME: Final[str] = "pitch_post_backfill_accuracy"
SCHEMA_VERSION: Final[int] = 1
DEFAULT_OUT_PATH: Final[Path] = Path("data/eval/pitch_post_backfill_accuracy_v1.json")
DEFAULT_BATCH_SIZE: Final[int] = 8192

# The honesty contract REQUIRES the run to state its leakage posture. This tool has exactly
# one posture (holdout-only), so there is one disclaimer - the sibling's in-sample variant
# has no analogue here because the inverse fence refuses training/validation years outright.
HOLDOUT_DISCLAIMER: Final[str] = (
    "2026 rule-13 holdout accuracy (UNSEEN): scored against the REALIZED pitch outcome from "
    "the 2026 season, which rule 13 excludes from every training and validation split. This "
    "is the genuine out-of-sample generalization read - the post-training accuracy test the "
    "holdout exists for. Preprocessing, ONNX graph, and isotonic calibrator are the SERVED "
    "pitch_outcome_post artifacts (the parity-fixture preprocess + calibrator.json), so this "
    "is the number the production model would produce. Note: 2026 is a partial, in-progress "
    "season, so n is smaller than a full year."
)


# --- report dataclasses -----------------------------------------------------


@dataclass(frozen=True)
class ClassAccuracy:
    """Per-outcome slice of the holdout read (top-1 precision/recall + top-2 recall)."""

    outcome: str
    support: int
    label_share: float
    top1_precision: float
    top1_recall: float
    top2_recall: float


@dataclass(frozen=True)
class HoldoutAccuracyReport:
    """The full offline holdout-accuracy artifact for one model x season."""

    model_name: str
    model_version: str
    model_dir: str
    season: int
    class_order: tuple[str, ...]
    n_pitches: int
    # headline: {"top1_accuracy", "top2_accuracy", "marginal_top1_accuracy",
    #            "marginal_top2_accuracy"} scored vs the REAL realized label.
    headline: dict[str, float]
    per_class: list[ClassAccuracy]
    data_source: str
    eval_kind: str
    disclaimer: str


# --- pure metric math -------------------------------------------------------


def top_k_accuracy(labels: np.ndarray, probs: np.ndarray, k: int) -> float:
    """Fraction of rows whose realized label is among the k highest-probability classes."""
    if k < 1:
        raise ValueError(f"k must be >= 1; got {k}")
    top_k = np.argsort(probs, axis=1)[:, -k:]
    return float((top_k == labels[:, None]).any(axis=1).mean())


def marginal_top_k_accuracy(labels: np.ndarray, k: int, *, n_classes: int = N_CLASSES) -> float:
    """Top-k accuracy of the marginal baseline: always predict the k most frequent labels.

    The baseline is fit on the SAME rows it is scored on - deliberately generous, so the
    model's lift over it is a lower bound.
    """
    if k < 1:
        raise ValueError(f"k must be >= 1; got {k}")
    counts = np.bincount(labels, minlength=n_classes)
    most_frequent = np.argsort(counts)[-k:]
    return float(np.isin(labels, most_frequent).mean())


def _refuse_non_holdout(season: int) -> None:
    """The inverse rule-13 fence: this tool scores HOLDOUT seasons only.

    A season < ``HOLDOUT_YEAR`` (2026) is data the post head trained and validated on
    (rolling-origin folds + the isotonic fit) - scoring it here would double-dip
    validation data and pass it off as a holdout number. Refuse loudly.
    """
    if season < HOLDOUT_YEAR:
        raise ValueError(
            f"rule 13 inverse fence: this tool is holdout-only ({HOLDOUT_YEAR}+); season "
            f"{season} is training/validation data and scoring it here would double-dip. "
            f"Use the rolling-origin CV harness for training-years evidence."
        )


def _per_class_breakdown(labels: np.ndarray, probs: np.ndarray) -> list[ClassAccuracy]:
    """Hand-computable per-outcome slice: support, share, top-1 P/R, top-2 recall."""
    n = int(labels.shape[0])
    pred_top1 = probs.argmax(axis=1)
    top_2 = np.argsort(probs, axis=1)[:, -2:]
    out: list[ClassAccuracy] = []
    for c, outcome in enumerate(LABEL_CLASSES):
        mask = labels == c
        support = int(mask.sum())
        true_positives = int((pred_top1[mask] == c).sum())
        predicted = int((pred_top1 == c).sum())
        out.append(
            ClassAccuracy(
                outcome=outcome,
                support=support,
                label_share=support / n if n else 0.0,
                top1_precision=true_positives / predicted if predicted else 0.0,
                top1_recall=true_positives / support if support else 0.0,
                top2_recall=float((top_2[mask] == c).any(axis=1).mean()) if support else 0.0,
            )
        )
    return out


# --- pure scoring -----------------------------------------------------------


def score_holdout(
    *,
    labels: np.ndarray,
    probs: np.ndarray,
    season: int,
    model_name: str,
    model_version: str,
    model_dir: str,
    disclaimer: str = HOLDOUT_DISCLAIMER,
) -> HoldoutAccuracyReport:
    """Build the holdout-accuracy report from realized labels + calibrated probabilities.

    HONESTY: everything is scored against the REAL realized integer label (the
    ``pitches.description`` outcome the loaders project), exactly as the battedball
    sibling scores against the realized outcome. ``probs`` must already be the SERVED
    calibrated distribution (:class:`PostHeadOnnxPredictor` produces it).

    Rule 13 inverse fence: refuses ``season < HOLDOUT_YEAR``.
    """
    _refuse_non_holdout(season)
    labels_arr = np.asarray(labels, dtype=np.int64)
    probs_arr = np.asarray(probs, dtype=np.float64)
    if probs_arr.ndim != 2 or probs_arr.shape[1] != N_CLASSES:
        raise ValueError(f"probs must be (N, {N_CLASSES}); got {probs_arr.shape}")
    n = int(probs_arr.shape[0])
    if n == 0:
        raise ValueError("no pitches to score")
    if labels_arr.shape != (n,):
        raise ValueError(f"labels must be ({n},) to match probs; got {labels_arr.shape}")
    if labels_arr.min() < 0 or labels_arr.max() >= N_CLASSES:
        raise ValueError(
            f"label values must be in 0..{N_CLASSES - 1}; got "
            f"[{int(labels_arr.min())}, {int(labels_arr.max())}]"
        )

    headline = {
        "top1_accuracy": top_k_accuracy(labels_arr, probs_arr, 1),
        "top2_accuracy": top_k_accuracy(labels_arr, probs_arr, 2),
        "marginal_top1_accuracy": marginal_top_k_accuracy(labels_arr, 1),
        "marginal_top2_accuracy": marginal_top_k_accuracy(labels_arr, 2),
    }
    return HoldoutAccuracyReport(
        model_name=model_name,
        model_version=model_version,
        model_dir=model_dir,
        season=season,
        class_order=tuple(LABEL_CLASSES),
        n_pitches=n,
        headline=headline,
        per_class=_per_class_breakdown(labels_arr, probs_arr),
        data_source=DATA_SOURCE,
        eval_kind=EVAL_KIND_HOLDOUT,
        disclaimer=disclaimer,
    )


# --- serialisation ----------------------------------------------------------


def report_to_dict(report: HoldoutAccuracyReport) -> dict[str, Any]:
    """Render to a JSON-safe dict (mirrors the battedball sibling's conventions)."""
    return {
        "schema_version": SCHEMA_VERSION,
        "artifact_name": ARTIFACT_NAME,
        "artifact_version": "v1",
        "model_name": report.model_name,
        "model_version": report.model_version,
        "model_dir": report.model_dir,
        "season": report.season,
        "class_order": list(report.class_order),
        "n_pitches": report.n_pitches,
        "headline": dict(report.headline),
        "per_class": [asdict(c) for c in report.per_class],
        "data_source": report.data_source,
        "eval_kind": report.eval_kind,
        "disclaimer": report.disclaimer,
    }


def report_from_dict(d: dict[str, Any]) -> HoldoutAccuracyReport:
    """Round-trip a :func:`report_to_dict` payload back to a report."""
    if d.get("schema_version") != SCHEMA_VERSION:
        raise ValueError(f"unknown holdout-accuracy schema_version: {d.get('schema_version')}")
    return HoldoutAccuracyReport(
        model_name=d["model_name"],
        model_version=d["model_version"],
        model_dir=d["model_dir"],
        season=int(d["season"]),
        class_order=tuple(d["class_order"]),
        n_pitches=int(d["n_pitches"]),
        headline={k: float(v) for k, v in d["headline"].items()},
        per_class=[ClassAccuracy(**c) for c in d["per_class"]],
        data_source=d["data_source"],
        eval_kind=d["eval_kind"],
        disclaimer=d["disclaimer"],
    )


def save_report(report: HoldoutAccuracyReport, json_path: Path) -> None:
    """Persist the report as JSON."""
    json_path.parent.mkdir(parents=True, exist_ok=True)
    json_path.write_text(json.dumps(report_to_dict(report), indent=2) + "\n")


# --- box-only data assembly + predictor (NOT imported by unit tests) --------


def load_holdout_frame(client: Any, *, season: int, limit: int | None = None) -> pd.DataFrame:
    """Assemble the holdout feature frame for one season (BOX-ONLY: needs ClickHouse).

    Reuses the exact window-scoped loaders the feature build uses:
    ``load_labeled_pitches`` (Tier 1 + realized string label),
    ``load_tier3_for_window`` (rolling form, temporal cutoff in the SQL), and
    ``load_tier4_for_window`` + ``merge_tier4`` (post-pitch physics; fills missing
    ``pitch_type`` with ""). Tier 3 rows the 5-class label filter excluded (HBP)
    drop out of the LEFT join, matching the training build's join semantics.

    Rows are sorted by (game_date, PK) so ``limit`` deterministically takes the
    season's first N pitches regardless of ClickHouse read order.
    """
    start, end = date(season, 1, 1), date(season, 12, 31)
    labeled = load_labeled_pitches(client, start_date=start, end_date=end)
    if labeled.empty:
        return labeled
    tier3 = load_tier3_for_window(client, test_start=start, test_end=end)
    tier4 = load_tier4_for_window(client, test_start=start, test_end=end)
    merged = labeled.merge(tier3, on=list(PK_JOIN), how="left")
    merged = merge_tier4(merged, tier4)
    merged = merged.sort_values(["game_date", *PK_JOIN], ignore_index=True)
    if limit is not None:
        merged = merged.head(limit)
    log.info("assembled %d labeled %d pitches with Tier 3+4 features", len(merged), season)
    return merged


class PostHeadOnnxPredictor:
    """Served-graph predictor for the registered ``pitch_outcome_post`` bundle (BOX-ONLY).

    Loads the bundle's ``model.onnx``, ``calibrator.json``, integer mappings
    (``park_id_mapping.json`` / ``pitch_type_mapping.json``), and TE lookups
    (``pitcher_te.json`` / ``batter_te.json``). ``predict_proba``:

      1. runs each row through ``parity_fixture_post._preprocess`` - the exact
         Python mirror of Java's ``FeaturePipelinePitchPost.transform`` (the
         serving-side preprocessing, byte-for-byte);
      2. runs the served ONNX graph in batches (``_shared.onnx_probabilities``);
      3. applies the SERVED isotonic calibrator outside the graph, exactly as
         ``IsotonicCalibratorJava`` does at serve time.

    NOT imported by the unit tests: it needs a real ``onnxruntime`` session +
    a registered bundle. Python <-> Java parity of steps 1-3 is verified
    separately by the post parity fixture + ``PitchPostParityTest``.
    """

    def __init__(self, model_dir: Path, *, batch_size: int = DEFAULT_BATCH_SIZE) -> None:
        # Imported lazily so the pure scoring surface (and its tests) never pulls
        # onnxruntime / the fixture module just to score synthetic arrays.
        import onnxruntime as ort

        from bullpen_training.pitch.isotonic import IsotonicCalibrator
        from bullpen_training.pitch.parity_fixture import _load_park_mapping, _load_te_lookup
        from bullpen_training.pitch.parity_fixture_post import _load_pitch_type_mapping

        self.model_dir = Path(model_dir)
        self.batch_size = batch_size
        paths = {
            name: self.model_dir / name
            for name in (
                "model.onnx",
                "calibrator.json",
                "park_id_mapping.json",
                "pitch_type_mapping.json",
                "pitcher_te.json",
                "batter_te.json",
                "metadata.json",
            )
        }
        missing = [str(p) for p in paths.values() if not p.exists()]
        if missing:
            raise FileNotFoundError(
                f"model dir {self.model_dir} is missing registered-bundle artifacts: {missing}"
            )

        metadata = json.loads(paths["metadata.json"].read_text())
        self.model_name = str(metadata.get("model_name", "pitch_outcome_post"))
        self.model_version = str(metadata.get("model_version", "v1"))
        self.feature_pipeline_hash = metadata.get("feature_pipeline_hash")

        self._park_id_to_int, self._park_missing = _load_park_mapping(paths["park_id_mapping.json"])
        self._pitch_type_to_int, self._pitch_type_missing = _load_pitch_type_mapping(
            paths["pitch_type_mapping.json"]
        )
        self._pitcher_te, self._pitcher_prior = _load_te_lookup(paths["pitcher_te.json"])
        self._batter_te, self._batter_prior = _load_te_lookup(paths["batter_te.json"])
        self._calibrator = IsotonicCalibrator.from_json(paths["calibrator.json"])
        self._session = ort.InferenceSession(str(paths["model.onnx"]))
        self._input_name = self._session.get_inputs()[0].name

    def predict_proba(self, df: pd.DataFrame) -> np.ndarray:
        """Serving-parity calibrated probabilities, ``(N, 5)`` in LABEL_CLASSES order."""
        from bullpen_training.pitch.parity_fixture_post import _preprocess

        vectors = np.empty((len(df), len(PITCH_FEATURE_COLUMNS_POST)), dtype=np.float32)
        rows = cast(list[dict[str, Any]], df.to_dict(orient="records"))
        for i, row in enumerate(rows):
            vectors[i] = _preprocess(
                row,
                park_id_to_int=self._park_id_to_int,
                park_missing=self._park_missing,
                pitch_type_to_int=self._pitch_type_to_int,
                pitch_type_missing=self._pitch_type_missing,
                pitcher_te=self._pitcher_te,
                pitcher_prior=self._pitcher_prior,
                batter_te=self._batter_te,
                batter_prior=self._batter_prior,
            )
        raw = onnx_probabilities(
            self._session, vectors, input_name=self._input_name, batch_size=self.batch_size
        )
        return self._calibrator.transform(raw)


# --- CLI --------------------------------------------------------------------


def _refuse_non_holdout_cli(season: int) -> None:
    """CLI pre-data guard: same inverse fence as :func:`_refuse_non_holdout`, but a
    SystemExit before any bundle / ClickHouse data is touched (mirrors the sibling
    CLI's ``_refuse_holdout``)."""
    if season < HOLDOUT_YEAR:
        raise SystemExit(
            f"rule 13 inverse fence: this tool is holdout-only ({HOLDOUT_YEAR}+); refusing "
            f"season {season} - it is training/validation data and scoring it here would "
            f"double-dip. Use the rolling-origin CV harness for training-years evidence."
        )


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Offline pitch_outcome_post holdout accuracy on the 2026 season "
            "(rule-13 post-training accuracy read; holdout-only, refuses < 2026)."
        )
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        required=True,
        help="The registered pitch_outcome_post bundle (model.onnx + calibrator.json + "
        "park_id/pitch_type mappings + pitcher/batter TE + metadata.json).",
    )
    parser.add_argument(
        "--season",
        type=int,
        default=HOLDOUT_YEAR,
        help=f"Holdout season to score (>= {HOLDOUT_YEAR}; the rule-13 inverse fence "
        "refuses training/validation years loudly).",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=DEFAULT_OUT_PATH,
        help="JSON report path.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Optional cap: score only the season's first N pitches in "
        "(game_date, game_id, at_bat_index, pitch_number) order. Smoke-run knob.",
    )
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    _refuse_non_holdout_cli(args.season)

    predictor = PostHeadOnnxPredictor(args.model_dir)
    log.info(
        "loaded %s %s from %s (feature_pipeline_hash=%s)",
        predictor.model_name,
        predictor.model_version,
        args.model_dir,
        predictor.feature_pipeline_hash,
    )

    from bullpen_training.ingest.clickhouse_client import make_client

    df = load_holdout_frame(make_client(None), season=args.season, limit=args.limit)
    if df.empty:
        raise SystemExit(f"no labeled pitches loaded for {args.season}; nothing to score.")

    labels = labels_to_int(cast(pd.Series, df["label"]))
    probs = predictor.predict_proba(df)
    report = score_holdout(
        labels=labels,
        probs=probs,
        season=args.season,
        model_name=predictor.model_name,
        model_version=predictor.model_version,
        model_dir=str(args.model_dir),
    )
    save_report(report, args.out)

    h = report.headline
    log.info("== post-head %d holdout accuracy (vs realized label) ==", args.season)
    log.info("  n_pitches:      %d", report.n_pitches)
    log.info(
        "  top-1 accuracy: %.3f (marginal %.3f)", h["top1_accuracy"], h["marginal_top1_accuracy"]
    )
    log.info(
        "  top-2 accuracy: %.3f (marginal %.3f)", h["top2_accuracy"], h["marginal_top2_accuracy"]
    )
    log.info("wrote -> %s", args.out)


__all__ = (
    "ARTIFACT_NAME",
    "DATA_SOURCE",
    "DEFAULT_OUT_PATH",
    "EVAL_KIND_HOLDOUT",
    "HOLDOUT_DISCLAIMER",
    "N_CLASSES",
    "ClassAccuracy",
    "HoldoutAccuracyReport",
    "PostHeadOnnxPredictor",
    "load_holdout_frame",
    "marginal_top_k_accuracy",
    "report_from_dict",
    "report_to_dict",
    "save_report",
    "score_holdout",
    "top_k_accuracy",
)


if __name__ == "__main__":
    main()
