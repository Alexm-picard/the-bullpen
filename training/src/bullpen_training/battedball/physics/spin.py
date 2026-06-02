"""Batted-ball spin model (Phase 1 physics overhaul).

Replaces the flat 1800 rpm backspin prior with a physics-derived
spin = f(EV, LA, spray) whose coefficients are calibrated to weather-corrected
Statcast carry (``scripts/calibrate_spin.py``). Statcast doesn't measure
batted-ball spin, so the *form* is grounded in batted-ball physics
(Nathan/Kagan) and the *coefficients* are fit so SIMULATED carry matches
OBSERVED carry under real weather — the non-circular "both" path the overhaul
chose (physics structure + Statcast calibration).

Why this is needed: Phase 0 showed that under real weather the simulator
over-predicts HR carry by ~+21 ft, because the 1800 rpm prior was reverse-tuned
(decision [131], 2200->1800) to make the *no-wind* sim fake the missing
tailwind. With real weather wired in, that boost double-counts. A calibrated,
launch-dependent spin model removes the bias (magnitude) and tightens the MAE
spread (LA + spray dependence).

Form (EV mph, LA/spray/tilt deg, spin rpm); the sim consumes (rate, tilt):
    backspin = b0 + b1*(EV-100) + b2*LA + b3*LA^2        [clamped 500..3500]
    tilt     = 180 + k_side*spray                         [clamped 120..240]
Backspin scales with EV and follows a launch-angle curve; the spin axis tilts
off pure backspin (180 deg) with spray, so pulled balls trade some lift for
sidespin/hook and carry slightly less. ``k_side``'s sign/magnitude is left to
the calibration.

``DEFAULT_COEFFS`` reproduce the legacy flat-1800 backspin (all slopes 0), so
wiring the model into retrodiction/fixtures is a **no-op until calibrated
coefficients are loaded** via ``load_spin_coeffs`` — keeping labels stable until
the desktop calibration lands.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np

_BACKSPIN_MIN, _BACKSPIN_MAX = 500.0, 3500.0
_TILT_MIN, _TILT_MAX = 120.0, 240.0
_SCHEMA_VERSION = 1


@dataclass(frozen=True)
class SpinCoeffs:
    """Fittable coefficients for the batted-ball spin model.

    Defaults reproduce the legacy flat 1800 rpm pure-backspin prior.
    """

    b0: float = 1800.0
    b1_ev: float = 0.0
    b2_la: float = 0.0
    b3_la2: float = 0.0
    k_side: float = 0.0

    def to_dict(self) -> dict[str, float | int]:
        return {
            "schema_version": _SCHEMA_VERSION,
            "b0": self.b0,
            "b1_ev": self.b1_ev,
            "b2_la": self.b2_la,
            "b3_la2": self.b3_la2,
            "k_side": self.k_side,
        }

    @classmethod
    def from_dict(cls, d: dict[str, float]) -> SpinCoeffs:
        if int(d.get("schema_version", _SCHEMA_VERSION)) != _SCHEMA_VERSION:
            raise ValueError(f"unknown spin-model schema_version: {d.get('schema_version')}")
        return cls(
            b0=float(d["b0"]),
            b1_ev=float(d["b1_ev"]),
            b2_la=float(d["b2_la"]),
            b3_la2=float(d["b3_la2"]),
            k_side=float(d["k_side"]),
        )

    def as_vector(self) -> np.ndarray:
        """(5,) param vector for the calibration optimiser, in a fixed order."""
        return np.array(
            [self.b0, self.b1_ev, self.b2_la, self.b3_la2, self.k_side], dtype=np.float64
        )

    @classmethod
    def from_vector(cls, v: np.ndarray) -> SpinCoeffs:
        return cls(
            b0=float(v[0]),
            b1_ev=float(v[1]),
            b2_la=float(v[2]),
            b3_la2=float(v[3]),
            k_side=float(v[4]),
        )


DEFAULT_COEFFS = SpinCoeffs()

# Physics-prior spin coefficients (NOT fit to carry — HR carry can't identify
# spin separately from drag; both scale carry). Sourced from batted-ball physics
# (Nathan/Kagan) + the fixtures' literature buckets: backspin rises with launch
# angle, peaking ~35 deg with a mild falloff, plus a small EV term and modest
# spray-driven sidespin. The quadratic was fit through (LA,bs) = (10,1500),
# (25,2000), (40,2150) rpm; b1_ev ~ +5 rpm/mph; k_side modest. The Statcast-
# calibrated knob is the drag scale (cd_scale), not these.
PHYSICS_PRIOR_COEFFS = SpinCoeffs(
    b0=973.0,
    b1_ev=5.0,
    b2_la=60.5,
    b3_la2=-0.78,
    k_side=0.4,
)


def batted_ball_spin(
    ev_mph: float | np.ndarray,
    la_deg: float | np.ndarray,
    spray_deg: float | np.ndarray,
    coeffs: SpinCoeffs = DEFAULT_COEFFS,
) -> tuple[float | np.ndarray, float | np.ndarray]:
    """Return ``(spin_rate_rpm, spin_axis_tilt_deg)`` for a batted ball.

    Scalar or vectorised (numpy) over EV/LA/spray — the retrodiction passes
    arrays, the fixtures + tests pass scalars. Clamped to physical ranges.
    """
    backspin = (
        coeffs.b0
        + coeffs.b1_ev * (np.asarray(ev_mph) - 100.0)
        + coeffs.b2_la * np.asarray(la_deg)
        + coeffs.b3_la2 * np.asarray(la_deg) ** 2
    )
    backspin = np.clip(backspin, _BACKSPIN_MIN, _BACKSPIN_MAX)
    tilt = np.clip(180.0 + coeffs.k_side * np.asarray(spray_deg), _TILT_MIN, _TILT_MAX)
    if np.isscalar(ev_mph) and np.isscalar(la_deg) and np.isscalar(spray_deg):
        return float(backspin), float(tilt)
    return backspin, tilt


def load_spin_coeffs(path: Path | str | None) -> SpinCoeffs:
    """Load calibrated coefficients, or ``DEFAULT_COEFFS`` if absent.

    The default-on-absence behaviour is deliberate: code can wire the spin model
    in unconditionally, and it stays a no-op (legacy 1800 rpm backspin) until the
    desktop calibration drops a ``spin_model.json`` in place.
    """
    if path is None:
        return DEFAULT_COEFFS
    p = Path(path)
    if not p.exists():
        return DEFAULT_COEFFS
    return SpinCoeffs.from_dict(json.loads(p.read_text()))


@dataclass(frozen=True)
class PhysicsCalibration:
    """The calibrated physics knobs: spin model + global drag (CD) scale.

    Phase 1 jointly fits both — drag absorbs the systematic carry bias (its
    physically-correct owner; the Nathan CD curve has digitisation slack), spin
    stays physical and captures the EV/LA/spray variance. Defaults reproduce the
    legacy physics (flat 1800 rpm, raw CD) so wiring it in is a no-op until the
    desktop calibration writes a file.
    """

    spin: SpinCoeffs = DEFAULT_COEFFS
    cd_scale: float = 1.0

    def to_dict(self) -> dict[str, object]:
        spin_d = {k: v for k, v in self.spin.to_dict().items() if k != "schema_version"}
        return {"schema_version": _SCHEMA_VERSION, "cd_scale": self.cd_scale, "spin": spin_d}

    @classmethod
    def from_dict(cls, d: dict[str, object]) -> PhysicsCalibration:
        if int(d.get("schema_version", _SCHEMA_VERSION)) != _SCHEMA_VERSION:  # type: ignore[arg-type]
            raise ValueError(
                f"unknown physics-calibration schema_version: {d.get('schema_version')}"
            )
        spin_raw = d["spin"]
        if not isinstance(spin_raw, dict):
            raise ValueError("physics-calibration 'spin' must be an object")
        spin_d: dict[str, object] = {**spin_raw, "schema_version": _SCHEMA_VERSION}
        return cls(
            spin=SpinCoeffs.from_dict(spin_d),  # type: ignore[arg-type]
            cd_scale=float(d.get("cd_scale", 1.0)),  # type: ignore[arg-type]
        )


DEFAULT_CALIBRATION = PhysicsCalibration()


def load_physics_calibration(path: Path | str | None) -> PhysicsCalibration:
    """Load the joint spin+drag calibration, or the legacy-physics default if absent."""
    if path is None:
        return DEFAULT_CALIBRATION
    p = Path(path)
    if not p.exists():
        return DEFAULT_CALIBRATION
    return PhysicsCalibration.from_dict(json.loads(p.read_text()))


__all__ = (
    "DEFAULT_CALIBRATION",
    "DEFAULT_COEFFS",
    "PHYSICS_PRIOR_COEFFS",
    "PhysicsCalibration",
    "SpinCoeffs",
    "batted_ball_spin",
    "load_physics_calibration",
    "load_spin_coeffs",
)
