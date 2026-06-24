"""Fused integrate+classify kernel for the retrodiction pipeline (GPU-B).

The retrodiction pipeline (``retrodict/labels.py``) runs ~375M independent
ball-flight trajectories (1.25M BIPs x 30 parks x N_MC) and then classifies each
into a 5-class outcome. The reference path materialises a full ``(N, n_steps+1, 6)``
trajectory history (``_jit.integrate_batch``) and then walks it in pure Python
(``parks._classify.classify_outcome``) — two costs that dominate the ~6 h phase.

This module fuses both into a single per-trajectory kernel that keeps the 6-dim
state in registers, computes the classification-relevant summaries *inline*
during the forward integration, and emits **one int8 outcome code per
trajectory** — no history array, no Python per-trajectory loop. That turns a
memory-bound + Python-bound problem into a compute-bound one suited to the GPU.

Two compiled targets share one source:

- **CPU** (``simulate_classify_cpu``): ``@njit(parallel=True)`` over ``prange`` —
  the macOS-dev fallback (ADR-0006: no CUDA on the dev box) and the parity oracle.
- **GPU** (``simulate_classify_gpu``): ``@cuda.jit`` kernel, one thread per
  trajectory — the production path on the desktop GPU.

``simulate_classify_batch(..., device=...)`` dispatches between them, mirroring
the ``_select_device`` pattern in ``mlp/train.py``.

Precision: inputs are float32 (decision: GPU-B runs float32; consumer GPUs cripple
float64). The classify branch logic is a direct port of ``classify_outcome``; the
**one intentional deviation** is that the fence-crossing height is captured in the
single forward pass using the ball's spray *at the crossing step* (the reference
re-walks the stored path using the *landing* spray). For near-radial flight these
agree to a fraction of a degree / ~1 ft — far inside the +/-25 ft calibration gate
(decision [131]) and the 10-sample MC noise. ``test_fused_parity.py`` pins the
agreement rate; the calibration gate is re-validated on the desktop before any
full relabel.

RNG note: Monte-Carlo jitter is generated host-side (numpy, seeded per-BIP exactly
as today — see ``labels._seed_for_bbip``) and passed in as jittered initial
velocities. So CPU vs GPU differ *only* by float32+fastmath rounding, never by
RNG, preserving the seeded ReplacingMergeTree idempotency story.
"""

from __future__ import annotations

import math
from typing import Final

import numpy as np
from numba import cuda, njit, prange

from bullpen_training.battedball.parks._classify import (
    _FOUL_LINE_DEG,
    DEFAULT_HR_MIN_DIST_PAST_FENCE_FT,
    DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT,
    DEFAULT_SPRINT_SPEED_FPS,
    DEFAULT_WALL_HANG_CUTOFF_S,
)
from bullpen_training.battedball.physics._constants import (
    _LIFT_A,
    _LIFT_B,
    _LIFT_NUM,
    BALL_AREA_M2,
    BALL_MASS_KG,
    BALL_RADIUS_M,
    G_M_S2,
    M_TO_FT,
)

# --- outcome codes (kernel emits these; labels.py maps to the Outcome enum) ---
OUT_CODE: Final[int] = 0
SINGLE_CODE: Final[int] = 1
DOUBLE_CODE: Final[int] = 2
TRIPLE_CODE: Final[int] = 3
HR_CODE: Final[int] = 4

# Number of float columns in the per-trajectory input matrix `traj_in`.
# Layout (column index): 0 vx0, 1 vy0, 2 vz0, 3 pz0(initial height, m),
# 4 spin_x, 5 spin_y, 6 spin_z, 7 spin_rate(rad/s), 8 rho(kg/m^3),
# 9 wind_x, 10 wind_y, 11 wind_z  (all m/s except rho).
TRAJ_IN_COLS: Final[int] = 12

# Classify thresholds, bound to module-level constants so the JITs bake them in.
_HR_MIN_DIST: Final[float] = DEFAULT_HR_MIN_DIST_PAST_FENCE_FT
_HR_MIN_HEIGHT: Final[float] = DEFAULT_HR_MIN_HEIGHT_OVER_FENCE_FT
_WALL_HANG: Final[float] = DEFAULT_WALL_HANG_CUTOFF_S
_SPRINT_SPEED: Final[float] = DEFAULT_SPRINT_SPEED_FPS
_FOUL_DEG: Final[float] = _FOUL_LINE_DEG
_RAD_TO_DEG: Final[float] = 180.0 / math.pi
_FT_PER_M: Final[float] = M_TO_FT


