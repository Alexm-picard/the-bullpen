"""Per-(BIP, park) Monte-Carlo retrodiction (Phase 2c.4).

One BIP -> 30 outcome distributions, one per MLB park. For each park
we run the physics simulator N_MC times with light Gaussian noise on
launch parameters (decision [47] Path A — physics retrodiction, NOT a
PINN per decision [48]), then classify each trajectory via
:func:`battedball.parks.classify_outcome` and bin into a 5-class
probability vector.

Noise model (the leaf's "small Gaussian noise on launch params"):
  - launch_speed_mph  ~ N(observed, 0.5 mph)     -- Statcast EV noise ~0.5 mph
  - launch_angle_deg  ~ N(observed, 0.7 deg)     -- Statcast LA noise ~0.7 deg
  - spray_angle_deg   ~ N(observed, 0.5 deg)
  - spin_rate_rpm     -- held fixed (no Statcast measurement to noise around)
  - spin_axis_tilt    -- held fixed
  - initial_height_m  -- held fixed at 1.0 m

The Monte Carlo lives here (not inside :func:`simulate`) so it can be
audited per BIP, and because batching the same BIP's N samples at every
park in one ``simulate_batch`` call amortises the Numba overhead.

Determinism: the RNG is seeded per-BIP via ``seed_offset + hash(bbip_id)``
so the same BIP always produces the same probability vector on every
re-run, which makes the pipeline idempotent under the
ReplacingMergeTree in V011.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Final

import numpy as np

from bullpen_training.battedball.parks import (
    Outcome,
    classify_outcome,
    load_park_geometry,
)
from bullpen_training.battedball.parks.loader import ParkGeometry
from bullpen_training.battedball.physics.atmosphere import Atmosphere
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate_batch
from bullpen_training.battedball.retrodict._atmospheres import get_atmosphere

# Statcast measurement noise + small fielder-positioning randomness (the
# leaf's "noise model"). Keep these as module constants so the test +
# audit code can import them directly.
LAUNCH_SPEED_SIGMA_MPH: Final[float] = 0.5
LAUNCH_ANGLE_SIGMA_DEG: Final[float] = 0.7
SPRAY_ANGLE_SIGMA_DEG: Final[float] = 0.5

# Default Monte Carlo sample count (the leaf specifies 10).
DEFAULT_N_MC: Final[int] = 10

# Default per-BIP seed offset; production callers pass their own to
# scope per-run reproducibility.
DEFAULT_SEED_OFFSET: Final[int] = 0xBA5EBA11


@dataclass(frozen=True)
class BBIP:
    """One ball-in-play row, the input to retrodiction.

    Fields are exactly what the pipeline pulls from ``pitches``:
    natural key (game_date, game_id, at_bat_index, pitch_number) plus
    the launch parameters and the home park / observed outcome the
    home-park label gets attached to.
    """

    game_date: str  # ISO date YYYY-MM-DD
    game_id: int
    at_bat_index: int
    pitch_number: int
    home_park_id: str
    launch_speed_mph: float
    launch_angle_deg: float
    spray_angle_deg: float
    spin_rate_rpm: float
    spin_axis_tilt_deg: float
    observed_event: str  # raw Statcast events string (for home-park label)

    @property
    def bbip_key(self) -> str:
        """Stable string key used to seed the per-BIP RNG."""
        return f"{self.game_id}-{self.at_bat_index}-{self.pitch_number}"


@dataclass(frozen=True)
class RetrodictionResult:
    """One row's worth of the V011 ``bbip_retrodicted_labels`` schema."""

    bbip: BBIP
    park_id: str
    is_home_park: bool
    prob_out: float
    prob_1b: float
    prob_2b: float
    prob_3b: float
    prob_hr: float
    observed_outcome: str | None  # 'out' / '1b' / '2b' / '3b' / 'hr', only on home park
    n_mc: int


# --- event -> outcome mapping ---------------------------------------------

# Statcast `events` strings collapsed to the 5-class label space.
# Anything in this set that maps to None is a BIP we skip entirely
# (defensive interference, errors, etc. — rare, not part of the
# clean 5-class label space).
_EVENT_TO_OUTCOME: Final[dict[str, str | None]] = {
    "home_run": "hr",
    "single": "1b",
    "double": "2b",
    "triple": "3b",
    "field_out": "out",
    "force_out": "out",
    "grounded_into_double_play": "out",
    "double_play": "out",
    "triple_play": "out",
    "fielders_choice": "out",
    "fielders_choice_out": "out",
    "sac_fly": "out",
    "sac_fly_double_play": "out",
    "sac_bunt": "out",
    "sac_bunt_double_play": "out",
    # Ambiguous / non-clean BIPs:
    "field_error": None,
    "catcher_interf": None,
    "batter_interference": None,
    "fan_interference": None,
}


def event_to_outcome(event: str) -> str | None:
    """Map a Statcast ``events`` value to a 5-class outcome string.

    Returns ``None`` for ambiguous events the retrodiction skips (errors,
    interference). The pipeline filters these upstream — this helper is
    here so the test layer can pin the mapping table without re-importing
    the dict.
    """
    return _EVENT_TO_OUTCOME.get(event)


# --- core: one BIP at one park ---------------------------------------------


def _seed_for_bbip(bbip_key: str, seed_offset: int) -> int:
    """Stable 32-bit seed for a BIP. Hash of the key gives a uniform
    distribution; XOR with the offset lets callers re-roll the whole
    pipeline by changing one number."""
    digest = hashlib.sha256(bbip_key.encode()).digest()
    return int.from_bytes(digest[:4], "big") ^ seed_offset


