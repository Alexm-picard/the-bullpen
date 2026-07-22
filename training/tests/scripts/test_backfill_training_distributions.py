"""Unit tests for the E-1 backfill CLI (Wave E) - the CLI-specific logic.

The champion config, request-space transforms (reconstruct / decode), and feature-block builder were
hoisted into ``registry_client.distributions`` (E-1 part 2) and are tested in
``tests/registry_client/test_distributions.py``. Here: the season / rule-13 guard and the additive
metadata merge. The CH query + real ONNX/calibrator inference are box-validated (lazy-imported in
CLI), not exercised here.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "backfill_training_distributions.py"
_spec = importlib.util.spec_from_file_location("backfill_training_distributions", _SCRIPT)
assert _spec is not None and _spec.loader is not None
backfill = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = backfill
_spec.loader.exec_module(backfill)


def test_parse_seasons_and_rule13_holdout_refusal():
    assert list(backfill._parse_seasons("2015-2025")) == list(range(2015, 2026))
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2015-2026")  # rule-13: 2026 is holdout-only
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2026-2026")
    with pytest.raises(SystemExit):
        backfill._parse_seasons("2025-2015")  # lo > hi


def test_pitch_feature_cols_selects_pre_31_vs_post_41():
    # The pre-vs-post ONNX input order is the load-bearing pre-specific decision in _served_proba;
    # exercise it directly (the full _served_proba wants a real ONNX session, so it is box-only).
    from bullpen_training.pitch import PITCH_FEATURE_COLUMNS, PITCH_FEATURE_COLUMNS_POST

    pre = backfill._pitch_feature_cols("pitch_outcome_pre")
    post = backfill._pitch_feature_cols("pitch_outcome_post")
    assert pre == PITCH_FEATURE_COLUMNS and len(pre) == 31
    assert post == PITCH_FEATURE_COLUMNS_POST and len(post) == 41
    assert "pitch_type_int" not in pre  # Tier-4, pre-head absent
    assert "pitch_type_int" in post


def test_merge_preserves_existing_metadata_keys(tmp_path: Path):
    meta_path = tmp_path / "metadata.json"
    original = {
        "model_name": "battedball_outcome",
        "model_version": "v2",
        "feature_pipeline_hash": "x",
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