def _build_core(jit):  # - jit is a numba decorator factory
    """Compile the fused integrate+classify core for one target.

    ``jit`` is either ``njit(...)`` (CPU) or ``cuda.jit(device=True, ...)`` (GPU).
    Everything the core needs is defined inside this closure so the GPU build
    never references a CPU-jitted helper (and vice versa) — one source, two
    independent compiled chains. Returns the compiled ``core`` device function.
    """

    @jit
    def cd_interp(speed):
        # Nathan 2008 5-point CD table, hardcoded branches (mirrors _jit._cd_interp).
        if speed <= 25.0:
            return 0.50
        if speed <= 35.0:
            return 0.50 + (speed - 25.0) * (-0.10 / 10.0)
        if speed <= 45.0:
            return 0.40 + (speed - 35.0) * (-0.08 / 10.0)
        if speed <= 55.0:
            return 0.32 + (speed - 45.0) * (-0.02 / 10.0)
        return 0.30

    @jit
    def accel(vx, vy, vz, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_scale):
        # Drag + Magnus + gravity, scalar (mirrors _jit._accel_scalar). Returns
        # (ax, ay, az). Position-independent, so the integrator passes velocity only.
        # cd_scale is the calibrated global drag multiplier (1.0 = raw CD).
        rx = vx - wx
        ry = vy - wy
        rz = vz - wz
        speed_sq = rx * rx + ry * ry + rz * rz
        if speed_sq < 1e-18:
            return 0.0, 0.0, -G_M_S2
        speed = math.sqrt(speed_sq)

        cd = cd_interp(speed) * cd_scale
        drag_coef = -0.5 * rho * cd * BALL_AREA_M2 * speed / BALL_MASS_KG
        a_drag_x = drag_coef * rx
        a_drag_y = drag_coef * ry
        a_drag_z = drag_coef * rz

        s_param = math.fabs(spin_rate) * BALL_RADIUS_M / speed
        cl = _LIFT_NUM * s_param / (_LIFT_A + _LIFT_B * s_param + 1e-12)
        inv_speed = 1.0 / speed
        vhx = rx * inv_speed
        vhy = ry * inv_speed
        vhz = rz * inv_speed
        cx = sy * vhz - sz * vhy
        cy = sz * vhx - sx * vhz
        cz = sx * vhy - sy * vhx
        cross_mag = math.sqrt(cx * cx + cy * cy + cz * cz)
        if cross_mag > 1e-9:
            m_coef = 0.5 * rho * cl * BALL_AREA_M2 * speed_sq / BALL_MASS_KG
            inv_cross = 1.0 / cross_mag
            a_mag_x = m_coef * cx * inv_cross
            a_mag_y = m_coef * cy * inv_cross
            a_mag_z = m_coef * cz * inv_cross
        else:
            a_mag_x = 0.0
            a_mag_y = 0.0
            a_mag_z = 0.0
        return (a_drag_x + a_mag_x, a_drag_y + a_mag_y, a_drag_z + a_mag_z - G_M_S2)

    @jit
    def interp_fence(values, angles, fence_n, p, spray_deg):
        # Linear interp of a per-park polyline value at a spray angle (deg),
        # clamped at the endpoints. Mirrors fence_distance/height_at_spray_deg.
        n = fence_n[p]
        if spray_deg <= angles[p, 0]:
            return values[p, 0]
        if spray_deg >= angles[p, n - 1]:
            return values[p, n - 1]
        for k in range(1, n):
            if spray_deg <= angles[p, k]:
                a0 = angles[p, k - 1]
                a1 = angles[p, k]
                v0 = values[p, k - 1]
                v1 = values[p, k]
                frac = (spray_deg - a0) / (a1 - a0)
                return v0 + frac * (v1 - v0)
        return values[p, n - 1]

    @jit
    def core(
        traj_in,
        i,
        park_idx,
        dt,
        n_steps_max,
        fence_angle,
        fence_dist,
        fence_height,
        fence_n,
        cd_scale,
    ):
        vx = traj_in[i, 0]
        vy = traj_in[i, 1]
        vz = traj_in[i, 2]
        px = 0.0
        py = 0.0
        pz = traj_in[i, 3]
        sx = traj_in[i, 4]
        sy = traj_in[i, 5]
        sz = traj_in[i, 6]
        spin_rate = traj_in[i, 7]
        rho = traj_in[i, 8]
        wx = traj_in[i, 9]
        wy = traj_in[i, 10]
        wz = traj_in[i, 11]
        p = park_idx[i]

        # Fence-crossing capture (single forward pass, crossing-point spray).
        has_zfence = 0
        z_at_fence = 0.0

        landed = 0
        landing_dist_ft = 0.0
        spray_deg = 0.0
        hang_time = 0.0

        for step in range(1, n_steps_max + 1):
            prev_px = px
            prev_py = py
            prev_pz = pz

            a1x, a1y, a1z = accel(vx, vy, vz, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_scale)
            vx2 = vx + 0.5 * dt * a1x
            vy2 = vy + 0.5 * dt * a1y
            vz2 = vz + 0.5 * dt * a1z
            a2x, a2y, a2z = accel(vx2, vy2, vz2, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_scale)
            vx3 = vx + 0.5 * dt * a2x
            vy3 = vy + 0.5 * dt * a2y
            vz3 = vz + 0.5 * dt * a2z
            a3x, a3y, a3z = accel(vx3, vy3, vz3, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_scale)
            vx4 = vx + dt * a3x
            vy4 = vy + dt * a3y
            vz4 = vz + dt * a3z
            a4x, a4y, a4z = accel(vx4, vy4, vz4, wx, wy, wz, sx, sy, sz, spin_rate, rho, cd_scale)

            sixth = dt / 6.0
            px_n = px + sixth * (vx + 2.0 * vx2 + 2.0 * vx3 + vx4)
            py_n = py + sixth * (vy + 2.0 * vy2 + 2.0 * vy3 + vy4)
            pz_n = pz + sixth * (vz + 2.0 * vz2 + 2.0 * vz3 + vz4)
            vx_n = vx + sixth * (a1x + 2.0 * a2x + 2.0 * a3x + a4x)
            vy_n = vy + sixth * (a1y + 2.0 * a2y + 2.0 * a3y + a4y)
            vz_n = vz + sixth * (a1z + 2.0 * a2z + 2.0 * a3z + a4z)

            # Fence-radius crossing on the way out (z > 0), captured once.
            if has_zfence == 0 and pz_n > 0.0:
                r_n = math.sqrt(px_n * px_n + py_n * py_n)
                cur_spray = math.atan2(py_n, px_n) * _RAD_TO_DEG if px_n > 0.0 else 0.0
                fence_m = interp_fence(fence_dist, fence_angle, fence_n, p, cur_spray) / _FT_PER_M
                if r_n >= fence_m:
                    prev_r = math.sqrt(prev_px * prev_px + prev_py * prev_py)
                    if r_n == prev_r:
                        z_at_fence = pz_n * _FT_PER_M
                    else:
                        frac = (fence_m - prev_r) / (r_n - prev_r)
                        z_at_fence = (prev_pz + frac * (pz_n - prev_pz)) * _FT_PER_M
                    has_zfence = 1

            # Landing: z crosses 0 from above. Back-interpolate to z=0.
            if pz_n <= 0.0 and prev_pz > 0.0:
                z0 = prev_pz
                z1 = pz_n
                lf = z0 / (z0 - z1)
                land_x = prev_px + lf * (px_n - prev_px)
                land_y = prev_py + lf * (py_n - prev_py)
                x_ft = land_x * _FT_PER_M
                y_ft = land_y * _FT_PER_M
                landing_dist_ft = math.sqrt(x_ft * x_ft + y_ft * y_ft)
                spray_deg = math.atan2(y_ft, x_ft) * _RAD_TO_DEG if x_ft > 0.0 else 0.0
                hang_time = (step - 1) * dt + lf * dt
                landed = 1
                break

            px = px_n
            py = py_n
            pz = pz_n
            vx = vx_n
            vy = vy_n
            vz = vz_n

        # --- classify (port of parks._classify.classify_outcome) -------------
        # Returns (outcome_code, carry_ft). carry_ft is the XY ground landing distance (ft);
        # it is 0.0 when the trajectory never landed (landed == 0), so the per-(BIP,park)
        # reduction in labels.py means over draws with carry > 0 (i.e. that landed). A foul
        # landing still carries a real distance; it is rare jitter on an already-fair BIP, so
        # it is included rather than special-cased.
        if landed == 0:
            return OUT_CODE, landing_dist_ft
        if math.fabs(spray_deg) > _FOUL_DEG:
            return OUT_CODE, landing_dist_ft

        fence_dist_ft = interp_fence(fence_dist, fence_angle, fence_n, p, spray_deg)
        fence_h_ft = interp_fence(fence_height, fence_angle, fence_n, p, spray_deg)
        if landing_dist_ft >= fence_dist_ft:
            if (
                has_zfence == 1
                and z_at_fence > fence_h_ft + _HR_MIN_HEIGHT
                and landing_dist_ft >= fence_dist_ft + _HR_MIN_DIST
            ):
                return HR_CODE, landing_dist_ft
            if has_zfence == 1 and z_at_fence > fence_h_ft:
                if hang_time >= _WALL_HANG:
                    return OUT_CODE, landing_dist_ft
                return DOUBLE_CODE, landing_dist_ft

        # In-park heuristic. With the default sprint speed (27.0) the triple
        # gate (>=28.0) never fires in retrodiction — kept for fidelity.
        if landing_dist_ft >= 380.0 and hang_time < 5.0 and _SPRINT_SPEED >= 28.0:
            return TRIPLE_CODE, landing_dist_ft
        if hang_time >= 4.0 and landing_dist_ft >= 250.0:
            return OUT_CODE, landing_dist_ft
        if landing_dist_ft >= 320.0:
            return DOUBLE_CODE, landing_dist_ft
        if landing_dist_ft >= 150.0:
            return SINGLE_CODE, landing_dist_ft
        return OUT_CODE, landing_dist_ft

    return core