def _jittered_launches(
    bbip: BBIP,
    *,
    n_mc: int,
    rng: np.random.Generator,
) -> list[LaunchParams]:
    """Build n_mc LaunchParams around the BIP's observed launch."""
    speed_jit = rng.normal(0.0, LAUNCH_SPEED_SIGMA_MPH, size=n_mc)
    angle_jit = rng.normal(0.0, LAUNCH_ANGLE_SIGMA_DEG, size=n_mc)
    spray_jit = rng.normal(0.0, SPRAY_ANGLE_SIGMA_DEG, size=n_mc)
    return [
        LaunchParams(
            launch_speed_mph=float(bbip.launch_speed_mph + speed_jit[i]),
            launch_angle_deg=float(bbip.launch_angle_deg + angle_jit[i]),
            spray_angle_deg=float(bbip.spray_angle_deg + spray_jit[i]),
            spin_rate_rpm=float(bbip.spin_rate_rpm),
            spin_axis_tilt_deg=float(bbip.spin_axis_tilt_deg),
            initial_height_m=1.0,
        )
        for i in range(n_mc)
    ]


def retrodict_one(
    bbip: BBIP,
    park_id: str,
    atmosphere: Atmosphere | None = None,
    *,
    n_mc: int = DEFAULT_N_MC,
    seed_offset: int = DEFAULT_SEED_OFFSET,
) -> RetrodictionResult:
    """Retrodict one (BIP, park) — N Monte Carlo simulations + classify.

    Used by the pipeline for single-park diagnostics and by tests.
    Production callers go through :func:`retrodict_bip_at_all_parks`
    which batches all 30 parks in one ``simulate_batch`` for speed.
    """
    park = load_park_geometry(park_id)
    atmo = atmosphere or get_atmosphere(park_id)
    rng = np.random.default_rng(_seed_for_bbip(bbip.bbip_key, seed_offset))
    launches = _jittered_launches(bbip, n_mc=n_mc, rng=rng)
    trajectories = simulate_batch(launches, [atmo] * n_mc)
    counts: dict[Outcome, int] = dict.fromkeys(Outcome, 0)
    for traj in trajectories:
        counts[classify_outcome(traj, park)] += 1
    total = float(n_mc)
    is_home = park_id == bbip.home_park_id
    observed = event_to_outcome(bbip.observed_event) if is_home else None
    return RetrodictionResult(
        bbip=bbip,
        park_id=park_id,
        is_home_park=is_home,
        prob_out=counts[Outcome.OUT] / total,
        prob_1b=counts[Outcome.SINGLE] / total,
        prob_2b=counts[Outcome.DOUBLE] / total,
        prob_3b=counts[Outcome.TRIPLE] / total,
        prob_hr=counts[Outcome.HOME_RUN] / total,
        observed_outcome=observed,
        n_mc=n_mc,
    )


# --- batched: one BIP at all 30 parks --------------------------------------


def retrodict_bip_at_all_parks(
    bbip: BBIP,
    park_ids: list[str],
    *,
    n_mc: int = DEFAULT_N_MC,
    seed_offset: int = DEFAULT_SEED_OFFSET,
) -> list[RetrodictionResult]:
    """Retrodict one BIP at every park. Batches all park*MC sims at once.

    Sends ``len(park_ids) * n_mc`` trajectories through a single
    ``simulate_batch`` call — this is where the Numba JIT pays off,
    keeping the typical per-BIP work down to ~30-50 ms on warm caches.
    """
    rng = np.random.default_rng(_seed_for_bbip(bbip.bbip_key, seed_offset))
    parks: dict[str, ParkGeometry] = {pid: load_park_geometry(pid) for pid in park_ids}
    base_launches = _jittered_launches(bbip, n_mc=n_mc, rng=rng)

    flat_launches: list[LaunchParams] = []
    flat_atmospheres: list[Atmosphere] = []
    for pid in park_ids:
        atmo = get_atmosphere(pid)
        for lp in base_launches:
            flat_launches.append(lp)
            flat_atmospheres.append(atmo)
    trajectories = simulate_batch(flat_launches, flat_atmospheres)

    results: list[RetrodictionResult] = []
    for i, pid in enumerate(park_ids):
        park = parks[pid]
        counts: dict[Outcome, int] = dict.fromkeys(Outcome, 0)
        for j in range(n_mc):
            counts[classify_outcome(trajectories[i * n_mc + j], park)] += 1
        total = float(n_mc)
        is_home = pid == bbip.home_park_id
        observed = event_to_outcome(bbip.observed_event) if is_home else None
        results.append(
            RetrodictionResult(
                bbip=bbip,
                park_id=pid,
                is_home_park=is_home,
                prob_out=counts[Outcome.OUT] / total,
                prob_1b=counts[Outcome.SINGLE] / total,
                prob_2b=counts[Outcome.DOUBLE] / total,
                prob_3b=counts[Outcome.TRIPLE] / total,
                prob_hr=counts[Outcome.HOME_RUN] / total,
                observed_outcome=observed,
                n_mc=n_mc,
            )
        )
    return results


__all__ = (
    "BBIP",
    "DEFAULT_N_MC",
    "DEFAULT_SEED_OFFSET",
    "LAUNCH_ANGLE_SIGMA_DEG",
    "LAUNCH_SPEED_SIGMA_MPH",
    "SPRAY_ANGLE_SIGMA_DEG",
    "RetrodictionResult",
    "event_to_outcome",
    "retrodict_bip_at_all_parks",
    "retrodict_one",
)
