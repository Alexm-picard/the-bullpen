"""Atmosphere lookup for retrodiction (Phase 2c.4).

For each (game_id, park_id) request, return an :class:`Atmosphere`.
v1 uses the park's default atmosphere unconditionally — game-time
``weather_observed`` is a separate ingest (decision [88]) that lands
between this leaf and the model-training leaf 2c.5; once it does, this
module is the single place to enrich with per-game wind / temperature /
humidity. Until then, every retrodiction runs at the park's annual
default (20 C / null pressure -> ISA from altitude / 50 % RH / zero
wind), which matches the simulator's calibration in 2c.2.

The lookup is cached + tiny: 30 entries (one per park). Callers should
treat the returned ``Atmosphere`` as immutable and not mutate it.
"""

from __future__ import annotations

from functools import lru_cache

from bullpen_training.battedball.parks.loader import load_park_geometry
from bullpen_training.battedball.physics.atmosphere import Atmosphere


@lru_cache(maxsize=64)
def get_atmosphere(park_id: str) -> Atmosphere:
    """Return the v1 default Atmosphere for a park (game_id ignored).

    Reads ``default_atmosphere`` from the park's geometry JSON and
    folds it into an :class:`Atmosphere`. game_id is reserved in the
    function signature elsewhere (see :func:`get_atmosphere_for_game`)
    so the weather-aware upgrade in 2c.4.b is a no-touch swap for
    callers — only this lookup changes.
    """
    park = load_park_geometry(park_id)
    return Atmosphere(
        temp_c=park.default_atmosphere.temp_c,
        pressure_hpa=park.default_atmosphere.pressure_hpa,
        altitude_m=park.altitude_m,
        humidity_pct=park.default_atmosphere.humidity_pct,
    )


def get_atmosphere_for_game(park_id: str, game_id: int) -> Atmosphere:
    """Per-(park, game) atmosphere. v1 ignores ``game_id``.

    When ``weather_observed`` lands (decision [88]), this becomes the
    real entry point — it joins the game's row from the weather table
    against the park default and returns a wind+temp-aware Atmosphere.
    """
    _ = game_id  # reserved for the weather upgrade
    return get_atmosphere(park_id)


__all__ = ("get_atmosphere", "get_atmosphere_for_game")