# --- CPU target -----------------------------------------------------------

_core_cpu = _build_core(njit(fastmath=True))


@njit(parallel=True, fastmath=True)
def _batch_cpu(
    traj_in,
    park_idx,
    dt,
    n_steps_max,
    fence_angle,
    fence_dist,
    fence_height,
    fence_n,
    cd_scale,
    out,
    out_carry,
):
    n = out.shape[0]
    for i in prange(n):
        code, carry = _core_cpu(
            traj_in,
            i,
            park_idx,
            dt,
            n_steps_max,
            fence_angle,
            fence_dist,
            fence_height,
            fence_n,
            cd_scale,
        )
        out[i] = code
        out_carry[i] = carry


# --- GPU target (built only when a CUDA device is present) ----------------
#
# `numba.cuda` always imports (it is part of numba); `cuda.is_available()` is
# False on the macOS dev box, so the kernel is only built/JIT-compiled on the
# desktop GPU (ADR-0006). When it is not built, the dispatcher falls back to the
# CPU path. The kernel body cannot be exercised or type-checked off-GPU, hence
# the `pragma: no cover` + the numba-stub `type: ignore`s below.

_GPU_READY = False
_batch_gpu_kernel = None
if cuda.is_available():  # pragma: no cover - desktop GPU only (ADR-0006)
    _core_gpu = _build_core(cuda.jit(device=True, fastmath=True))

    @cuda.jit
    def _gpu_kernel(
        traj_in,
        park_idx,
        dt,
        n_steps_max,
        fence_angle,
        fence_dist,
        fence_height,
        fence_n,
        cd_scale,
        out,
        out_carry,
    ):
        i = cuda.grid(1)
        if i < out.shape[0]:
            code, carry = _core_gpu(
                traj_in,
                i,
                park_idx,
                dt,
                n_steps_max,
                fence_angle,
                fence_dist,
                fence_height,
                fence_n,
                cd_scale,
            )
            out[i] = code
            out_carry[i] = carry

    _batch_gpu_kernel = _gpu_kernel
    _GPU_READY = True


