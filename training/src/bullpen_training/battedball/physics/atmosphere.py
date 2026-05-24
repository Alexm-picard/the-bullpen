"""Air-density model for the ball-flight simulator (Phase 2c.1).

Why this matters: ball flight is dominated by drag at typical batted-ball
speeds (30-50 m/s), and drag is linear in air density. A Denver day at
2 % humidity has ~17 % less density than a sea-level standard day; that
translates to ~25-30 ft of extra carry on a deep fly. The retrodiction
pipeline (2c.4) feeds this same model with per-game atmosphere data so
the simulator's predictions are park + weather realistic.

Implementation: ideal-gas density with a partial-pressure water-vapor
correction (Buck 1981 saturation curve). Vectorised over numpy inputs
for the batch simulator. All inputs in plain meteorological units;
internal math is SI.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import cast

import numpy as np

from bullpen_training.battedball.physics._constants import (
    DRY_AIR_R_J_KG_K,
    SEA_LEVEL_DENSITY_KG_M3,
    SEA_LEVEL_PRESSURE_PA,
    SEA_LEVEL_TEMP_K,
    WATER_VAPOR_R_J_KG_K,
)


@dataclass(frozen=True)
class Atmosphere:
    """Per-game atmospheric conditions.

    Parameters
    ----------
    temp_c : Celsius. -10..45 covers MLB game-time range.
    pressure_hpa : Station pressure (hPa = mbar). Typically 980-1030
        at sea level; lower at altitude. ``None`` = ISA-standard pressure
        adjusted for ``altitude_m``.
    altitude_m : Park elevation above sea level (m). Coors Field ≈ 1580 m.
    humidity_pct : Relative humidity 0-100.
    wind_x_m_s, wind_y_m_s, wind_z_m_s : Wind velocity vector in stadium
        coordinates (x = toward CF, y = toward 3B, z = up). Velocity OF the
        air, NOT direction it's coming from — convert at the API boundary.
    """

    temp_c: float = 20.0
    pressure_hpa: float | None = None  # None → ISA standard at altitude
    altitude_m: float = 0.0
    humidity_pct: float = 50.0
    wind_x_m_s: float = 0.0
    wind_y_m_s: float = 0.0
    wind_z_m_s: float = 0.0

    @property
    def density(self) -> float:
        """Computed air density at these conditions (kg/m³)."""
        return float(
            air_density(self.temp_c, self.pressure_hpa, self.altitude_m, self.humidity_pct)
        )

    @property
    def wind_vec_m_s(self) -> np.ndarray:
        """Wind velocity as a (3,) numpy array for the integrator."""
        return np.array([self.wind_x_m_s, self.wind_y_m_s, self.wind_z_m_s], dtype=np.float64)


def _saturation_vapor_pressure_pa(temp_c: float | np.ndarray) -> float | np.ndarray:
    """Buck 1981 equation for saturation vapor pressure of water (Pa).

    e_s(T) = 611.21 · exp((18.678 - T/234.5) · (T / (257.14 + T)))
    where T is Celsius. Accurate to <0.5 % over -40..50 °C.
    """
    t = np.asarray(temp_c, dtype=np.float64)
    return cast(np.ndarray, 611.21 * np.exp((18.678 - t / 234.5) * (t / (257.14 + t))))


def _isa_pressure_pa(altitude_m: float | np.ndarray) -> float | np.ndarray:
    """ISA standard pressure (Pa) at altitude, troposphere model.

    Used when the caller doesn't supply a measured station pressure.
    Lapse rate 0.0065 K/m up to 11 km — fine for all MLB parks.
    """
    h = np.asarray(altitude_m, dtype=np.float64)
    lapse = 0.0065
    t0 = SEA_LEVEL_TEMP_K
    exponent = 5.25588  # = g·M/(R·L)
    return cast(np.ndarray, SEA_LEVEL_PRESSURE_PA * (1.0 - lapse * h / t0) ** exponent)


def air_density(
    temp_c: float | np.ndarray,
    pressure_hpa: float | np.ndarray | None,
    altitude_m: float | np.ndarray,
    humidity_pct: float | np.ndarray,
) -> float | np.ndarray:
    """Compute moist-air density (kg/m³) from common meteorological inputs.

    rho = (p_d / R_d + p_v / R_v) / T
    where p_d = total pressure - vapor pressure, p_v = humidity · e_sat.

    Pressure: if ``pressure_hpa`` is None, fall back to ISA at altitude.

    Validation: sea-level standard atmosphere (15 °C, 1013.25 hPa, dry)
    should return ≈ 1.225 kg/m³ within 0.5 %. Test in test_equations.py.
    """
    t_kelvin = np.asarray(temp_c, dtype=np.float64) + 273.15
    if pressure_hpa is None:
        p_total = _isa_pressure_pa(altitude_m)
    else:
        p_total = np.asarray(pressure_hpa, dtype=np.float64) * 100.0  # hPa → Pa
    p_sat = _saturation_vapor_pressure_pa(temp_c)
    p_vapor = (np.asarray(humidity_pct, dtype=np.float64) / 100.0) * p_sat
    p_dry = p_total - p_vapor
    rho = p_dry / (DRY_AIR_R_J_KG_K * t_kelvin) + p_vapor / (WATER_VAPOR_R_J_KG_K * t_kelvin)
    return cast(np.ndarray, rho)


# Sanity reference for tests + docs.
STANDARD_DENSITY: float = SEA_LEVEL_DENSITY_KG_M3


__all__ = ("STANDARD_DENSITY", "Atmosphere", "air_density")
