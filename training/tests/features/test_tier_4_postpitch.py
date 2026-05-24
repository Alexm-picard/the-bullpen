"""Unit tests for the Tier 4 post-pitch pipeline (Phase 2b.1).

ClickHouse-side correctness (the JOIN against `pitches`) is covered by
the live build run for fold 3+4 documented in the leaf status log.
Here we lock the pure-Python pieces: the column tuple, the merge
semantics on small synthetic frames, and the contract that Tier 4
columns are not allowed in any `*_pre` pipeline.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from bullpen_training.features.tier_3_form import FEATURES_COLUMNS_FULL
from bullpen_training.features.tier_4_postpitch import (
    PK_JOIN,
    TIER4_COLUMNS,
    merge_tier4,
)

REPO_ROOT = Path(__file__).resolve().parents[2].parent
CONTRACT_PATH = REPO_ROOT / "contracts" / "feature_pipeline.json"


def test_tier4_columns_listed_in_full_features() -> None:
    """If somebody adds a Tier 4 column to V010 they must extend both
    TIER4_COLUMNS and FEATURES_COLUMNS_FULL; this guards the contract."""
    full = set(FEATURES_COLUMNS_FULL)
    missing = set(TIER4_COLUMNS) - full
    assert not missing, f"Tier 4 columns missing from FEATURES_COLUMNS_FULL: {missing}"


def test_tier4_column_set_matches_leaf_plan() -> None:
    """Lock the canonical Tier 4 column list — adding/removing requires a
    coordinated V0NN migration + a registry schema-hash bump."""
    expected = {
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
    }
    assert set(TIER4_COLUMNS) == expected


def test_pre_pipeline_contract_lists_no_tier4_columns() -> None:
    """CLAUDE.md catastrophic-leakage rule: pitch_outcome_pre's
    feature_pipeline.json MUST NOT reference any Tier 4 column. If this
    fails, the pre-pitch model is reading post-pitch data — which would
    silently inflate validation metrics."""
    spec = json.loads(CONTRACT_PATH.read_text())
    assert spec["model_name"] == "pitch_outcome_pre", (
        f"this test guards the pre-pitch contract; canonical contract is now "
        f"{spec['model_name']!r} — point this test at feature_pipeline_pre.json"
    )
    feature_set = set(spec["feature_order"])
    leaked = feature_set & set(TIER4_COLUMNS)
    assert not leaked, f"Tier 4 columns leaked into pre-pitch contract: {leaked}"


def test_merge_tier4_left_joins_on_pk_and_keeps_features_row_order() -> None:
    features = pd.DataFrame(
        {
            "game_id": [10, 10, 11],
            "at_bat_index": [1, 1, 1],
            "pitch_number": [1, 2, 1],
            "count_balls": [0, 1, 0],
        }
    )
    tier4 = pd.DataFrame(
        {
            "game_id": [11, 10, 10],
            "at_bat_index": [1, 1, 1],
            "pitch_number": [1, 1, 2],
            "pitch_type": ["FF", "SI", "SL"],
            "release_speed_mph": [94.1, 92.3, 85.4],
            "plate_x_in": [0.1, -0.2, 0.5],
            "plate_z_in": [2.0, 1.8, 2.2],
            "pfx_x_in": [0.5, -0.4, 1.1],
            "pfx_z_in": [1.0, 0.9, 0.2],
            "spin_rate_rpm": [2300.0, 2100.0, 2400.0],
            "spin_axis_deg": [200.0, 215.0, 90.0],
            "release_pos_x_in": [-1.5, -1.4, 1.6],
            "release_pos_z_in": [5.8, 5.9, 5.5],
        }
    )
    merged = merge_tier4(features, tier4)
    # Features row order preserved
    assert list(merged["game_id"]) == [10, 10, 11]
    assert list(merged["pitch_number"]) == [1, 2, 1]
    # Tier 4 joined correctly per PK
    assert list(merged["pitch_type"]) == ["SI", "SL", "FF"]
    assert merged["release_speed_mph"].tolist() == [92.3, 85.4, 94.1]


def test_merge_tier4_fills_pitch_type_for_unmatched_rows() -> None:
    """Pitch rows that don't have a Tier 4 match (shouldn't happen in
    practice — every features row originated from a pitches row — but
    happens for synthetic test fixtures) must get '' for pitch_type,
    not NaN. clickhouse-driver rejects NaN in a LowCardinality(String)."""
    features = pd.DataFrame(
        {
            "game_id": [99],
            "at_bat_index": [1],
            "pitch_number": [1],
            "count_balls": [0],
        }
    )
    tier4 = pd.DataFrame(
        {
            "game_id": [],
            "at_bat_index": [],
            "pitch_number": [],
            "pitch_type": [],
            "release_speed_mph": [],
            "plate_x_in": [],
            "plate_z_in": [],
            "pfx_x_in": [],
            "pfx_z_in": [],
            "spin_rate_rpm": [],
            "spin_axis_deg": [],
            "release_pos_x_in": [],
            "release_pos_z_in": [],
        }
    )
    merged = merge_tier4(features, tier4)
    assert merged["pitch_type"].iloc[0] == ""
    # Float Tier 4 columns get NaN (Nullable on the wire); fine.
    assert np.isnan(merged["release_speed_mph"].iloc[0])


def test_pk_join_matches_features_pk() -> None:
    """The Tier 4 PK must match the features PK so the merge is unambiguous."""
    assert PK_JOIN == ("game_id", "at_bat_index", "pitch_number")
