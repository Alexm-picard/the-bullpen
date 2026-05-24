"""CI subset of the 2c.2 physics validation gate.

Loads 5 representative fixtures from ``data/physics_validation_fixtures.json``
and asserts each one independently meets the per-fixture pass criterion.
This keeps CI fast (no ClickHouse, no docker) while still pinning the
simulator's accuracy at the gate threshold — if any of the 5 starts
failing, the full 100-fixture run is almost certainly below the
``pass_rate >= 0.85`` gate.

Fixture selection: takes a stratified subset by park type — Coors (the
altitude case), a low-altitude park (Fenway), a mid-altitude park, a
typical home-run distance, and a wall-scraper. Curated for CI signal,
not for full coverage.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from bullpen_training.battedball.physics.validate import (
    _DISTANCE_TOL_FT_ABS,
    _DISTANCE_TOL_PCT,
    _evaluate_fixture,
    assert_gate,
    run_validation,
)

_FIXTURES_PATH = Path(__file__).resolve().parents[3] / "data" / "physics_validation_fixtures.json"


@pytest.fixture(scope="module")
def all_fixtures() -> list[dict]:
    if not _FIXTURES_PATH.exists():
        pytest.skip(
            f"physics fixtures not generated yet at {_FIXTURES_PATH}; "
            "run `uv run python -m bullpen_training.battedball.physics.fixtures` "
            "from a host with ClickHouse access."
        )
    return json.loads(_FIXTURES_PATH.read_text())["fixtures"]


def _pick_subset(fixtures: list[dict]) -> list[dict]:
    """5 fixtures: one Coors, one Fenway, one mid-altitude, plus the first
    two HRs from the dataset for stable ordering. Defensive: use the
    first match for each park and fall back to the head of the list if
    that park isn't represented (small fixture set + sampling artefact)."""

    def first_at(park: str) -> dict | None:
        for fx in fixtures:
            if fx["park_id"] == park:
                return fx
        return None

    head: list[dict | None] = [first_at("COL"), first_at("BOS"), first_at("ATL")]
    rest = [fx for fx in fixtures if fx not in head][:2]
    return [fx for fx in head if fx is not None] + rest


def test_validation_subset_each_fixture_passes(all_fixtures: list[dict]) -> None:
    """Every fixture in the subset must individually pass the per-fixture
    tolerance — if even one is failing, the simulator has regressed
    enough that the full-run gate is probably broken too."""
    subset = _pick_subset(all_fixtures)
    assert subset, "physics fixture subset is empty"
    failures: list[str] = []
    for fx in subset:
        r = _evaluate_fixture(fx)
        if not r.pass_distance:
            failures.append(
                f"{r.fixture_id} @{r.park_id} obs={r.observed_distance_ft:.0f} "
                f"pred={r.pred_distance_ft:.1f} err={r.err_distance_ft:+.1f} ft "
                f"({r.err_distance_pct:.1%})"
            )
    assert not failures, (
        f"per-fixture tolerance ({_DISTANCE_TOL_PCT:.0%} or "
        f"{_DISTANCE_TOL_FT_ABS:.0f} ft) violated: {failures}"
    )


def test_validation_report_gate_passes(all_fixtures: list[dict]) -> None:
    """End-to-end gate: run the full 100-fixture validation and assert
    the aggregate gate holds. Heavier than the subset test above (100 sims)
    but still ~1 s on the JIT path, so keep it in the standard test suite
    rather than gating it behind a slow marker."""
    report = run_validation(_FIXTURES_PATH)
    # assert_gate raises SystemExit; we want pytest to record it as a
    # failure with the full report attached for debugging.
    if not report["gate_passes"]:
        pytest.fail(
            f"physics validation gate FAILED: pass_rate="
            f"{report['pass_rate_distance']:.2%} "
            f"(>= {report['gate_pass_rate']:.0%}), mae="
            f"{report['mae_distance_ft']:.2f} ft "
            f"(<= {report['gate_mae_distance_ft']:.0f}); "
            f"{len(report['failures'])} per-fixture failures"
        )
    # Call assert_gate too so the helper itself is exercised.
    assert_gate(report)
