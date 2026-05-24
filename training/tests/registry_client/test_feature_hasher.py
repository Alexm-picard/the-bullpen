"""Tests for ``bullpen_training.registry_client.feature_hasher``.

The parity fixtures live under ``contracts/test_fixtures/feature_hasher/``
and are shared with ``FeatureSchemaParityIT`` on the Java side. Both
implementations must hash each fixture to the same digest.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from bullpen_training.registry_client.feature_hasher import (
    compute,
    compute_from_content,
)

FIXTURE_DIR = (
    Path(__file__).resolve().parents[2].parent / "contracts" / "test_fixtures" / "feature_hasher"
)


def _load_manifest() -> list[dict[str, str]]:
    with (FIXTURE_DIR / "fixtures.json").open() as f:
        return json.load(f)


@pytest.mark.parametrize("entry", _load_manifest(), ids=lambda e: e["name"])
def test_fixture_hashes_match_manifest(entry: dict[str, str]) -> None:
    """Each shared parity fixture hashes to the expected value."""
    actual = compute(FIXTURE_DIR / entry["input_file"])
    assert actual == entry["expected_hash"], (
        f"hash drift on fixture {entry['name']}: expected {entry['expected_hash']}, got {actual}"
    )


def test_schema_hash_field_is_ignored() -> None:
    """Mutating only ``schema_hash`` must not change the digest."""
    a = '{"a": 1, "schema_hash": ""}'
    b = '{"a": 1, "schema_hash": "anything-else"}'
    assert compute_from_content(a) == compute_from_content(b)


def test_missing_schema_hash_field_is_treated_as_empty() -> None:
    """A doc without ``schema_hash`` hashes the same as one with ``""``."""
    without = '{"a": 1}'
    with_empty = '{"a": 1, "schema_hash": ""}'
    assert compute_from_content(without) == compute_from_content(with_empty)


def test_key_order_does_not_matter() -> None:
    """Same logical doc with different key insertion order = same hash."""
    a = '{"alpha": 1, "beta": 2}'
    b = '{"beta": 2, "alpha": 1}'
    assert compute_from_content(a) == compute_from_content(b)


def test_array_order_does_matter() -> None:
    """Arrays are semantic; reordering produces a different hash."""
    a = '{"xs": [1, 2, 3]}'
    b = '{"xs": [3, 2, 1]}'
    assert compute_from_content(a) != compute_from_content(b)


def test_invalid_json_raises_value_error() -> None:
    with pytest.raises(ValueError, match="not valid JSON"):
        compute_from_content("{not json")


def test_root_must_be_object() -> None:
    with pytest.raises(ValueError, match="must be a JSON object"):
        compute_from_content("[1, 2, 3]")


def test_compute_from_path_reads_file(tmp_path: Path) -> None:
    p = tmp_path / "pipeline.json"
    p.write_text('{"a": 1, "schema_hash": ""}', encoding="utf-8")
    direct = compute_from_content('{"a": 1, "schema_hash": ""}')
    assert compute(p) == direct
