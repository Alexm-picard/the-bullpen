"""Generate the Python↔Java parity fixture (1.4).

Reads a deterministic set of in-play rows from ClickHouse, runs each one
through the toy Python ONNX inference + the in-process LightGBM
predictor, and emits two JSON files:

    parity_toy_001.json           — raw input rows (one per row, JSON-friendly)
    parity_toy_001_expected.json  — preprocessed feature vector + ONNX prob

The Java parity test loads the same input file, runs Java's ONNX Runtime
+ FeaturePipeline, and asserts |prob - expected| < 1e-6. The Python
parity test does the same round-trip in Python.

Re-run when the toy model is retrained or the export pipeline changes.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any, cast

import click
import lightgbm as lgb
import numpy as np
import onnxruntime as ort
import pandas as pd

from bullpen_training.battedball.features_toy import FEATURES, TARGET
from bullpen_training.battedball.train_toy import DEFAULT_OUTPUT_DIR
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
FIXTURES_DIR = REPO_ROOT / "training" / "tests" / "fixtures"
INPUT_FILE = FIXTURES_DIR / "parity_toy_001.json"
EXPECTED_FILE = FIXTURES_DIR / "parity_toy_001_expected.json"


def _load_rows(client: Any, year: int, n: int) -> pd.DataFrame:
    query = f"""
        SELECT
            game_id,
            at_bat_index,
            pitch_number,
            game_date,
            home_team,
            launch_speed_mph,
            launch_angle_deg,
            release_speed_mph,
            park_id,
            stand,
            toUInt8(events = 'home_run') AS is_hr
        FROM pitches FINAL
        WHERE description = 'in_play'
          AND toYear(game_date) = {year}
          AND launch_speed_mph IS NOT NULL
          AND launch_angle_deg IS NOT NULL
          AND release_speed_mph IS NOT NULL
        ORDER BY game_date, game_id, at_bat_index, pitch_number
        LIMIT {n}
    """
    rows = cast(list[tuple[Any, ...]], client.execute(query))
    return pd.DataFrame(
        rows,
        columns=[
            "game_id",
            "at_bat_index",
            "pitch_number",
            "game_date",
            "home_team",
            "launch_speed_mph",
            "launch_angle_deg",
            "release_speed_mph",
            "park_id",
            "stand",
            TARGET,
        ],
    )


def _preprocess(row: dict[str, Any], park_hr_rate: dict[str, float]) -> list[float]:
    """Apply the documented preprocess steps in `feature_pipeline.json`
    order. Returns the float32 input vector for ONNX."""
    park_value = park_hr_rate.get(str(row["park_id"]))
    if park_value is None:
        park_value = float(np.mean(list(park_hr_rate.values())))
    return [
        float(row["launch_speed_mph"]),
        float(row["launch_angle_deg"]),
        float(row["release_speed_mph"]),
        float(park_value),
        1.0 if row["stand"] == "L" else 0.0,
    ]


def _onnx_probability(session: ort.InferenceSession, vector: list[float]) -> float:
    arr = np.array([vector], dtype=np.float32)
    raw = session.run(None, {"input": arr})
    probs = cast(np.ndarray, raw[1] if len(raw) > 1 else raw[0])
    # convert_lightgbm with zipmap=False emits a Nx2 prob matrix
    return float(probs[0][1])


def generate(
    *,
    out_dir: Path | None = None,
    artifacts_dir: Path | None = None,
    year: int = 2024,
    n: int = 16,
    settings: ClickHouseSettings | None = None,
) -> dict[str, Any]:
    from bullpen_training.battedball.export_toy_onnx import load_canonical_pipeline

    artifacts = artifacts_dir or DEFAULT_OUTPUT_DIR
    onnx_path = artifacts / "model.onnx"
    lgb_path = artifacts / "model.lgb"
    park_path = artifacts / "park_hr_rate.json"

    for p in (onnx_path, lgb_path, park_path):
        if not p.exists():
            raise FileNotFoundError(f"missing artifact: {p}")

    park_hr_rate: dict[str, float] = json.loads(park_path.read_text())
    pipeline_spec = load_canonical_pipeline()

    session = ort.InferenceSession(str(onnx_path))
    booster = lgb.Booster(model_file=str(lgb_path))

    df = _load_rows(make_client(settings), year, n)
    inputs: list[dict[str, Any]] = []
    expected: list[dict[str, Any]] = []
    for _, raw_row in df.iterrows():
        row: dict[str, Any] = {
            str(k): (v.isoformat() if hasattr(v, "isoformat") else v) for k, v in raw_row.items()
        }
        feature_vector = _preprocess(row, park_hr_rate)
        onnx_prob = _onnx_probability(session, feature_vector)
        # Sanity: also compute via the in-process booster (no ONNX in between)
        lgb_pred = cast(np.ndarray, booster.predict(np.array([feature_vector], dtype=np.float32)))
        lgb_prob = float(lgb_pred[0])
        inputs.append(row)
        expected.append(
            {
                "game_id": int(row["game_id"]),
                "at_bat_index": int(row["at_bat_index"]),
                "pitch_number": int(row["pitch_number"]),
                "feature_vector": feature_vector,
                "onnx_probability": onnx_prob,
                "lightgbm_probability": lgb_prob,
                "abs_difference_onnx_lightgbm": abs(onnx_prob - lgb_prob),
            }
        )

    out_root = out_dir or FIXTURES_DIR
    out_root.mkdir(parents=True, exist_ok=True)
    input_doc = {
        "model_name": "_toy_batted_ball",
        "version": "v0",
        "feature_order": list(FEATURES),
        "schema_hash": pipeline_spec["schema_hash"],
        "rows": inputs,
    }
    expected_doc = {
        "model_name": "_toy_batted_ball",
        "version": "v0",
        "schema_hash": pipeline_spec["schema_hash"],
        "tolerance": 1e-6,
        "rows": expected,
    }
    (out_root / "parity_toy_001.json").write_text(json.dumps(input_doc, indent=2) + "\n")
    (out_root / "parity_toy_001_expected.json").write_text(
        json.dumps(expected_doc, indent=2) + "\n"
    )

    max_drift = max((r["abs_difference_onnx_lightgbm"] for r in expected), default=0.0)
    log.info(
        "fixture generated",
        n=len(inputs),
        max_onnx_lgb_drift=max_drift,
        input_path=str(out_root / "parity_toy_001.json"),
        expected_path=str(out_root / "parity_toy_001_expected.json"),
    )
    return {
        "n": len(inputs),
        "max_onnx_lgb_drift": max_drift,
        "schema_hash": pipeline_spec["schema_hash"],
        "input_sha256": hashlib.sha256((out_root / "parity_toy_001.json").read_bytes()).hexdigest(),
    }


@click.command()
@click.option("--year", type=int, default=2024, show_default=True)
@click.option("--n", type=int, default=16, show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(year: int, n: int, artifacts_dir: Path | None, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    generate(year=year, n=n, artifacts_dir=artifacts_dir)


if __name__ == "__main__":
    main()
