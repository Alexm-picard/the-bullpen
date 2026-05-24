"""Generate the Python↔Java parity fixture for pitch_outcome_pre (2a.8).

Reads N random 2025-fold pitches from ClickHouse, runs each one through
the Python ONNX inference + isotonic calibrator, and emits two JSON
files:

    parity_pitch_pre_001.json           — raw input rows (a request body shape)
    parity_pitch_pre_001_expected.json  — preprocessed feature vector +
                                          raw-onnx prob distribution +
                                          calibrated prob distribution

The Java parity test loads the same input file, runs Java's ONNX Runtime
+ FeaturePipelinePitchPre + IsotonicCalibratorJava, and asserts
|prob - expected| < 1e-6 on the calibrated probs.

Re-run when the production model is retrained.
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
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.export_pre_onnx import load_canonical_pipeline
from bullpen_training.pitch.isotonic import IsotonicCalibrator
from bullpen_training.pitch.train_pre import (
    STAND_TO_INT,
    THROWS_TO_INT,
)

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
FIXTURES_DIR = REPO_ROOT / "training" / "tests" / "fixtures"
INPUT_FILE = FIXTURES_DIR / "parity_pitch_pre_001.json"
EXPECTED_FILE = FIXTURES_DIR / "parity_pitch_pre_001_expected.json"

DEFAULT_ARTIFACTS_DIR = REPO_ROOT / "training" / "artifacts"
DEFAULT_MODEL_DIR = DEFAULT_ARTIFACTS_DIR / "pitch_outcome_pre" / "v1"

# Column subset we pull from the features table to reconstruct a request
# body + apply Java-style preprocessing locally. The string ids
# (pitcher_id, batter_id, park_id) feed Tier 2 / park lookups; the
# pre-computed Tier 3 columns ride along verbatim (LightGBM-native NaN).
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
)


def _load_rows(client: Any, year: int, fold_id: int, n: int) -> pd.DataFrame:
    """Pull a deterministic slice of `features` rows for the test fold."""
    cols = ", ".join(_REQUEST_COLS)
    query = f"""
        SELECT {cols}
        FROM features FINAL
        WHERE fold = {fold_id} AND toYear(game_date) = {year}
        ORDER BY game_date, game_id, at_bat_index, pitch_number
        LIMIT {n}
    """
    rows = cast(list[tuple[Any, ...]], client.execute(query))
    return pd.DataFrame(rows, columns=list(_REQUEST_COLS))


def _load_te_lookup(path: Path) -> tuple[dict[int, dict[str, float]], dict[str, float]]:
    """Read pitcher_te.json / batter_te.json into id → {class: te_val}."""
    raw = json.loads(path.read_text())
    table: dict[int, dict[str, float]] = {}
    for row in raw["rows"]:
        entity_id = int(row[raw["entity_col"]])
        table[entity_id] = {cls: float(row[f"te_{cls}"]) for cls in LABEL_CLASSES}
    prior: dict[str, float] = {cls: float(raw["prior"][cls]) for cls in LABEL_CLASSES}
    return table, prior


def _load_park_mapping(path: Path) -> tuple[dict[str, int], int]:
    raw = json.loads(path.read_text())
    return {str(k): int(v) for k, v in raw["park_id"].items()}, int(raw["missing_value"])


def _preprocess(
    row: dict[str, Any],
    *,
    park_id_to_int: dict[str, int],
    park_missing: int,
    pitcher_te: dict[int, dict[str, float]],
    pitcher_prior: dict[str, float],
    batter_te: dict[int, dict[str, float]],
    batter_prior: dict[str, float],
) -> list[float]:
    """Apply the documented preprocess steps in `feature_pipeline.json` order.

    Returns the float32 input vector for ONNX. Must match the Java
    FeaturePipelinePitchPre.transform implementation byte-for-byte.
    """
    throws = row.get("pitcher_throws") or "R"
    stand = row.get("batter_stand") or "R"
    park = str(row.get("park_id") or "")
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
    ]


def _onnx_distribution(session: ort.InferenceSession, vector: list[float]) -> list[float]:
    arr = np.array([vector], dtype=np.float32)
    raw = session.run(None, {"input": arr})
    # convert_lightgbm with zipmap=False → outputs[0]=label, outputs[1]=probs (N, K)
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
) -> dict[str, Any]:
    mdir = model_dir or DEFAULT_MODEL_DIR
    onnx_path = mdir / "model.onnx"
    cal_path = mdir / "calibrator.json"
    park_path = mdir / "park_id_mapping.json"
    pitcher_te_path = mdir / "pitcher_te.json"
    batter_te_path = mdir / "batter_te.json"

    for p in (onnx_path, cal_path, park_path, pitcher_te_path, batter_te_path):
        if not p.exists():
            raise FileNotFoundError(
                f"missing artifact: {p}; run "
                "`bullpen_training.pitch.production --model lightgbm` then "
                "`bullpen_training.pitch.export_pre_onnx` first"
            )

    spec = load_canonical_pipeline()
    park_id_to_int, park_missing = _load_park_mapping(park_path)
    pitcher_te, pitcher_prior = _load_te_lookup(pitcher_te_path)
    batter_te, batter_prior = _load_te_lookup(batter_te_path)

    calibrator = IsotonicCalibrator.from_json(cal_path)
    session = ort.InferenceSession(str(onnx_path))

    df = _load_rows(make_client(settings), year, fold_id, n)
    inputs: list[dict[str, Any]] = []
    expected: list[dict[str, Any]] = []
    for _, raw_row in df.iterrows():
        row: dict[str, Any] = {
            str(k): (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in raw_row.items()
        }
        # Cast id columns to int so JSON is human-readable + Java-friendly
        for id_col in ("pitcher_id", "batter_id", "game_id", "at_bat_index", "pitch_number"):
            if row.get(id_col) is not None:
                row[id_col] = int(row[id_col])
        # Replace NaN with None so json.dumps emits "null" (strict JSON) — Jackson
        # rejects literal "NaN" by default and we'd rather feed the parity test
        # well-formed JSON than enable lenient parsing.
        for k, v in list(row.items()):
            if isinstance(v, float) and np.isnan(v):
                row[k] = None
        feature_vector = _preprocess(
            row,
            park_id_to_int=park_id_to_int,
            park_missing=park_missing,
            pitcher_te=pitcher_te,
            pitcher_prior=pitcher_prior,
            batter_te=batter_te,
            batter_prior=batter_prior,
        )
        raw_probs = _onnx_distribution(session, feature_vector)
        calibrated = calibrator.transform(np.array([raw_probs], dtype=np.float64))[0]
        inputs.append(row)
        # NaN → None on the wire so the JSON is strict (Jackson rejects "NaN")
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
        "model_name": "pitch_outcome_pre",
        "version": "v1",
        "feature_order": list(PITCH_FEATURE_COLUMNS),
        "class_labels": list(LABEL_CLASSES),
        "schema_hash": spec["schema_hash"],
        "rows": inputs,
    }
    expected_doc = {
        "model_name": "pitch_outcome_pre",
        "version": "v1",
        "class_labels": list(LABEL_CLASSES),
        "schema_hash": spec["schema_hash"],
        "tolerance": 1e-6,
        "rows": expected,
    }
    INPUT_FILE_OUT = out_root / "parity_pitch_pre_001.json"
    EXPECTED_FILE_OUT = out_root / "parity_pitch_pre_001_expected.json"
    INPUT_FILE_OUT.write_text(json.dumps(input_doc, indent=2) + "\n")
    EXPECTED_FILE_OUT.write_text(json.dumps(expected_doc, indent=2) + "\n")

    log.info(
        "pitch parity fixture generated",
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
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(
    year: int,
    fold_id: int,
    n: int,
    model_dir: Path | None,
    log_format: str,
) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    generate(year=year, fold_id=fold_id, n=n, model_dir=model_dir)


if __name__ == "__main__":
    main()
