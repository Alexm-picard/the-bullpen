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
from functools import lru_cache
from typing import Final

import numpy as np

from bullpen_training.battedball.parks import (
    Outcome,
    classify_outcome,
    load_park_geometry,
)
from bullpen_training.battedball.parks.loader import ParkGeometry
from bullpen_training.battedball.physics._constants import (
    DEG_TO_RAD,
    MPH_TO_M_S,
    RPM_TO_RAD_S,
)
from bullpen_training.battedball.physics._fused import (
    DOUBLE_CODE,
    HR_CODE,
    OUT_CODE,
    SINGLE_CODE,
    TRAJ_IN_COLS,
    TRIPLE_CODE,
    simulate_classify_batch,
)
from bullpen_training.battedball.physics.atmosphere import Atmosphere, air_density
from bullpen_training.battedball.physics.simulator import LaunchParams, simulate_batch
from bullpen_training.battedball.physics.spin import (
    PhysicsCalibration,
    batted_ball_spin,
    load_physics_calibration,
)
from bullpen_training.battedball.retrodict._atmospheres import (
    Weather,
    get_atmosphere,
    still_air_atmosphere,
    weather_to_atmosphere,
)

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
    weather: Weather | None = None,
) -> RetrodictionResult:
    """Retrodict one (BIP, park) — N Monte Carlo simulations + classify.

    Used by the pipeline for single-park diagnostics and by tests.
    Production callers go through :func:`retrodict_bip_at_all_parks`
    which batches all 30 parks in one ``simulate_batch`` for speed.

    Atmosphere precedence: explicit ``atmosphere`` > ``weather`` (the game's
    actual conditions projected onto this park) > seasonal default.
    """
    park = load_park_geometry(park_id)
    if atmosphere is not None:
        atmo = atmosphere
    elif weather is not None:
        atmo = weather_to_atmosphere(weather, park)
    else:
        atmo = get_atmosphere(park_id)
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
    weather: Weather | None = None,
) -> list[RetrodictionResult]:
    """Retrodict one BIP at every park. Batches all park*MC sims at once.

    Sends ``len(park_ids) * n_mc`` trajectories through a single
    ``simulate_batch`` call — this is where the Numba JIT pays off,
    keeping the typical per-BIP work down to ~30-50 ms on warm caches.

    When ``weather`` (the BIP's actual game-time conditions) is supplied, the
    same field-relative wind + temperature is applied at every park — only
    per-park altitude/geometry varies, which isolates the park's physical HR
    factor. With no weather we fall back to still air per park (NOT the seasonal
    default, which is what scrambled the cross-park ranking).
    """
    rng = np.random.default_rng(_seed_for_bbip(bbip.bbip_key, seed_offset))
    parks: dict[str, ParkGeometry] = {pid: load_park_geometry(pid) for pid in park_ids}
    base_launches = _jittered_launches(bbip, n_mc=n_mc, rng=rng)

    flat_launches: list[LaunchParams] = []
    flat_atmospheres: list[Atmosphere] = []
    for pid in park_ids:
        park = parks[pid]
        atmo = (
            weather_to_atmosphere(weather, park)
            if weather is not None
            else still_air_atmosphere(park)
        )
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


# --- bulk fused integrate+classify (GPU-B production path) -----------------


