"""Generate the Python↔Java parity fixture for pitch_outcome_post (2b.3).

Sister script to ``parity_fixture.py`` (pre head). Reads N random 2025
``features`` rows including all Tier 4 columns, runs each through the
Python ONNX inference + isotonic calibrator, and emits two JSON files:

    parity_pitch_post_001.json           — raw input rows
    parity_pitch_post_001_expected.json  — feature_vector + raw probs +
                                           calibrated probs

The Java parity test loads both files, runs Java's ONNX Runtime +
``FeaturePipelinePitchPost`` + ``IsotonicCalibratorJava``, and asserts
|prob - expected| < 1e-6 at every stage.

Re-run when the production post-head model is retrained.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, cast

import click
import numpy as np
import onnxruntime as ort
import pandas as pd

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.export_post_onnx import load_canonical_pipeline
from bullpen_training.pitch.isotonic import IsotonicCalibrator
from bullpen_training.pitch.parity_fixture import (
    _load_park_mapping,
    _load_te_lookup,
)
from bullpen_training.pitch.train_pre import (
    STAND_TO_INT,
    THROWS_TO_INT,
)

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
FIXTURES_DIR = REPO_ROOT / "training" / "tests" / "fixtures"
INPUT_FILE = FIXTURES_DIR / "parity_pitch_post_001.json"
EXPECTED_FILE = FIXTURES_DIR / "parity_pitch_post_001_expected.json"

DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "training" / "artifacts"
DEFAULT_MODEL_DIR = DEFAULT_ARTIFACTS_DIR / "pitch_outcome_post" / "v1"

# Columns to pull from `features`. Same as the pre fixture plus all 10 Tier 4 columns.
_REQUEST_COLS: tuple[str, ...] = (
    "game_id",
    "at_bat_index",
    "pitch_number",
    "game_date",
    "pitcher_id",
    "batter_id",
    "pitcher_throws",
    "batter_stand",
    "park_id",
    "count_balls",
    "count_strikes",
    "outs",
    "inning",
    "base_state",
    "score_diff",
    "dow",
    "pitcher_pitches_last_28d",
    "pitcher_pitches_in_game",
    "days_since_last_appearance",
    "pitcher_strike_rate_28d",
    "pitcher_swstrike_rate_28d",
    "pitcher_inplay_rate_28d",
    "pitcher_strike_rate_std",
    "batter_strike_rate_28d",
    "batter_inplay_rate_28d",
    "batter_ball_rate_28d",
    "batter_inplay_rate_std",
    "pitch_type",
    "release_speed_mph",
    "plate_x_in",
    "plate_z_in",
    "pfx_x_in",
    "pfx_z_in",
    "spin_rate_rpm",
    "spin_axis_deg",
    "release_pos_x_in",
    "release_pos_z_in",
)


# Deterministic synthetic request rows for the CI parity path (no ClickHouse). Same shape as the
# pre fixture plus the 10 Tier 4 columns. Mixes in-universe / out-of-universe ids and pitch types,
# and includes explicit nulls (null pitch_type -> missing_value; null Tier 4 -> NaN) to exercise
# both branches. Keep ids in sync with `generate_ci_artifacts.PITCHER_IDS / BATTER_IDS / PARK_CODES
# / PITCH_TYPES`. Tier 4 continuous values are deliberately Float32-exact (integers / n over a power
# of two) so the float64-serialized expected vector equals both Python's float64 preprocess AND
# Java's `(float) value` within the 1e-6 tolerance - the same property real Nullable(Float32) CH
# data has for free.
SYNTHETIC_INPUT_ROWS: list[dict[str, Any]] = [
    {
        "game_id": 710001,
        "at_bat_index": 1,
        "pitch_number": 1,
        "game_date": "2025-04-02",
        "pitcher_id": 100003,
        "batter_id": 200005,
        "pitcher_throws": "R",
        "batter_stand": "R",
        "park_id": "NYY",
        "count_balls": 1,
        "count_strikes": 1,
        "outs": 0,
        "inning": 3,
        "base_state": 0,
        "score_diff": 0,
        "dow": 2,
        "pitcher_pitches_last_28d": 310.0,
        "pitcher_pitches_in_game": 12.0,
        "days_since_last_appearance": 5.0,
        "pitcher_strike_rate_28d": 0.64,
        "pitcher_swstrike_rate_28d": 0.11,
        "pitcher_inplay_rate_28d": 0.18,
        "pitcher_strike_rate_std": 0.04,
        "batter_strike_rate_28d": 0.62,
        "batter_inplay_rate_28d": 0.19,
        "batter_ball_rate_28d": 0.36,
        "batter_inplay_rate_std": 0.05,
        "pitch_type": "FF",
        "release_speed_mph": 95.5,
        "plate_x_in": -3.25,
        "plate_z_in": 24.0,
        "pfx_x_in": -6.5,
        "pfx_z_in": 14.25,
        "spin_rate_rpm": 2280.0,
        "spin_axis_deg": 205.0,
        "release_pos_x_in": -18.0,
        "release_pos_z_in": 62.0,
    },
    {
        "game_id": 710002,
        "at_bat_index": 4,
        "pitch_number": 3,
        "game_date": "2025-05-13",
        "pitcher_id": 999999,
        "batter_id": 888888,
        "pitcher_throws": "L",
        "batter_stand": "L",
        "park_id": "ZZZ",
        "count_balls": 2,
        "count_strikes": 2,
        "outs": 1,
        "inning": 5,
        "base_state": 3,
        "score_diff": -2,
        "dow": 5,
        "pitcher_pitches_last_28d": None,
        "pitcher_pitches_in_game": 45.0,
        "days_since_last_appearance": None,
        "pitcher_strike_rate_28d": None,
        "pitcher_swstrike_rate_28d": 0.09,
        "pitcher_inplay_rate_28d": 0.21,
        "pitcher_strike_rate_std": None,
        "batter_strike_rate_28d": 0.58,
        "batter_inplay_rate_28d": None,
        "batter_ball_rate_28d": 0.40,
        "batter_inplay_rate_std": None,
        "pitch_type": "SL",
        "release_speed_mph": 84.0,
        "plate_x_in": 5.0,
        "plate_z_in": 18.0,
        "pfx_x_in": 2.25,
        "pfx_z_in": 1.25,
        "spin_rate_rpm": 2450.0,
        "spin_axis_deg": 95.0,
        "release_pos_x_in": 21.0,
        "release_pos_z_in": 58.0,
    },
    {
        "game_id": 710003,
        "at_bat_index": 7,
        "pitch_number": 6,
        "game_date": "2025-06-21",
        "pitcher_id": 100011,
        "batter_id": 200018,
        "pitcher_throws": "L",
        "batter_stand": "R",
        "park_id": "LAD",
        "count_balls": 3,
        "count_strikes": 2,
        "outs": 2,
        "inning": 7,
        "base_state": 7,
        "score_diff": 3,
        "dow": 0,
        "pitcher_pitches_last_28d": 190.0,
        "pitcher_pitches_in_game": 88.0,
        "days_since_last_appearance": 1.0,
        "pitcher_strike_rate_28d": 0.60,
        "pitcher_swstrike_rate_28d": 0.13,
        "pitcher_inplay_rate_28d": 0.17,
        "pitcher_strike_rate_std": 0.06,
        "batter_strike_rate_28d": 0.66,
        "batter_inplay_rate_28d": 0.15,
        "batter_ball_rate_28d": 0.33,
        "batter_inplay_rate_std": 0.04,
        "pitch_type": "XX",
        "release_speed_mph": 88.0,
        "plate_x_in": 0.5,
        "plate_z_in": 30.0,
        "pfx_x_in": -1.0,
        "pfx_z_in": 8.0,
        "spin_rate_rpm": 2100.0,
        "spin_axis_deg": 150.0,
        "release_pos_x_in": 15.0,
        "release_pos_z_in": 60.0,
    },
    {
        "game_id": 710004,
        "at_bat_index": 2,
        "pitch_number": 2,
        "game_date": "2025-07-05",
        "pitcher_id": 100007,
        "batter_id": 999000,
        "pitcher_throws": "R",
        "batter_stand": "L",
        "park_id": "BOS",
        "count_balls": 0,
        "count_strikes": 0,
        "outs": 0,
        "inning": 2,
        "base_state": 1,
        "score_diff": 1,
        "dow": 4,
        "pitcher_pitches_last_28d": None,
        "pitcher_pitches_in_game": None,
        "days_since_last_appearance": None,
        "pitcher_strike_rate_28d": None,
        "pitcher_swstrike_rate_28d": None,
        "pitcher_inplay_rate_28d": None,
        "pitcher_strike_rate_std": None,
        "batter_strike_rate_28d": None,
        "batter_inplay_rate_28d": None,
        "batter_ball_rate_28d": None,
        "batter_inplay_rate_std": None,
        "pitch_type": None,
        "release_speed_mph": None,
        "plate_x_in": None,
        "plate_z_in": None,
        "pfx_x_in": None,
        "pfx_z_in": None,
        "spin_rate_rpm": None,
        "spin_axis_deg": None,
        "release_pos_x_in": None,
        "release_pos_z_in": None,
    },
    {
        "game_id": 710005,
        "at_bat_index": 9,
        "pitch_number": 4,
        "game_date": "2025-08-16",
        "pitcher_id": 100015,
        "batter_id": 200002,
        "pitcher_throws": "R",
        "batter_stand": "R",
        "park_id": "CHC",
        "count_balls": 0,
        "count_strikes": 2,
        "outs": 1,
        "inning": 9,
        "base_state": 5,
        "score_diff": -1,
        "dow": 6,
        "pitcher_pitches_last_28d": 275.0,
        "pitcher_pitches_in_game": 20.0,
        "days_since_last_appearance": 3.0,
        "pitcher_strike_rate_28d": 0.63,
        "pitcher_swstrike_rate_28d": 0.10,
        "pitcher_inplay_rate_28d": 0.20,
        "pitcher_strike_rate_std": 0.05,
        "batter_strike_rate_28d": 0.61,
        "batter_inplay_rate_28d": 0.18,
        "batter_ball_rate_28d": 0.37,
        "batter_inplay_rate_std": 0.05,
        "pitch_type": "CH",
        "release_speed_mph": 86.5,
        "plate_x_in": -1.25,
        "plate_z_in": 20.5,
        "pfx_x_in": -9.0,
        "pfx_z_in": 6.0,
        "spin_rate_rpm": 1700.0,
        "spin_axis_deg": 230.0,
        "release_pos_x_in": -20.0,
        "release_pos_z_in": 61.0,
    },
]


def _load_rows(client: Any, year: int, fold_id: int, n: int) -> pd.DataFrame:
    """Pull a deterministic slice of `features` rows for the test fold.

    Filters to rows with non-null Tier 4 — the parity test exercises the
    populated path (validating that NaN-handling Java doesn't drift from
    populated values). Pre-2024 sparse-Tier-4 behaviour is covered
    implicitly by the synthetic unit tests for train_post.
    """
    cols = ", ".join(_REQUEST_COLS)
    query = f"""
        SELECT {cols}
        FROM features FINAL
        WHERE fold = {fold_id}
          AND toYear(game_date) = {year}
          AND pfx_x_in IS NOT NULL
          AND spin_rate_rpm IS NOT NULL
        ORDER BY game_date, game_id, at_bat_index, pitch_number
        LIMIT {n}
    """
    rows = cast(list[tuple[Any, ...]], client.execute(query))
    return pd.DataFrame(rows, columns=list(_REQUEST_COLS))


def _load_pitch_type_mapping(path: Path) -> tuple[dict[str, int], int]:
    raw = json.loads(path.read_text())
    return {str(k): int(v) for k, v in raw["pitch_type"].items()}, int(raw["missing_value"])


def _preprocess(
    row: dict[str, Any],
    *,
    park_id_to_int: dict[str, int],
    park_missing: int,
    pitch_type_to_int: dict[str, int],
    pitch_type_missing: int,
    pitcher_te: dict[int, dict[str, float]],
    pitcher_prior: dict[str, float],
    batter_te: dict[int, dict[str, float]],
    batter_prior: dict[str, float],
) -> list[float]:
    """Apply the documented preprocess steps in feature_pipeline_post.json order.

    Returns the float32 input vector for ONNX. Must match the Java
    FeaturePipelinePitchPost.transform implementation byte-for-byte.
    """
    throws = row.get("pitcher_throws") or "R"
    stand = row.get("batter_stand") or "R"
    park = str(row.get("park_id") or "")
    pitch_type = str(row.get("pitch_type") or "")
    pitcher_id = int(row["pitcher_id"])
    batter_id = int(row["batter_id"])

    pitcher_te_row = pitcher_te.get(pitcher_id, pitcher_prior)
    batter_te_row = batter_te.get(batter_id, batter_prior)

    def _passthrough(name: str) -> float:
        val = row.get(name)
        return float("nan") if val is None else float(val)

    return [
        _passthrough("count_balls"),
        _passthrough("count_strikes"),
        _passthrough("outs"),
        _passthrough("inning"),
        _passthrough("base_state"),
        _passthrough("score_diff"),
        _passthrough("dow"),
        float(THROWS_TO_INT.get(throws, 1)),
        float(STAND_TO_INT.get(stand, 1)),
        float(park_id_to_int.get(park, park_missing)),
        float(pitcher_te_row["ball"]),
        float(pitcher_te_row["called_strike"]),
        float(pitcher_te_row["swinging_strike"]),
        float(pitcher_te_row["foul"]),
        float(pitcher_te_row["in_play"]),
        float(batter_te_row["ball"]),
        float(batter_te_row["called_strike"]),
        float(batter_te_row["swinging_strike"]),
        float(batter_te_row["foul"]),
        float(batter_te_row["in_play"]),
        _passthrough("pitcher_pitches_last_28d"),
        _passthrough("pitcher_pitches_in_game"),
        _passthrough("days_since_last_appearance"),
        _passthrough("pitcher_strike_rate_28d"),
        _passthrough("pitcher_swstrike_rate_28d"),
        _passthrough("pitcher_inplay_rate_28d"),
        _passthrough("pitcher_strike_rate_std"),
        _passthrough("batter_strike_rate_28d"),
        _passthrough("batter_inplay_rate_28d"),
        _passthrough("batter_ball_rate_28d"),
        _passthrough("batter_inplay_rate_std"),
        # Tier 4
        float(pitch_type_to_int.get(pitch_type, pitch_type_missing)),
        _passthrough("release_speed_mph"),
        _passthrough("plate_x_in"),
        _passthrough("plate_z_in"),
        _passthrough("pfx_x_in"),
        _passthrough("pfx_z_in"),
        _passthrough("spin_rate_rpm"),
        _passthrough("spin_axis_deg"),
        _passthrough("release_pos_x_in"),
        _passthrough("release_pos_z_in"),
    ]


def _onnx_distribution(session: ort.InferenceSession, vector: list[float]) -> list[float]:
    arr = np.array([vector], dtype=np.float32)
    raw = session.run(None, {"input": arr})
    probs = cast(np.ndarray, raw[1] if len(raw) > 1 else raw[0])
    return [float(p) for p in probs[0]]


def generate(
    *,
    out_dir: Path | None = None,
    model_dir: Path | None = None,
    year: int = 2025,
    fold_id: int = 4,
    n: int = 10,
    settings: ClickHouseSettings | None = None,
    synthetic: bool = False,
) -> dict[str, Any]:
    mdir = model_dir or DEFAULT_MODEL_DIR
    onnx_path = mdir / "model.onnx"
    cal_path = mdir / "calibrator.json"
    park_path = mdir / "park_id_mapping.json"
    pitch_type_path = mdir / "pitch_type_mapping.json"
    pitcher_te_path = mdir / "pitcher_te.json"
    batter_te_path = mdir / "batter_te.json"

    for p in (onnx_path, cal_path, park_path, pitch_type_path, pitcher_te_path, batter_te_path):
        if not p.exists():
            raise FileNotFoundError(
                f"missing artifact: {p}; run "
                "`bullpen_training.pitch.production --model post --version v1` then "
                "`bullpen_training.pitch.export_post_onnx` first"
            )

    spec = load_canonical_pipeline()
    park_id_to_int, park_missing = _load_park_mapping(park_path)
    pitch_type_to_int, pitch_type_missing = _load_pitch_type_mapping(pitch_type_path)
    pitcher_te, pitcher_prior = _load_te_lookup(pitcher_te_path)
    batter_te, batter_prior = _load_te_lookup(batter_te_path)

    calibrator = IsotonicCalibrator.from_json(cal_path)
    session = ort.InferenceSession(str(onnx_path))

    if synthetic:
        # CI path: fixed synthetic request rows, no ClickHouse. Already native Python types.
        raw_rows: list[dict[str, Any]] = [dict(r) for r in SYNTHETIC_INPUT_ROWS]
    else:
        df = _load_rows(make_client(settings), year, fold_id, n)
        if len(df) < n:
            log.warning("post parity fixture short", requested=n, got=len(df))
        raw_rows = [
            {str(k): (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in raw_row.items()}
            for _, raw_row in df.iterrows()
        ]

    inputs: list[dict[str, Any]] = []
    expected: list[dict[str, Any]] = []
    for row in raw_rows:
        for id_col in ("pitcher_id", "batter_id", "game_id", "at_bat_index", "pitch_number"):
            if row.get(id_col) is not None:
                row[id_col] = int(row[id_col])
        # NaN → None on the wire (strict JSON; Jackson rejects "NaN")
        for k, v in list(row.items()):
            if isinstance(v, float) and np.isnan(v):
                row[k] = None
        feature_vector = _preprocess(
            row,
            park_id_to_int=park_id_to_int,
            park_missing=park_missing,
            pitch_type_to_int=pitch_type_to_int,
            pitch_type_missing=pitch_type_missing,
            pitcher_te=pitcher_te,
            pitcher_prior=pitcher_prior,
            batter_te=batter_te,
            batter_prior=batter_prior,
        )
        raw_probs = _onnx_distribution(session, feature_vector)
        calibrated = calibrator.transform(np.array([raw_probs], dtype=np.float64))[0]
        inputs.append(row)
        # Emitted as-is (float64); the Java mirror casts to float32 on read, so the fixture only
        # holds within 1e-6 when input values are Float32-representable. Box CH data is
        # Nullable(Float32); the synthetic CI rows use Float32-exact Tier 4 values for the same
        # reason (see the SYNTHETIC_INPUT_ROWS comment).
        feature_vector_serialised: list[float | None] = [
            None if np.isnan(v) else v for v in feature_vector
        ]
        expected.append(
            {
                "game_id": row["game_id"],
                "at_bat_index": row["at_bat_index"],
                "pitch_number": row["pitch_number"],
                "feature_vector": feature_vector_serialised,
                "raw_probabilities": raw_probs,
                "calibrated_probabilities": [float(p) for p in calibrated],
            }
        )

    out_root = out_dir or FIXTURES_DIR
    out_root.mkdir(parents=True, exist_ok=True)
    input_doc = {
        "model_name": "pitch_outcome_post",
        "version": "v1",
        "feature_order": list(PITCH_FEATURE_COLUMNS_POST),
        "class_labels": list(LABEL_CLASSES),
        "schema_hash": spec["schema_hash"],
        "rows": inputs,
    }
    expected_doc = {
        "model_name": "pitch_outcome_post",
        "version": "v1",
        "class_labels": list(LABEL_CLASSES),
        "schema_hash": spec["schema_hash"],
        "tolerance": 1e-6,
        "rows": expected,
    }
    INPUT_FILE_OUT = out_root / "parity_pitch_post_001.json"
    EXPECTED_FILE_OUT = out_root / "parity_pitch_post_001_expected.json"
    INPUT_FILE_OUT.write_text(json.dumps(input_doc, indent=2) + "\n")
    EXPECTED_FILE_OUT.write_text(json.dumps(expected_doc, indent=2) + "\n")

    log.info(
        "post pitch parity fixture generated",
        n=len(inputs),
        input_path=str(INPUT_FILE_OUT),
        expected_path=str(EXPECTED_FILE_OUT),
        schema_hash=spec["schema_hash"],
    )
    return {
        "n": len(inputs),
        "schema_hash": spec["schema_hash"],
        "input_sha256": hashlib.sha256(INPUT_FILE_OUT.read_bytes()).hexdigest(),
    }


@click.command()
@click.option("--year", type=int, default=2025, show_default=True)
@click.option("--fold-id", type=int, default=4, show_default=True)
@click.option("--n", type=int, default=10, show_default=True)
@click.option(
    "--model-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option(
    "--synthetic",
    is_flag=True,
    default=False,
    help="Use the fixed synthetic request rows (CI path, no ClickHouse) instead of a fold pull.",
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(
    year: int,
    fold_id: int,
    n: int,
    model_dir: Path | None,
    synthetic: bool,
    log_format: str,
) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    generate(year=year, fold_id=fold_id, n=n, model_dir=model_dir, synthetic=synthetic)


if __name__ == "__main__":
    main()