# --- public API -----------------------------------------------------------


def gpu_available() -> bool:
    """True when a CUDA device was found and the GPU kernel compiled."""
    return _GPU_READY


def simulate_classify_cpu(
    traj_in: np.ndarray,
    park_idx: np.ndarray,
    fence_angle: np.ndarray,
    fence_dist: np.ndarray,
    fence_height: np.ndarray,
    fence_n: np.ndarray,
    *,
    dt: float,
    n_steps_max: int,
    cd_scale: float = 1.0,
) -> tuple[np.ndarray, np.ndarray]:
    """Integrate+classify N trajectories on the CPU (njit/prange).

    Returns ``(codes int8[N], carry_ft float32[N])`` - the outcome code and the XY ground
    landing distance (ft) per trajectory (0.0 for a trajectory that never landed)."""
    out = np.empty(traj_in.shape[0], dtype=np.int8)
    out_carry = np.empty(traj_in.shape[0], dtype=np.float32)
    _batch_cpu(
        traj_in,
        park_idx,
        np.float32(dt),
        n_steps_max,
        fence_angle,
        fence_dist,
        fence_height,
        fence_n,
        np.float32(cd_scale),
        out,
        out_carry,
    )
    return out, out_carry


def simulate_classify_gpu(
    traj_in: np.ndarray,
    park_idx: np.ndarray,
    fence_angle: np.ndarray,
    fence_dist: np.ndarray,
    fence_height: np.ndarray,
    fence_n: np.ndarray,
    *,
    dt: float,
    n_steps_max: int,
    cd_scale: float = 1.0,
    threads_per_block: int = 128,
) -> tuple[np.ndarray, np.ndarray]:  # pragma: no cover - desktop GPU only (ADR-0006)
    """Integrate+classify N trajectories on the GPU.

    Returns ``(codes int8[N], carry_ft float32[N])``. Copies the (small) inputs up and only the
    two result arrays back — the trajectory history never leaves the device (it never
    materialises at all).
    """
    if not _GPU_READY or _batch_gpu_kernel is None:
        raise RuntimeError("GPU path requested but no CUDA device / kernel is available")
    n = traj_in.shape[0]
    d_traj = cuda.to_device(traj_in)
    d_park = cuda.to_device(park_idx)
    d_fa = cuda.to_device(fence_angle)
    d_fd = cuda.to_device(fence_dist)
    d_fh = cuda.to_device(fence_height)
    d_fn = cuda.to_device(fence_n)
    d_out = cuda.device_array(n, dtype=np.int8)  # type: ignore[arg-type]  # numba stub types dtype as float64-only
    d_carry = cuda.device_array(n, dtype=np.float32)  # type: ignore[arg-type]  # numba stub types dtype as float64-only
    blocks = (n + threads_per_block - 1) // threads_per_block
    _batch_gpu_kernel[blocks, threads_per_block](  # type: ignore[index]  # numba cuda kernel launch syntax
        d_traj,
        d_park,
        np.float32(dt),
        n_steps_max,
        d_fa,
        d_fd,
        d_fh,
        d_fn,
        np.float32(cd_scale),
        d_out,
        d_carry,
    )
    return d_out.copy_to_host(), d_carry.copy_to_host()


