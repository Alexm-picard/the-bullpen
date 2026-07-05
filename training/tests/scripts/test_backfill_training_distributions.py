"""Unit tests for the E-1 backfill CLI (Wave E) - the Mac-testable PURE logic.

Covers: the battedball one-hot -> request-space reconstruction, the pitch _int decode (throws/stand
{0:L,1:R} not assumed backwards; park/pitch-type inversion), the season / rule-13 guard, the
request-key namespace of the feature block, the missing-source-column guard, and the additive
metadata merge. The ClickHouse query + real ONNX/calibrator inference are box-validated
(lazy-imported in the CLI), so they are not exercised here.
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


def _battedball_model_frame(n: int = 96) -> pd.DataFrame:
    """The frame rows_to_frame emits: model one-hots + request-space continuous + outs."""
    stand_l = np.arange(n) % 2  # index 0 -> R, index 1 -> L
    base = np.arange(n) % 8
    df = pd.DataFrame(
        {
            "launch_speed_mph": np.linspace(60.0, 118.0, n),
            "launch_angle_deg": np.linspace(-20.0, 45.0, n),
            "spray_angle_deg": np.linspace(-45.0, 45.0, n),
            "hit_distance_ft": np.linspace(0.0, 460.0, n),
            "stand_L": stand_l,
            "stand_R": 1 - stand_l,
            "outs": np.arange(n) % 3,
        }
    )
    for i in range(8):
        df[f"base_state_{i}"] = (base == i).astype(int)
    return df


def test_reconstruct_battedball_categoricals_from_one_hots():
    df = backfill._reconstruct_battedball_categoricals(_battedball_model_frame(8))
    assert list(df["stand_str"]) == ["R", "L", "R", "L", "R", "L", "R", "L"]
    assert list(df["base_state_int"]) == [0, 1, 2, 3, 4, 5, 6, 7]


def test_battedball_feature_block_keys_are_request_fields():
    df = backfill._reconstruct_battedball_categoricals(_battedball_model_frame(80))
    block = backfill.build_feature_block(df, backfill.CHAMPIONS["battedball_outcome"], 5000)
    assert set(block) == {
        "launchSpeedMph",
        "launchAngleDeg",
        "sprayAngleDeg",
        "hitDistanceFt",
        "stand",
        "baseState",
        "outs",
    }
    assert block["stand"]["kind"] == "categorical"
    assert set(block["stand"]["counts"]) == {"L", "R"}
    # base_state renders as int-string keys ("0".."7"), not "0.0".
    assert set(block["baseState"]["counts"]) == {str(i) for i in range(8)}
    assert "parkId" not in block and "releaseSpeedMph" not in block  # the silent-skip trap


def test_decode_pitch_categoricals_inverts_int_encodings():
    df = pd.DataFrame(
        {
            "park_id_int": [0, 1, 0],
            "pitch_type_int": [3, 3, 7],
            "pitcher_throws_int": [0, 1, 1],
            "batter_stand_int": [1, 0, 1],
        }
    )
    out = backfill._decode_pitch_categoricals(
        df, park_by_int={0: "ATL", 1: "AZ"}, ptype_by_int={3: "FF", 7: "SL"}
    )
    assert list(out["park_id"]) == ["ATL", "AZ", "ATL"]
    assert list(out["pitch_type"]) == ["FF", "FF", "SL"]
    # 0=L, 1=R (R is the null fallback) - not assumed backwards.
    assert list(out["pitcher_throws"]) == ["L", "R", "R"]
    assert list(out["batter_stand"]) == ["R", "L", "R"]


def test_parse_seasons_and_rule13_holdout_refusal():
    assert list(backfill._parse_seasons("2015-2025")) == list(range(2015, 2026))
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2015-2026")  # rule-13: 2026 is holdout-only
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2026-2026")
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2025-2015")  # lo > hi


def test_missing_source_column_fails_loud():
    cfg = backfill.CHAMPIONS["battedball_outcome"]
    df = backfill._reconstruct_battedball_categoricals(_battedball_model_frame(24)).drop(
        columns=["spray_angle_deg"]
    )
    with pytest.raises(SystemExit) as exc:
        backfill.build_feature_block(df, cfg, 5000)
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
    for k, v in original.items():
        assert merged[k] == v  # every original key survives
    assert "feature_distributions" in merged and "training_prediction_distribution" in merged
