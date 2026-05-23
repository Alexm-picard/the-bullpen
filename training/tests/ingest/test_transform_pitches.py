"""Unit tests for the pitches cleaning orchestrator.

Pure-Python coverage of the bind-substitution, assertion parser, and
assertion-gate evaluation. ClickHouse-side correctness (the actual SQL
transform + ReplacingMergeTree dedup) is covered by the live drill at
the end of Phase 1.2 and asserted against real 2024 data.
"""

from __future__ import annotations

import pytest

from bullpen_training.ingest.assertions import AssertionFailure
from bullpen_training.ingest.transform_pitches import (
    _bind,
    _evaluate_assertion,
    _parse_assertions,
)


def test_bind_substitutes_single_param() -> None:
    assert _bind("SELECT :year", {"year": 2024}) == "SELECT 2024"


def test_bind_substitutes_multiple_occurrences() -> None:
    sql = "WHERE a = :year OR b = :year"
    assert _bind(sql, {"year": 2024}) == "WHERE a = 2024 OR b = 2024"


def test_bind_respects_word_boundary() -> None:
    """`:year` should not match `:yearly` if we ever add such a param."""
    sql = "SELECT :year, :yearly"
    out = _bind(sql, {"year": 2024, "yearly": 12})
    assert out == "SELECT 2024, 12"


def test_parse_assertions_extracts_named_blocks() -> None:
    sql = """
    -- top-level comment
    -- @name: alpha
    SELECT 1;

    -- @name: beta
    SELECT count(*) FROM t WHERE x > 0;
    """
    parsed = _parse_assertions(sql)
    names = [n for n, _ in parsed]
    assert names == ["alpha", "beta"]
    assert "SELECT 1" in parsed[0][1]
    assert "count(*)" in parsed[1][1]


def test_parse_assertions_handles_no_trailing_semicolon() -> None:
    sql = "-- @name: solo\nSELECT 42"
    parsed = _parse_assertions(sql)
    assert parsed == [("solo", "SELECT 42")]


def test_evaluate_max_gate_passes_at_limit() -> None:
    _evaluate_assertion("zero_id_rows", 0)


def test_evaluate_max_gate_passes_under_limit() -> None:
    _evaluate_assertion("unknown_description_excess", 42)


def test_evaluate_max_gate_fails_over_limit() -> None:
    with pytest.raises(AssertionFailure, match="zero_id_rows"):
        _evaluate_assertion("zero_id_rows", 1)


def test_evaluate_range_gate_passes_in_band() -> None:
    _evaluate_assertion("regular_season_count", 700_000)


def test_evaluate_range_gate_fails_below_band() -> None:
    with pytest.raises(AssertionFailure, match="regular_season_count"):
        _evaluate_assertion("regular_season_count", 100_000)


def test_evaluate_range_gate_fails_above_band() -> None:
    with pytest.raises(AssertionFailure, match="regular_season_count"):
        _evaluate_assertion("regular_season_count", 1_000_000)


def test_evaluate_unknown_assertion_is_a_warning_not_a_failure() -> None:
    """Forward-compat: if SQL adds an assertion before the gate map is updated,
    it warns rather than crashing — the SQL change rolls out first, the gate
    update lands in the next commit."""
    _evaluate_assertion("brand_new_assertion", 12345)
