"""Tests for the verified pitch-type contract reads (CLAUDE.md rule 7).

These pin the two gates the production bundle's integrity rests on, and prove they BITE:
a contract whose declared schema_hash has drifted from its content must fail loud, and a
bundle missing a lookup file its own contract declares must be refused.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from bullpen_training.pitch_type.contract import (
    CONTRACT_PATH,
    assert_declared_lookups_present,
    declared_lookup_paths,
    load_canonical_pipeline,
)
from bullpen_training.registry_client import feature_hasher


def test_canonical_contract_hash_is_current() -> None:
    """The committed contract's declared hash matches its content (the pre-commit invariant)."""
    spec = load_canonical_pipeline()
    assert spec["model_name"] == "pitch_type_pre"
    assert spec["schema_hash"] == feature_hasher.compute(CONTRACT_PATH)


def test_load_rejects_a_drifted_contract(tmp_path: Path) -> None:
    """A contract edited without recomputing its hash must fail loud HERE, not silently stamp
    a wrong hash into a bundle that only registration would reject - after a box run is spent."""
    spec = json.loads(CONTRACT_PATH.read_text())
    spec["description"] = spec["description"] + " (drifted without recomputing schema_hash)"
    drifted = tmp_path / "feature_pipeline_pitchtype.json"
    drifted.write_text(json.dumps(spec, indent=2) + "\n")
    with pytest.raises(RuntimeError, match="schema_hash is stale"):
        load_canonical_pipeline(drifted)


def test_declared_lookup_paths_covers_park_map_and_calibrator() -> None:
    """The pitch-type contract declares exactly two side-cars: park_i's categorical_lookup and
    the temperature calibrator. Derived from the contract, not hardcoded, so a future contract
    that adds a lookup tightens the completeness check automatically."""
    paths = declared_lookup_paths(load_canonical_pipeline())
    assert set(paths) == {"park_id_mapping.json", "calibrator.json"}


def test_assert_declared_lookups_present_passes_when_complete(tmp_path: Path) -> None:
    spec = load_canonical_pipeline()
    for name in declared_lookup_paths(spec):
        (tmp_path / name).write_text("{}")
    assert_declared_lookups_present(tmp_path, spec)  # must not raise


@pytest.mark.parametrize("omit", ["park_id_mapping.json", "calibrator.json"])
def test_assert_declared_lookups_present_bites_on_each_missing_file(
    tmp_path: Path, omit: str
) -> None:
    spec = load_canonical_pipeline()
    for name in declared_lookup_paths(spec):
        if name != omit:
            (tmp_path / name).write_text("{}")
    with pytest.raises(ValueError, match="missing contract-declared lookup"):
        assert_declared_lookups_present(tmp_path, spec)
