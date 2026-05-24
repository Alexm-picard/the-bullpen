"""Park geometry (Phase 2c.3) — per-park fence polylines + outcome classifier.

Public surface:

- :class:`ParkGeometry` / :func:`load_park_geometry` — load one park JSON
  from ``infra/park_geometry/<park_id>.json`` and use it.
- :func:`load_all_parks` — load all 30 park files at once.
- :func:`classify_outcome` — turn a Trajectory + park into a 5-class
  batted-ball outcome (out / single / double / triple / home_run).

The JSON files live under ``infra/park_geometry/`` so they're shared
between training and the (future) Java retrodiction backfill — the
boundary is the same file-based contract the rest of the project uses
between Python and Java.
"""

from __future__ import annotations

from bullpen_training.battedball.parks._classify import Outcome, classify_outcome
from bullpen_training.battedball.parks.loader import (
    FencePoint,
    ParkGeometry,
    fence_height_at_spray_deg,
    load_all_parks,
    load_park_geometry,
)

__all__ = (
    "FencePoint",
    "Outcome",
    "ParkGeometry",
    "classify_outcome",
    "fence_height_at_spray_deg",
    "load_all_parks",
    "load_park_geometry",
)
