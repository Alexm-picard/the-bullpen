"""Outcome-calibration registration gate (Phase 2c, decision [141]) — BLOCKING.

Batted-ball v1 ships as a calibrated per-park *outcome* model, so registration is
gated on outcome calibration, NOT cross-park ranking (the 2c.7 cross-park check is
now a non-blocking diagnostic — see ``test_cross_park_sanity.py`` + decision [141]).

Thresholds (the Phase-2 exit bar):
  - per-park ECE post-cal mean  < 0.05
  - aggregate test ECE          < 0.02

Both come from ``calibration_metrics.json``, which 2c.6 (``fit_calibrators.py``)
writes from the *calibrated* probabilities. We read the persisted file rather than
recompute so the gate is a pure function of the registered artifact — and we fail
LOUD if the file is missing while a model exists, so a stale/un-emitted metrics
file can never let the gate pass-by-skip and admit an un-checked model.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

PER_PARK_ECE_GATE = 0.05
AGGREGATE_ECE_GATE = 0.02

_MODEL_DIR = Path(__file__).resolve().parents[3] / "artifacts" / "battedball_mlp_v1"
_METRICS_PATH = _MODEL_DIR / "calibration_metrics.json"


def _have_production_artifacts() -> bool:
    return (_MODEL_DIR / "model.pt").exists() and (_MODEL_DIR / "metadata.json").exists()


def test_gate_thresholds_are_pinned() -> None:
    """Pin decision [141]: registration is gated on outcome calibration."""
    assert PER_PARK_ECE_GATE == 0.05
    assert AGGREGATE_ECE_GATE == 0.02


@pytest.mark.production
@pytest.mark.skipif(not _have_production_artifacts(), reason="batted_ball/v1 not trained yet")
def test_production_model_passes_outcome_calibration_gate() -> None:
    """The HARD registration gate for batted-ball v1 (decision [141])."""
    assert _METRICS_PATH.exists(), (
        f"calibration_metrics.json missing at {_METRICS_PATH}. The outcome gate must "
        "not be skipped when a trained model exists (decision [141]). Re-run 2c.6:\n"
        "  uv run python scripts/fit_calibrators.py --mlp-dir artifacts/battedball_mlp_v1 "
        "--val-season-from <VAL_SEASON>"
    )
    m = json.loads(_METRICS_PATH.read_text())
    per_park_mean = float(m["per_park_ece_post_mean"])
    aggregate = float(m["aggregate_ece_post"])

    failures: list[str] = []
    if not per_park_mean < PER_PARK_ECE_GATE:
        failures.append(f"per-park ECE post mean {per_park_mean:.4f} >= gate {PER_PARK_ECE_GATE}")
    if not aggregate < AGGREGATE_ECE_GATE:
        failures.append(f"aggregate test ECE {aggregate:.4f} >= gate {AGGREGATE_ECE_GATE}")
    assert not failures, "; ".join(failures)
