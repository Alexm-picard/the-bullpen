"""Unit tests for the E-1 backfill CLI (Wave E).

Cover the Mac-testable logic against synthetic frames: the request-key namespace (keys are the
prod-confirmed request fields, not source columns), the missing-source-column guard, and the
additive metadata merge (every existing key preserved). The champion serving path (real ONNX +
calibrator) and the real snapshot / ClickHouse schema are box-validated, not exercised here.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "backfill_training_distributions.py"
_spec = importlib.util.spec_from_file_location("backfill_training_distributions", _SCRIPT)
assert _spec is not None and _spec.loader is not None
backfill = importlib.util.module_from_spec(_spec)
# Register before exec so the frozen dataclass's __future__ annotations resolve via sys.modules.
sys.modules[_spec.name] = backfill
_spec.loader.exec_module(backfill)


def _battedball_frame(n: int = 120) -> pd.DataFrame:
    # Synthetic frame in REQUEST-value space (stand as "R"/"L", base_state/outs as ints).
    return pd.DataFrame(
        {
            "launch_speed_mph": np.linspace(60.0, 118.0, n),
            "launch_angle_deg": np.linspace(-20.0, 45.0, n),
            "spray_angle_deg": np.linspace(-45.0, 45.0, n),
            "hit_distance_ft": np.linspace(0.0, 460.0, n),
            "stand": np.array(["R", "L"])[np.arange(n) % 2],
            "base_state": np.arange(n) % 8,
            "outs": np.arange(n) % 3,
        }
    )


def test_feature_block_keys_are_request_fields_not_source_columns():
    cfg = backfill.CHAMPIONS["battedball_outcome"]
    block = backfill.build_feature_block(_battedball_frame(), cfg, max_sample=5000)
    # Keyed by the prod-confirmed request fields (camelCase), NOT the snake_case source columns.
    assert set(block) == {
        "launchSpeedMph",
        "launchAngleDeg",
        "sprayAngleDeg",
        "hitDistanceFt",
        "stand",
        "baseState",
        "outs",
    }
    assert block["launchSpeedMph"]["kind"] == "continuous"
    assert block["stand"]["kind"] == "categorical"
    assert block["stand"]["counts"] == {"R": 60, "L": 60}


def test_excluded_request_keys_are_never_emitted():
    cfg = backfill.CHAMPIONS["battedball_outcome"]
    block = backfill.build_feature_block(_battedball_frame(), cfg, max_sample=5000)
    # parkId / releaseSpeedMph are absent from the battedball request (the silent-skip trap).
    assert "parkId" not in block and "releaseSpeedMph" not in block
    assert cfg.excluded == ["parkId", "releaseSpeedMph"]


def test_missing_source_column_fails_loud():
    cfg = backfill.CHAMPIONS["battedball_outcome"]
    frame = _battedball_frame().drop(columns=["spray_angle_deg"])  # simulate a schema mismatch
    with pytest.raises(SystemExit) as exc:
        backfill.build_feature_block(frame, cfg, max_sample=5000)
    assert "spray_angle_deg" in str(exc.value)


def test_merge_preserves_existing_metadata_keys(tmp_path: Path):
    meta_path = tmp_path / "metadata.json"
    original = {
        "model_name": "battedball_outcome",
        "model_version": "v2",
        "feature_pipeline_hash": "abc",
    }
    meta_path.write_text(json.dumps(original))
    merged = backfill.merge_into_metadata(
        meta_path,
        feature_block={"launchSpeedMph": {"kind": "continuous", "sample": [1.0]}},
        prediction_block={"out": [0.6]},
    )
    # Every original key survives; the two additive keys are added.
    for k, v in original.items():
        assert merged[k] == v
    assert "feature_distributions" in merged and "training_prediction_distribution" in merged


def test_main_end_to_end_writes_both_blocks(tmp_path: Path):
    frame = _battedball_frame()
    parquet = tmp_path / "training_data.parquet"
    frame.to_parquet(parquet)
    meta_path = tmp_path / "metadata.json"
    meta_path.write_text(json.dumps({"model_name": "battedball_outcome", "model_version": "v2"}))
    proba = np.tile(np.array([0.6, 0.2, 0.1, 0.03, 0.07]), (len(frame), 1))
    proba_npy = tmp_path / "proba.npy"
    np.save(proba_npy, proba)

    rc = backfill.main(
        [
            "--model",
            "battedball_outcome",
            "--training-parquet",
            str(parquet),
            "--metadata",
            str(meta_path),
            "--proba-npy",
            str(proba_npy),
        ]
    )
    assert rc == 0
    written = json.loads(meta_path.read_text())
    assert written["model_version"] == "v2"  # preserved
    assert set(written["feature_distributions"]) == {
        "launchSpeedMph",
        "launchAngleDeg",
        "sprayAngleDeg",
        "hitDistanceFt",
        "stand",
        "baseState",
        "outs",
    }
    assert set(written["training_prediction_distribution"]) == {"out", "1b", "2b", "3b", "hr"}


def test_main_dry_run_writes_nothing(tmp_path: Path):
    frame = _battedball_frame()
    parquet = tmp_path / "training_data.parquet"
    frame.to_parquet(parquet)
    meta_path = tmp_path / "metadata.json"
    meta_path.write_text(json.dumps({"model_name": "battedball_outcome"}))
    rc = backfill.main(
        [
            "--model",
            "battedball_outcome",
            "--training-parquet",
            str(parquet),
            "--metadata",
            str(meta_path),
            "--dry-run",
        ]
    )
    assert rc == 0
    assert "feature_distributions" not in json.loads(meta_path.read_text())  # untouched
