"""Per-game observed weather from the MLB Stats API (decision [88], observed leg).

The live feed carries game-time conditions under ``gameData.weather``:

    {"condition": "Partly Cloudy", "temp": "72", "wind": "8 mph, Out To CF"}

We pull one record per ``gamePk`` (== Statcast ``game_pk`` == ``pitches.game_id``)
and normalise it into a :class:`RawWeather`. The wind direction is reported
**field-relative** ("Out To CF" / "In From LF" / "L To R" / ...), which is exactly
the simulator's stadium frame, so the retrodiction read path
(``battedball.retrodict._atmospheres.parse_wind_label``) maps the stored label
straight to a unit vector with no compass math.

MLB Stats API is a hard external boundary — the one place mocks are acceptable per
the project's testing posture. Network use is for non-commercial research only
(Risk Register I7), matching the existing Statcast pull.

Source: https://statsapi.mlb.com/api/v1.1/game/{gamePk}/feed/live
"""

from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from bullpen_training.logging_config import get_logger

log = get_logger(__name__)

_FEED_URL = "https://statsapi.mlb.com/api/v1.1/game/{game_pk}/feed/live"
_USER_AGENT = "thebullpen-weather-backfill/1.0 (research; +https://thebullpen.net)"

# "8 mph, Out To CF" / "12 MPH, In From LF" — speed then field-relative direction.
_SPEED_DIR_RE = re.compile(r"^\s*(\d+)\s*mph\s*,?\s*(.*)$", re.IGNORECASE)

# Canonical field-relative labels (kept tidy for the LowCardinality column; the
# read-side parser tolerates anything, so this is cosmetic, not load-bearing).
_CANON_DIR: dict[str, str] = {
    "out to cf": "Out To CF",
    "in from cf": "In From CF",
    "out to lf": "Out To LF",
    "out to rf": "Out To RF",
    "in from lf": "In From LF",
    "in from rf": "In From RF",
    "l to r": "L To R",
    "r to l": "R To L",
}


@dataclass(frozen=True)
class RawWeather:
    """One game's normalised observed weather, ready for ``weather_observed``."""

    game_pk: int
    condition: str
    temp_f: int | None
    wind_speed_mph: int | None
    wind_dir_label: str
    is_indoor: bool


def _clean_dir(direction: str) -> str:
    """Normalise a wind-direction phrase to a canonical label where possible."""
    key = re.sub(r"\s+", " ", direction.strip().lower())
    key = (
        key.replace("center field", "cf")
        .replace("centerfield", "cf")
        .replace("center", "cf")
        .replace("left field", "lf")
        .replace("leftfield", "lf")
        .replace("right field", "rf")
        .replace("rightfield", "rf")
    )
    return _CANON_DIR.get(key, direction.strip())


def parse_wind(wind_str: str | None) -> tuple[int | None, str, bool]:
    """Parse a feed wind string -> ``(speed_mph, dir_label, is_indoor)``.

    Handles "8 mph, Out To CF", "Calm", "Indoors", "0 mph", "Varies", "" / None.
    ``speed_mph`` is ``None`` only when the feed gave no parseable speed.
    """
    s = (wind_str or "").strip()
    low = s.lower()
    if not s:
        return None, "", False
    if "indoor" in low or low in ("dome", "roof closed"):
        return 0, "Indoors", True
    if low.startswith("calm"):
        return 0, "Calm", False
    match = _SPEED_DIR_RE.match(s)
    if match:
        speed = int(match.group(1))
        direction = match.group(2).strip()
        if speed == 0 or not direction or direction.lower() in ("none", "varies"):
            return speed, ("Calm" if speed == 0 else _clean_dir(direction)), False
        return speed, _clean_dir(direction), False
    # No "mph" prefix — a bare direction phrase or an unknown token.
    return None, _clean_dir(s), False


def _parse_temp(raw: object) -> int | None:
    """Feed temp is a string like "72" (degrees F). Extract the integer."""
    if raw is None:
        return None
    match = re.search(r"-?\d+", str(raw))
    return int(match.group(0)) if match else None


def _http_get_json(
    url: str, *, timeout: float, retries: int, backoff: float
) -> dict[str, object] | None:
    """GET JSON with bounded retries. Returns None on 404 or exhausted retries."""
    last_exc: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
            with urllib.request.urlopen(req, timeout=timeout) as resp:  # (https only)
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return None  # game not found — don't retry
            last_exc = exc
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError) as exc:
            last_exc = exc
        if attempt < retries - 1:
            time.sleep(backoff**attempt)
    log.warning("weather fetch failed for %s after %d attempts: %s", url, retries, last_exc)
    return None


def fetch_game_weather(
    game_pk: int,
    *,
    timeout: float = 20.0,
    retries: int = 3,
    backoff: float = 1.5,
) -> RawWeather | None:
    """Fetch + normalise one game's observed weather. None when unavailable."""
    data = _http_get_json(
        _FEED_URL.format(game_pk=game_pk), timeout=timeout, retries=retries, backoff=backoff
    )
    if data is None:
        return None
    return weather_from_feed(int(game_pk), data)


def weather_from_feed(game_pk: int, feed: dict[str, object]) -> RawWeather | None:
    """Extract a :class:`RawWeather` from a parsed feed/live payload.

    Split out from :func:`fetch_game_weather` so the parsing is unit-testable
    without the network.
    """
    game_data = feed.get("gameData")
    weather = game_data.get("weather") if isinstance(game_data, dict) else None
    if not isinstance(weather, dict) or not weather:
        return None
    condition = str(weather.get("condition") or "").strip()
    temp_f = _parse_temp(weather.get("temp"))
    speed, dir_label, wind_indoor = parse_wind(
        weather.get("wind") if isinstance(weather.get("wind"), str) else None
    )
    cond_low = condition.lower()
    is_indoor = wind_indoor or cond_low in ("dome", "roof closed") or "indoor" in cond_low
    if is_indoor:
        speed = 0
        if not dir_label:
            dir_label = "Indoors"
    # A game with no temp AND no wind signal carries no usable weather.
    if temp_f is None and speed is None and not dir_label and not is_indoor:
        return None
    return RawWeather(
        game_pk=game_pk,
        condition=condition,
        temp_f=temp_f,
        wind_speed_mph=speed,
        wind_dir_label=dir_label,
        is_indoor=is_indoor,
    )


__all__ = (
    "RawWeather",
    "fetch_game_weather",
    "parse_wind",
    "weather_from_feed",
)
