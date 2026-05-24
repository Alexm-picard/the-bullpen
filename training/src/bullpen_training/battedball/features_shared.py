"""Torch-free feature-encoding helpers shared between the MLP and LGBM
baseline (Phase 2c.5 + 2c.8).

These live in their own module so consumers (notably the LightGBM
baseline in `lgbm_baseline/`) can import them without dragging torch
in transitively — co-loading torch + lightgbm on macOS triggers a
double-libomp segfault that the CI workflow splits explicitly to
avoid. Keeping this module torch-free is a load-bearing invariant.
"""

from __future__ import annotations

import math
from typing import Final

import numpy as np

# Stable feature order emitted into metadata.json so the Java
# inference side can mirror it via FeaturePipeline.
FEATURE_NAMES: Final[tuple[str, ...]] = (
    "launch_speed_mph",
    "launch_angle_deg",
    "spray_angle_deg",
    "hit_distance_ft",
    "stand_R",
    "stand_L",
    "base_state_0",
    "base_state_1",
    "base_state_2",
    "base_state_3",
    "base_state_4",
    "base_state_5",
    "base_state_6",
    "base_state_7",
    "outs",
)

# Outcome ordering — matches V011's Enum8 and the retrodict label order.
OUTCOME_NAMES: Final[tuple[str, ...]] = ("out", "1b", "2b", "3b", "hr")


def stand_one_hot(stand: str) -> np.ndarray:
    """One-hot the batter handedness ('R' or 'L'). Unknowns -> R fallback."""
    out = np.zeros(2, dtype=np.float32)
    out[0 if stand != "L" else 1] = 1.0
    return out


def base_state_one_hot(base_state: int) -> np.ndarray:
    """One-hot the 0-7 baserunner state (3-bit: 1B / 2B / 3B occupancy)."""
    out = np.zeros(8, dtype=np.float32)
    if 0 <= base_state <= 7:
        out[base_state] = 1.0
    return out


def hc_to_spray_deg(hc_x: float, hc_y: float) -> float:
    """Statcast hc_x/hc_y -> spray angle in sim convention (+ to 3B/LF).

    Constants 125.42 and 198.27 are the published Statcast home-plate
    coordinates in the scaled hc_x/hc_y system. Matches the formula in
    `retrodict.run_pipeline._spray_deg_from_hc`; tests in both modules
    pin them to each other.
    """
    return math.degrees(math.atan2(125.42 - hc_x, 198.27 - hc_y))


__all__ = (
    "FEATURE_NAMES",
    "OUTCOME_NAMES",
    "base_state_one_hot",
    "hc_to_spray_deg",
    "stand_one_hot",
)