def simulate_classify_batch(
    traj_in: np.ndarray,
    park_idx: np.ndarray,
    fence_angle: np.ndarray,
    fence_dist: np.ndarray,
    fence_height: np.ndarray,
    fence_n: np.ndarray,
    *,
    dt: float = 0.005,
    n_steps_max: int = 2000,
    cd_scale: float = 1.0,
    device: str = "auto",
) -> tuple[np.ndarray, np.ndarray]:
    """Dispatch the fused integrate+classify over the right target.

    ``device``: ``"auto"`` (GPU if available, else CPU), ``"cuda"`` (force GPU,
    error if unavailable), or ``"cpu"`` (force the njit/prange path — the
    macOS-dev fallback per ADR-0006). ``cd_scale`` is the calibrated global drag
    multiplier (1.0 = raw CD). Returns ``(codes int8[N], carry_ft float32[N])``: the outcome
    code (see ``OUT_CODE``..``HR_CODE``) and the XY ground landing distance in feet per
    trajectory (0.0 when the trajectory never landed).
    """
    traj_in = np.ascontiguousarray(traj_in, dtype=np.float32)
    park_idx = np.ascontiguousarray(park_idx, dtype=np.int32)
    fence_angle = np.ascontiguousarray(fence_angle, dtype=np.float32)
    fence_dist = np.ascontiguousarray(fence_dist, dtype=np.float32)
    fence_height = np.ascontiguousarray(fence_height, dtype=np.float32)
    fence_n = np.ascontiguousarray(fence_n, dtype=np.int32)

    if device == "cpu":
        use_gpu = False
    elif device == "cuda":
        if not _GPU_READY:
            raise RuntimeError("device='cuda' requested but no CUDA device is available")
        use_gpu = True
    else:  # auto
        use_gpu = _GPU_READY

    if use_gpu:
        return simulate_classify_gpu(
            traj_in,
            park_idx,
            fence_angle,
            fence_dist,
            fence_height,
            fence_n,
            dt=dt,
            n_steps_max=n_steps_max,
            cd_scale=cd_scale,
        )
    return simulate_classify_cpu(
        traj_in,
        park_idx,
        fence_angle,
        fence_dist,
        fence_height,
        fence_n,
        dt=dt,
        n_steps_max=n_steps_max,
        cd_scale=cd_scale,
    )


__all__ = (
    "DOUBLE_CODE",
    "HR_CODE",
    "OUT_CODE",
    "SINGLE_CODE",
    "TRAJ_IN_COLS",
    "TRIPLE_CODE",
    "gpu_available",
    "simulate_classify_batch",
    "simulate_classify_cpu",
    "simulate_classify_gpu",
)