@lru_cache(maxsize=8)
def _build_fence_arrays(
    park_ids: tuple[str, ...],
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Padded per-park fence polylines for the fused kernel.

    Returns ``(angles, distances, heights)`` each shape ``(P, K)`` float32 plus
    ``counts`` ``(P,)`` int32, where ``P = len(park_ids)`` and ``K`` is the
    longest polyline. Rows are padded with the last real point; ``counts`` tells
    the kernel how many entries are real so padding is never interpolated into.
    Cached because the geometry is static across the whole pipeline run.
    """
    geoms = [load_park_geometry(pid) for pid in park_ids]
    k = max(len(g.fence_polyline) for g in geoms)
    p = len(geoms)
    angles = np.zeros((p, k), dtype=np.float32)
    dists = np.zeros((p, k), dtype=np.float32)
    heights = np.zeros((p, k), dtype=np.float32)
    counts = np.zeros(p, dtype=np.int32)
    for i, g in enumerate(geoms):
        poly = g.fence_polyline  # already sorted by angle in the loader
        counts[i] = len(poly)
        for j in range(k):
            pt = poly[j] if j < len(poly) else poly[-1]
            angles[i, j] = pt.angle_from_centerline_deg
            dists[i, j] = pt.distance_ft
            heights[i, j] = pt.height_ft
    return angles, dists, heights, counts


def _bbip_velocities(
    bbip: BBIP, n_mc: int, rng: np.random.Generator
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Jittered initial velocities (m/s) + height + the jittered launch params.

    Reproduces ``_jittered_launches`` + ``simulator._initial_state`` exactly —
    same RNG draw order (speed, angle, spray) — but skips the LaunchParams
    objects and emits the ``(vx, vy, vz, height)`` the kernel consumes directly,
    plus the jittered ``(ev_mph, la_deg, spray_deg)`` so the spin model can be
    evaluated per draw. Keeping the draw sequence identical preserves the
    per-BIP seeded idempotency the reference path relies on.
    """
    speed_jit = rng.normal(0.0, LAUNCH_SPEED_SIGMA_MPH, size=n_mc)
    angle_jit = rng.normal(0.0, LAUNCH_ANGLE_SIGMA_DEG, size=n_mc)
    spray_jit = rng.normal(0.0, SPRAY_ANGLE_SIGMA_DEG, size=n_mc)
    ev_mph = bbip.launch_speed_mph + speed_jit
    la_deg = bbip.launch_angle_deg + angle_jit
    spray_deg = bbip.spray_angle_deg + spray_jit
    v = ev_mph * MPH_TO_M_S
    la = la_deg * DEG_TO_RAD
    sa = spray_deg * DEG_TO_RAD
    vx = v * np.cos(la) * np.cos(sa)
    vy = v * np.cos(la) * np.sin(sa)
    vz = v * np.sin(la)
    height = np.full(n_mc, 1.0)  # initial_height_m default
    return vx, vy, vz, height, ev_mph, la_deg, spray_deg


def retrodict_bips_batch(
    bbips: list[BBIP],
    park_ids: list[str],
    *,
    n_mc: int = DEFAULT_N_MC,
    seed_offset: int = DEFAULT_SEED_OFFSET,
    weather_by_game: dict[int, Weather] | None = None,
    device: str = "auto",
    calibration: PhysicsCalibration | None = None,
) -> list[RetrodictionResult]:
    """Retrodict many BIPs at all parks in one fused integrate+classify call.

    The GPU-B production path used by the pipeline. Builds a flat
    ``(n_bbip * n_parks * n_mc, TRAJ_IN_COLS)`` trajectory-input matrix on the
    host, runs the fused kernel once (GPU when available, else the njit/prange
    CPU fallback — ``device``), and reduces the per-trajectory outcome codes into
    5-class probability rows. Results are ordered ``[bbip0 @ all parks, bbip1 @
    all parks, ...]`` — the same order as looping ``retrodict_bip_at_all_parks``.

    Per-BIP weather (the game's actual conditions, ``weather_by_game[game_id]``)
    is applied identically at all parks (field-relative wind + temperature; only
    per-park altitude/geometry varies). Games without a row fall back to still
    air per park — matching ``retrodict_bip_at_all_parks``'s no-weather policy
    (NOT the seasonal default, which scrambled the cross-park ranking).

    This is the float32 path (decision: GPU-B runs float32); it does not reproduce
    the float64 reference bit-for-bit. ``test_fused_parity.py`` pins the
    outcome-agreement rate vs the reference, and the calibration gate (decision
    [131]) is re-validated on the desktop before any full relabel.
    """
    weather_by_game = weather_by_game or {}
    calib = calibration if calibration is not None else load_physics_calibration()
    n_bbip = len(bbips)
    n_parks = len(park_ids)
    if n_bbip == 0:
        return []

    parks = [load_park_geometry(pid) for pid in park_ids]
    fence_angle, fence_dist, fence_height, fence_n = _build_fence_arrays(tuple(park_ids))

    # Per-park static atmosphere inputs.
    alt = np.array([p.altitude_m for p in parks], dtype=np.float64)  # (P,)
    hum = np.array([p.default_atmosphere.humidity_pct for p in parks], dtype=np.float64)
    def_temp = np.array([p.default_atmosphere.temp_c for p in parks], dtype=np.float64)

    # Per-BIP weather: temperature (NaN => use the target park's default) + wind.
    # Wind is field-relative so it is the same vector at every park.
    temp_bbip = np.full(n_bbip, np.nan)
    wind_x = np.zeros(n_bbip)
    wind_y = np.zeros(n_bbip)
    for b, bbip in enumerate(bbips):
        w = weather_by_game.get(bbip.game_id)
        if w is not None:
            if w.temp_c is not None:
                temp_bbip[b] = w.temp_c
            wind_x[b] = w.wind_speed_m_s * w.wind_out_x
            wind_y[b] = w.wind_speed_m_s * w.wind_out_y

    # Air density per (bbip, park): game temp where present else park default,
    # ISA pressure at the park's altitude, park seasonal humidity. Vectorised.
    temp_mat = np.where(np.isnan(temp_bbip)[:, None], def_temp[None, :], temp_bbip[:, None])
    rho_mat = np.asarray(
        air_density(temp_mat, None, alt[None, :], hum[None, :]), dtype=np.float64
    )  # (B, P)

    # Per-BIP jittered velocities (seeded per BIP) + per-draw spin from the
    # calibrated spin model (computed from each jittered EV/LA/spray, NOT the
    # BBIP's fixed prior — that's the Phase-1 physics overhaul). spin axis tilts
    # off pure backspin with spray; spin_rate in rad/s for the kernel.
    vel = np.zeros((n_bbip, n_mc, 4))  # vx, vy, vz, height
    spin = np.zeros((n_bbip, n_mc, 4))  # sx, sy, sz, spin_rate (per draw)
    for b, bbip in enumerate(bbips):
        rng = np.random.default_rng(_seed_for_bbip(bbip.bbip_key, seed_offset))
        vx, vy, vz, height, ev_mph, la_deg, spray_deg = _bbip_velocities(bbip, n_mc, rng)
        vel[b, :, 0] = vx
        vel[b, :, 1] = vy
        vel[b, :, 2] = vz
        vel[b, :, 3] = height
        rate_rpm, tilt_deg = batted_ball_spin(ev_mph, la_deg, spray_deg, calib.spin)
        a = np.asarray(tilt_deg) * DEG_TO_RAD
        spin[b, :, 1] = np.cos(a)
        spin[b, :, 2] = np.sin(a)
        spin[b, :, 3] = np.asarray(rate_rpm) * RPM_TO_RAD_S

    # Assemble (n_bbip, n_parks, n_mc, TRAJ_IN_COLS) via broadcasting, then flatten.
    traj = np.zeros((n_bbip, n_parks, n_mc, TRAJ_IN_COLS), dtype=np.float32)
    traj[..., 0] = vel[:, None, :, 0]
    traj[..., 1] = vel[:, None, :, 1]
    traj[..., 2] = vel[:, None, :, 2]
    traj[..., 3] = vel[:, None, :, 3]
    traj[..., 4] = spin[:, None, :, 0]
    traj[..., 5] = spin[:, None, :, 1]
    traj[..., 6] = spin[:, None, :, 2]
    traj[..., 7] = spin[:, None, :, 3]
    traj[..., 8] = rho_mat[:, :, None]
    traj[..., 9] = wind_x[:, None, None]
    traj[..., 10] = wind_y[:, None, None]
    # column 11 (wind_z) stays 0.0

    park_idx_grid = np.broadcast_to(
        np.arange(n_parks, dtype=np.int32)[None, :, None], (n_bbip, n_parks, n_mc)
    )

    codes = simulate_classify_batch(
        traj.reshape(-1, TRAJ_IN_COLS),
        np.ascontiguousarray(park_idx_grid).reshape(-1),
        fence_angle,
        fence_dist,
        fence_height,
        fence_n,
        device=device,
        cd_scale=calib.cd_scale,
    ).reshape(n_bbip, n_parks, n_mc)

    total = float(n_mc)
    results: list[RetrodictionResult] = []
    for b, bbip in enumerate(bbips):
        for pk, pid in enumerate(park_ids):
            sl = codes[b, pk]
            is_home = pid == bbip.home_park_id
            observed = event_to_outcome(bbip.observed_event) if is_home else None
            results.append(
                RetrodictionResult(
                    bbip=bbip,
                    park_id=pid,
                    is_home_park=is_home,
                    prob_out=int(np.count_nonzero(sl == OUT_CODE)) / total,
                    prob_1b=int(np.count_nonzero(sl == SINGLE_CODE)) / total,
                    prob_2b=int(np.count_nonzero(sl == DOUBLE_CODE)) / total,
                    prob_3b=int(np.count_nonzero(sl == TRIPLE_CODE)) / total,
                    prob_hr=int(np.count_nonzero(sl == HR_CODE)) / total,
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
    "retrodict_bips_batch",
    "retrodict_one",
)
