"""Backfill ``weather_observed`` from the MLB Stats API (decision [88]).

For every distinct game in ``pitches`` over a season range, pull the game-time
observed weather (:mod:`bullpen_training.ingest.weather`) and insert one row into
the ``weather_observed`` ClickHouse table. The retrodiction pipeline
(``battedball.retrodict.run_pipeline``) then retrodicts each BIP with its actual
wind instead of the per-park seasonal prevailing wind.

Resumable + idempotent: games already present in ``weather_observed`` are skipped,
rows are inserted in batches, and ``weather_observed`` is a ReplacingMergeTree
keyed on ``game_id`` — so a crash-and-resume re-pulls only the unwritten tail and
never double-counts. Runs on the desktop, where the ``bullpen-clickhouse`` Docker
container and the full ``pitches`` history live (ADR-0006).

Usage:

  # smoke: 50 games from 2024, real API, real insert
  uv run python -m bullpen_training.ingest.weather_backfill --season 2024 --limit 50

  # full 2015-2024 backfill (the desktop run before the retrodiction re-run)
  uv run python -m bullpen_training.ingest.weather_backfill \\
      --season-from 2015 --season-to 2024
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

from bullpen_training.ingest.weather import RawWeather, fetch_game_weather
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

_BATCH_SIZE = 200

_INSERT_COLUMNS: tuple[str, ...] = (
    "game_id",
    "game_date",
    "park_id",
    "condition",
    "temp_f",
    "wind_speed_mph",
    "wind_dir_label",
    "is_indoor",
)


@dataclass(frozen=True)
class GameRef:
    """A game to fetch weather for: natural key + partition/park columns."""

    game_id: int
    game_date: str  # ISO YYYY-MM-DD
    park_id: str


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    """Execute a query in the local Docker ClickHouse and return stdout TSV."""
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def distinct_games(
    *, season_from: int, season_to: int, container: str = "bullpen-clickhouse"
) -> list[GameRef]:
    """Every distinct (game_id, game_date, park_id) in ``pitches`` for the range."""
    query = (
        "SELECT DISTINCT "
        "toString(game_id) AS game_id_str, "
        "toString(game_date) AS game_date_str, "
        "park_id "
        "FROM pitches "
        f"WHERE toYear(game_date) BETWEEN {season_from} AND {season_to} "
        "ORDER BY game_id_str "
        "FORMAT TSV"
    )
    tsv = _run_clickhouse(query, container=container)
    games: list[GameRef] = []
    for line in tsv.strip().split("\n"):
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) != 3 or not parts[2]:
            continue
        try:
            games.append(GameRef(game_id=int(parts[0]), game_date=parts[1], park_id=parts[2]))
        except ValueError:
            continue
    return games


def existing_weather_game_ids(
    *, season_from: int, season_to: int, container: str = "bullpen-clickhouse"
) -> set[int]:
    """game_ids already present in ``weather_observed`` (skip set for resume)."""
    query = (
        "SELECT DISTINCT toString(game_id) FROM weather_observed "
        f"WHERE toYear(game_date) BETWEEN {season_from} AND {season_to} FORMAT TSV"
    )
    tsv = _run_clickhouse(query, container=container)
    out: set[int] = set()
    for line in tsv.strip().split("\n"):
        line = line.strip()
        if line:
            try:
                out.add(int(line))
            except ValueError:
                continue
    return out


def _weather_to_tsv_row(ref: GameRef, weather: RawWeather) -> str:
    temp = str(weather.temp_f) if weather.temp_f is not None else "\\N"
    wind_speed = str(weather.wind_speed_mph) if weather.wind_speed_mph is not None else "\\N"
    return "\t".join(
        (
            str(ref.game_id),
            ref.game_date,
            ref.park_id,
            weather.condition,
            temp,
            wind_speed,
            weather.wind_dir_label,
            "1" if weather.is_indoor else "0",
        )
    )


def _insert_weather(rows: list[str], *, container: str = "bullpen-clickhouse") -> None:
    """Stream a batch of TSV rows into ``weather_observed`` (source/ingested_at default)."""
    if not rows:
        return
    cols = ", ".join(_INSERT_COLUMNS)
    subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            container,
            "clickhouse-client",
            "--query",
            f"INSERT INTO weather_observed ({cols}) FORMAT TSV",
        ],
        input="\n".join(rows),
        check=True,
        text=True,
    )


def backfill(
    *,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    batch_size: int = _BATCH_SIZE,
    sleep_s: float = 0.0,
    container: str = "bullpen-clickhouse",
) -> dict[str, int | float]:
    """Fetch + insert observed weather for all uncovered games. Returns stats."""
    games = distinct_games(season_from=season_from, season_to=season_to, container=container)
    if limit is not None:
        games = games[:limit]
    already = existing_weather_game_ids(
        season_from=season_from, season_to=season_to, container=container
    )
    todo = [g for g in games if g.game_id not in already]
    log.info(
        "weather backfill %d-%d: %d games, %d already present, %d to fetch",
        season_from,
        season_to,
        len(games),
        len(games) - len(todo),
        len(todo),
    )

    fetched = 0
    missing = 0
    indoor = 0
    buffer: list[str] = []
    t0 = time.perf_counter()
    for i, ref in enumerate(todo, start=1):
        weather = fetch_game_weather(ref.game_id)
        if weather is None:
            missing += 1
        else:
            if weather.is_indoor:
                indoor += 1
            buffer.append(_weather_to_tsv_row(ref, weather))
            fetched += 1
        if len(buffer) >= batch_size:
            _insert_weather(buffer, container=container)
            buffer.clear()
        if sleep_s > 0:
            time.sleep(sleep_s)
        if i % batch_size == 0 or i == len(todo):
            elapsed = time.perf_counter() - t0
            rate = i / elapsed if elapsed > 0 else 0.0
            log.info(
                "  %d/%d games | fetched=%d missing=%d indoor=%d | %.1f games/s",
                i,
                len(todo),
                fetched,
                missing,
                indoor,
                rate,
            )
    if buffer:
        _insert_weather(buffer, container=container)

    return {
        "n_games_total": len(games),
        "n_already_present": len(games) - len(todo),
        "n_to_fetch": len(todo),
        "n_fetched": fetched,
        "n_missing": missing,
        "n_indoor": indoor,
        "elapsed_sec": time.perf_counter() - t0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Backfill weather_observed from the MLB Stats API."
    )
    season = parser.add_mutually_exclusive_group()
    season.add_argument("--season", type=int, help="Single season to backfill.")
    season.add_argument("--season-from", type=int, help="First season (inclusive).")
    parser.add_argument("--season-to", type=int, help="Last season (inclusive).")
    parser.add_argument("--limit", type=int, default=None, help="Cap on games (smoke runs).")
    parser.add_argument("--batch-size", type=int, default=_BATCH_SIZE)
    parser.add_argument(
        "--sleep",
        type=float,
        default=0.0,
        help="Seconds to sleep between API calls (politeness throttle).",
    )
    parser.add_argument("--container", default="bullpen-clickhouse")
    parser.add_argument(
        "--report", type=Path, default=None, help="Optional JSON path for the run summary."
    )
    args = parser.parse_args()
    configure_logging()

    if args.season is None and args.season_from is None:
        print("ERROR: pass --season or --season-from", file=sys.stderr)
        sys.exit(2)
    season_from = args.season if args.season is not None else args.season_from
    season_to = args.season if args.season is not None else (args.season_to or args.season_from)

    summary = backfill(
        season_from=season_from,
        season_to=season_to,
        limit=args.limit,
        batch_size=args.batch_size,
        sleep_s=args.sleep,
        container=args.container,
    )
    print()
    print("== weather backfill summary ==")
    for k, v in summary.items():
        print(f"  {k}: {v:.2f}" if isinstance(v, float) else f"  {k}: {v}")
    if args.report is not None:
        import json

        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(summary, indent=2))
        print(f"wrote summary -> {args.report}")


if __name__ == "__main__":
    main()


__all__ = ("GameRef", "backfill", "distinct_games", "existing_weather_game_ids")
