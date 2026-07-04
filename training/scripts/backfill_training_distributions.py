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

Two things below are still BOX-CONFIRMED, not guessed (see the SOURCE-SCHEMA notes): the SOURCE
column names in the training frame and the categorical VALUE space (is ``stand`` "R"/"L" or 0/1?).
They are config so the box can correct them against the real parquet / ClickHouse schema before the
run, exactly as the request keys were confirmed.
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


# Confirmed against prod prediction_log.features on 2026-07-04 (real logged rows, both champions).
# SOURCE-SCHEMA (box-confirm before run): the values on the right are training-frame column names.
# For battedball the cleanest source is a ClickHouse pull over 2015-2025 (raw request fields live
# there in request-value space); for pitch_outcome_post it is the on-box training_data.parquet.
# Confirm column names + that categoricals are in REQUEST-value space (stand "R"/"L", not 0/1).
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
            "stand": "stand",
            "baseState": "base_state",
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


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", required=True, choices=sorted(CHAMPIONS))
    ap.add_argument(
        "--training-parquet", required=True, type=Path, help="the champion's training slice"
    )
    ap.add_argument(
        "--metadata", required=True, type=Path, help="the champion's metadata.json to update"
    )
    ap.add_argument(
        "--proba-npy",
        type=Path,
        default=None,
        help="optional (n_rows, n_classes) .npy of the champion's CALIBRATED served probabilities "
        "over the training frame (produced on the box via the family serving path); enables the "
        "training_prediction_distribution block. Omit to write feature_distributions only.",
    )
    ap.add_argument("--max-sample", type=int, default=5000)
    ap.add_argument(
        "--dry-run", action="store_true", help="print a summary; do not write metadata.json"
    )
    args = ap.parse_args(argv)

    cfg = CHAMPIONS[args.model]
    frame = pd.read_parquet(args.training_parquet)

    feature_block = build_feature_block(frame, cfg, args.max_sample)

    prediction_block: dict | None = None
    if args.proba_npy is not None:
        proba = np.load(args.proba_npy)
        prediction_block = compute_prediction_distribution(proba, cfg.class_labels, args.max_sample)

    n_feat = len(feature_block)
    n_pred = len(prediction_block) if prediction_block else 0
    print(
        f"[{cfg.model_name}] feature_distributions: {n_feat} keys "
        f"({sorted(feature_block)}); training_prediction_distribution: {n_pred} classes"
    )
    if prediction_block is None:
        print(
            "  note: no --proba-npy -> feature_distributions only; PSI_PREDICTION needs the "
            "prediction block (run the champion serving path on the box to produce the .npy)."
        )

    if args.dry_run:
        print("  --dry-run: metadata.json not written")
        return 0

    meta = merge_into_metadata(args.metadata, feature_block, prediction_block)
    args.metadata.write_text(json.dumps(meta, indent=2) + "\n")
    print(f"  wrote {n_feat} feature + {n_pred} prediction blocks into {args.metadata}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
