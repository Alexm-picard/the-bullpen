#!/usr/bin/env python
"""Wave E / E-1: backfill training-distribution baselines into a champion's metadata.json.

Writes the additive ``feature_distributions`` + ``training_prediction_distribution`` blocks the
Java ``TrainingDistributionLoader`` consumes, so the worker PSI jobs can compute drift on the live
path (the 2026-07-04 PSI run wrote zero rows precisely because these blocks were absent; see
decision [175]).

Mac-authored, BOX-RUN (ADR-0006): the champion's training snapshot + ONNX + per-park calibrator
live on the box. Rule-13 safe (reads the 2015-2025 training slice, never 2026). Rule-7 hash-safe:
these are ADDITIVE metadata keys and the feature schema hash is over ``feature_pipeline.json``
only, so the registration gate is untouched (see registry_client.feature_hasher).

CRITICAL - the ``feature_distributions`` KEYS are the serialized-REQUEST field names, NOT the model
``feature_order``. The observed side reads ``prediction_log.features`` (the request DTO), and PSI
joins observed<->reference by exact key; a mismatch fails SILENTLY (the job skips the feature). The
keys below were CONFIRMED against real prod ``prediction_log.features`` rows for both champions on
2026-07-04 - do not "fix" them to snake_case model names.

SOURCE modes (both prod-confirmed 2026-07-04): ``battedball_outcome`` derives from ClickHouse over
2015-2025 (build_year_query + rows_to_frame; request-space ``stand``/``baseState`` are reconstructed
from the model one-hots); ``pitch_outcome_post`` reads the on-box parquet and DECODES its ``_int``
categoricals back to request space. The served prediction distribution is computed in-CLI via each
family's real ONNX + calibrator (``--proba-npy`` is an escape hatch).
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

from bullpen_training.registry_client.distributions import (
    compute_feature_distributions,
    compute_prediction_distribution,
)


@dataclass(frozen=True)
class ChampionConfig:
    """Per-champion baseline spec. Request keys are prod-confirmed; source columns box-confirmed."""

    model_name: str
    class_labels: list[str]
    # request-field key (prod-confirmed) -> source column in the training frame (box-confirmed).
    continuous: dict[str, str]
    categorical: dict[str, str]
    # request keys NOT emitted (absent from prediction_log.features; would silent-skip).
    excluded: list[str] = field(default_factory=list)


# Keys confirmed against prod prediction_log.features 2026-07-04; source columns confirmed same day
# against the CH schema (battedball) + the on-box parquet (pitch). Right-hand values are the columns
# present AFTER family prep: battedball reconstructs stand_str/base_state_int from model one-hots;
# pitch decodes park_id/pitch_type/pitcher_throws/batter_stand from the parquet _int cols.
CHAMPIONS: dict[str, ChampionConfig] = {
    "battedball_outcome": ChampionConfig(
        model_name="battedball_outcome",
        class_labels=["out", "1b", "2b", "3b", "hr"],
        continuous={
            "launchSpeedMph": "launch_speed_mph",
            "launchAngleDeg": "launch_angle_deg",
            "sprayAngleDeg": "spray_angle_deg",
            "hitDistanceFt": "hit_distance_ft",
        },
        categorical={
            # reconstructed from the model one-hots by _reconstruct_battedball_categoricals.
            "stand": "stand_str",
            "baseState": "base_state_int",
            "outs": "outs",
        },
        # No parkId / releaseSpeedMph: absent from the battedball request (would silent-skip).
        excluded=["parkId", "releaseSpeedMph"],
    ),
    "pitch_outcome_post": ChampionConfig(
        model_name="pitch_outcome_post",
        class_labels=["ball", "called_strike", "swinging_strike", "foul", "in_play"],
        continuous={
            "releaseSpeedMph": "release_speed_mph",
            "plateXIn": "plate_x_in",
            "plateZIn": "plate_z_in",
            "pfxXIn": "pfx_x_in",
            "pfxZIn": "pfx_z_in",
            "spinRateRpm": "spin_rate_rpm",
            "spinAxisDeg": "spin_axis_deg",
            "releasePosXIn": "release_pos_x_in",
            "releasePosZIn": "release_pos_z_in",
        },
        categorical={
            "pitcherThrows": "pitcher_throws",
            "batterStand": "batter_stand",
            "parkId": "park_id",
            "pitchType": "pitch_type",
            "countBalls": "count_balls",
            "countStrikes": "count_strikes",
            "outs": "outs",
            "inning": "inning",
            "baseState": "base_state",
            "scoreDiff": "score_diff",
            "dow": "dow",
        },
        # pitcherId / batterId excluded (high-cardinality IDs, meaningless as drift features). The
        # 11 form/rate features are null in early live rows: their baselines could be emitted but
        # produce no PSI rows until live traffic carries values, so they are omitted here.
        excluded=["pitcherId", "batterId"],
    ),
}


def _resolve_present(
    frame: pd.DataFrame, mapping: dict[str, str]
) -> tuple[dict[str, str], list[str]]:
    """Split a key->column mapping into (present, missing) against the frame's columns.

    A missing source column is fatal for that key (its baseline would be absent), so the caller
    surfaces it loudly rather than emit a partial reference the box can't diagnose.
    """
    present = {k: c for k, c in mapping.items() if c in frame.columns}
    missing = [f"{k} <- {c}" for k, c in mapping.items() if c not in frame.columns]
    return present, missing


def build_feature_block(frame: pd.DataFrame, cfg: ChampionConfig, max_sample: int) -> dict:
    cont, miss_c = _resolve_present(frame, cfg.continuous)
    cat, miss_cat = _resolve_present(frame, cfg.categorical)
    missing = miss_c + miss_cat
    if missing:
        raise SystemExit(
            f"[{cfg.model_name}] training frame is missing source columns for: {missing}. "
            "Confirm the SOURCE-SCHEMA column names against the real parquet / ClickHouse schema."
        )
    return compute_feature_distributions(
        frame, continuous=cont, categorical=cat, max_sample=max_sample
    )


def merge_into_metadata(
    metadata_path: Path, feature_block: dict, prediction_block: dict | None
) -> dict:
    """Return the metadata dict with the two additive keys set, preserving every existing key."""
    meta = json.loads(metadata_path.read_text())
    meta["feature_distributions"] = feature_block
    if prediction_block is not None:
        meta["training_prediction_distribution"] = prediction_block
    return meta


# --- family source prep + served inference --------------------------------------------------------
# Box-validated: battedball derives from ClickHouse (docker exec); pitch reads the on-box parquet;
# both run the real ONNX + calibrator. The PURE transforms (reconstruct / decode) are unit-tested on
# the Mac; the CH query + ONNX inference are lazy-imported and exercised only on the box.


def _parse_seasons(spec: str) -> range:
    """'2015-2025' -> range(2015, 2026). Rule-13: 2026 is holdout-only, refuse it."""
    lo, hi = (int(x) for x in spec.split("-", 1))
    if lo > hi or hi >= 2026:
        raise SystemExit(f"rule-13: training seasons must be <= 2025 (got {spec})")
    return range(lo, hi + 1)


def _reconstruct_battedball_categoricals(df: pd.DataFrame) -> pd.DataFrame:
    """Add request-space `stand_str` + `base_state_int` from the model one-hots (pure, testable).

    rows_to_frame emits the model frame (stand_R/stand_L, base_state_0..7 one-hots); the observed
    side logs the raw request `stand` ("L"/"R") and `baseState` (int), so recover those to join.
    """
    out = df.copy()
    out["stand_str"] = np.where(df["stand_L"] == 1, "L", "R")
    base_cols = [f"base_state_{i}" for i in range(8)]
    out["base_state_int"] = df[base_cols].to_numpy().argmax(axis=1)
    return out


def _decode_pitch_categoricals(
    df: pd.DataFrame, park_by_int: dict[int, str], ptype_by_int: dict[int, str]
) -> pd.DataFrame:
    """Decode the parquet's _int categoricals back to request space (pure, testable).

    throws/stand use {0:"L", 1:"R"} (train_pre.STAND_TO_INT/THROWS_TO_INT; R is the null fallback so
    the int is always 0 or 1). park/pitch_type invert the bundle's name->int mapping.
    """
    throws_stand = {0: "L", 1: "R"}  # 0=L, 1=R (R is the null fallback); never assumed backwards.
    out = df.copy()
    out["park_id"] = df["park_id_int"].map(park_by_int.get)
    out["pitch_type"] = df["pitch_type_int"].map(ptype_by_int.get)
    out["pitcher_throws"] = df["pitcher_throws_int"].map(throws_stand.get)
    out["batter_stand"] = df["batter_stand_int"].map(throws_stand.get)
    return out


def _load_battedball_frame(seasons: range, container: str) -> pd.DataFrame:
    """CH-derive the training population (build_year_query + rows_to_frame) + request-space cats.

    Box-only: shells out to `docker exec <container> clickhouse-client`, reusing the exact training
    WHERE + the frame OnnxMlpPredictor consumes (eval.promotion.export_batted_ball_full).
    """
    import subprocess

    from bullpen_training.eval.promotion.export_batted_ball_full import (
        build_year_query,
        rows_to_frame,
    )

    parts = []
    for year in seasons:
        tsv = subprocess.run(
            ["docker", "exec", container, "clickhouse-client", "--query", build_year_query(year)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        parts.append(rows_to_frame(tsv))
    return _reconstruct_battedball_categoricals(pd.concat(parts, ignore_index=True))


def _load_pitch_frame(parquet: Path, model_dir: Path) -> pd.DataFrame:
    """Read the pitch-post parquet (already the 41-vector) and decode its _int categoricals.

    The operator MUST pass the champion's REGISTERED training snapshot (``training_data.parquet`` in
    the bundle dir), not an ad-hoc parquet: the parquet carries no season/date column, so rule-13 is
    enforced upstream at snapshot registration, not here.
    """
    park = json.loads((model_dir / "park_id_mapping.json").read_text())["park_id"]
    ptype = json.loads((model_dir / "pitch_type_mapping.json").read_text())["pitch_type"]
    park_by_int = {int(v): k for k, v in park.items()}
    ptype_by_int = {int(v): k for k, v in ptype.items()}
    return _decode_pitch_categoricals(pd.read_parquet(parquet), park_by_int, ptype_by_int)


def _served_proba(model: str, model_dir: Path, frame: pd.DataFrame) -> np.ndarray:
    """Run the champion's served chain on the frame -> (n_rows, n_classes) calibrated probs."""
    if model == "battedball_outcome":
        from bullpen_training.battedball.eval.backfill_accuracy import OnnxMlpPredictor

        return OnnxMlpPredictor(model_dir).predict_proba(frame)

    import onnxruntime as ort

    from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST
    from bullpen_training.pitch.eval._shared import onnx_probabilities
    from bullpen_training.pitch.isotonic import IsotonicCalibrator

    session = ort.InferenceSession(str(model_dir / "model.onnx"))
    mat = frame[list(PITCH_FEATURE_COLUMNS_POST)].to_numpy(np.float32)
    raw = onnx_probabilities(session, mat, input_name=session.get_inputs()[0].name)
    return IsotonicCalibrator.from_json(model_dir / "calibrator.json").transform(raw)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", required=True, choices=sorted(CHAMPIONS))
    ap.add_argument(
        "--model-dir",
        required=True,
        type=Path,
        help="champion bundle dir (model.onnx + calibrator.json + mappings + metadata.json)",
    )
    ap.add_argument(
        "--training-parquet",
        type=Path,
        default=None,
        help="pitch_outcome_post: the on-box training_data.parquet (battedball uses ClickHouse)",
    )
    ap.add_argument(
        "--seasons", default="2015-2025", help="battedball CH training seasons, inclusive (<= 2025)"
    )
    ap.add_argument(
        "--container", default="bullpen-clickhouse", help="battedball: the ClickHouse container"
    )
    ap.add_argument(
        "--metadata",
        type=Path,
        default=None,
        help="metadata.json to update (default <model-dir>/metadata.json)",
    )
    ap.add_argument(
        "--proba-npy",
        type=Path,
        default=None,
        help="escape hatch: precomputed (n, classes) calibrated probs; overrides in-CLI inference",
    )
    ap.add_argument("--max-sample", type=int, default=5000)
    ap.add_argument(
        "--dry-run", action="store_true", help="print a summary; do not write metadata.json"
    )
    args = ap.parse_args(argv)

    cfg = CHAMPIONS[args.model]
    metadata_path = args.metadata or (args.model_dir / "metadata.json")

    # 1. request-space frame (family source).
    if args.model == "battedball_outcome":
        frame = _load_battedball_frame(_parse_seasons(args.seasons), args.container)
    else:
        if args.training_parquet is None:
            ap.error("pitch_outcome_post requires --training-parquet")
        frame = _load_pitch_frame(args.training_parquet, args.model_dir)

    # 2. feature_distributions (request keys) + 3. training_prediction_distribution (served probs).
    feature_block = build_feature_block(frame, cfg, args.max_sample)
    # An all-null source column yields an empty continuous sample - the Java PSI quantile-edge step
    # cannot bin an empty reference, so warn loudly (the operator should confirm the column is
    # populated in this snapshot) rather than emit a degenerate block that silently skips.
    empty = [
        k for k, v in feature_block.items() if v.get("kind") == "continuous" and not v.get("sample")
    ]
    if empty:
        print(f"  WARNING: empty continuous sample (all-null source) for: {empty}")
    proba = (
        np.load(args.proba_npy)
        if args.proba_npy is not None
        else _served_proba(args.model, args.model_dir, frame)
    )
    prediction_block = compute_prediction_distribution(proba, cfg.class_labels, args.max_sample)

    print(
        f"[{cfg.model_name}] {len(frame)} rows -> feature_distributions {len(feature_block)} keys "
        f"({sorted(feature_block)}); prediction_distribution {len(prediction_block)} classes"
    )
    if args.dry_run:
        print("  --dry-run: metadata.json not written")
        return 0

    meta = merge_into_metadata(metadata_path, feature_block, prediction_block)
    metadata_path.write_text(json.dumps(meta, indent=2) + "\n")
    print(f"  wrote both blocks into {metadata_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
