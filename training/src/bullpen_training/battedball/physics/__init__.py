"""Ball-flight physics for the batted-ball model (Phase 2c.1).

Public surface (Path A physics retrodiction per decision [47]):

- :class:`Trajectory` — output dataclass (full time series + summary stats)
- :class:`LaunchParams` — launch_speed / launch_angle / spray_angle / spin
- :class:`Atmosphere` — temp / pressure / altitude / humidity / wind
- :func:`simulate` — single-trajectory integration
- :func:`simulate_batch` — vectorised N-trajectory integration

Internal modules (``_constants``, ``equations``, ``integrator``, ``atmosphere``,
``simulator``) are stable but considered implementation detail; cross-package
callers should import from this package root.
"""

from bullpen_training.battedball.physics.atmosphere import Atmosphere, air_density
from bullpen_training.battedball.physics.simulator import (
    LaunchParams,
    Trajectory,
    simulate,
    simulate_batch,
)

__all__ = (
    "Atmosphere",
    "LaunchParams",
    "Trajectory",
    "air_density",
    "simulate",
    "simulate_batch",
)
