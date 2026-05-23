"""Transform raw_statcast → pitches (Phase 1.2).

Runs the cleaning transform, then a parsed assertions sweep. Idempotent
because `pitches` is `ReplacingMergeTree(ingested_at)` keyed on
`(game_date, game_id, at_bat_index, pitch_number)` — re-runs INSERT the
same keys with a fresh `ingested_at`, and FINAL queries always see the
last-write-wins state. The merge is eventual; OPTIMIZE TABLE forces it
when needed (the wrap-up step calls OPTIMIZE FINAL on the year's
partitions for predictable downstream counts).

Usage:
    uv run python -m bullpen_training.ingest.transform_pitches --year 2024
"""

from __future__ import annotations

import logging
import re
import time
from pathlib import Path
from typing import Any, cast

import click
from clickhouse_driver import Client

from bullpen_training.ingest.assertions import (
    AssertionFailure,
)
from bullpen_training.ingest.clickhouse_client import (
    ClickHouseSettings,
    make_client,
)
from bullpen_training.ingest.migrations import apply_migrations
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

SQL_DIR = Path(__file__).resolve().parent / "sql"

EXPECTED_REGULAR_SEASON_ROWS = 700_000

# Each assertion's name → (expected_value, tolerance_pct OR None for exact 0).
# `regular_season_count` uses the tolerance band; the rest must be exactly 0
# (or, for `unknown_description_excess`, < 100).
_ASSERTION_GATES: dict[str, dict[str, Any]] = {
    "regular_season_count": {
        "kind": "range",
        "expected": EXPECTED_REGULAR_SEASON_ROWS,
        "tol_pct": 5.0,
    },
    "zero_id_rows": {"kind": "max", "max": 0},
    "launch_speed_out_of_range": {"kind": "max", "max": 0},
    "launch_angle_out_of_range": {"kind": "max", "max": 0},
    "unknown_description_excess": {"kind": "max", "max": 99},
    "dedup_consistency": {"kind": "max", "max": 0},
}


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _bind(sql: str, params: dict[str, Any]) -> str:
    """Substitute `:name` placeholders. Plain string substitution is safe here
    because all binds are ints we control (years, counts) — never user input."""
    out = sql
    for k, v in params.items():
        out = re.sub(rf":{re.escape(k)}\b", str(v), out)
    return out


def _parse_assertions(sql: str) -> list[tuple[str, str]]:
    """Split `assertions_pitches.sql` into [(name, sql), ...] pairs."""
    out: list[tuple[str, str]] = []
    current_name: str | None = None
    current_body: list[str] = []
    for raw_line in sql.splitlines():
        marker = re.match(r"^\s*--\s*@name:\s*(\S+)\s*$", raw_line)
        if marker:
            if current_name is not None:
                body = "\n".join(current_body).strip().rstrip(";").strip()
                if body:
                    out.append((current_name, body))
            current_name = marker.group(1)
            current_body = []
            continue
        if raw_line.strip().startswith("--"):
            continue
        current_body.append(raw_line)
    if current_name is not None:
        body = "\n".join(current_body).strip().rstrip(";").strip()
        if body:
            out.append((current_name, body))
    return out


def _scalar(client: Client, sql: str) -> int:
    rows = cast(list[tuple[Any, ...]], client.execute(sql))
    return int(rows[0][0]) if rows else 0


def _evaluate_assertion(name: str, value: int) -> None:
    gate = _ASSERTION_GATES.get(name)
    if gate is None:
        log.warning(
            "unknown assertion (no gate configured); skipping evaluation",
            name=name,
            value=value,
        )
        return
    if gate["kind"] == "max":
        if value > gate["max"]:
            raise AssertionFailure(f"assertion {name} failed: value={value} > max={gate['max']}")
        log.info("assertion passed", name=name, value=value, gate="max", limit=gate["max"])
    elif gate["kind"] == "range":
        expected = int(gate["expected"])
        tol = float(gate["tol_pct"])
        lower = expected * (1 - tol / 100)
        upper = expected * (1 + tol / 100)
        if not (lower <= value <= upper):
            raise AssertionFailure(
                f"assertion {name} out of band: value={value} expected~{expected} (±{tol}%)"
            )
        log.info(
            "assertion passed",
            name=name,
            value=value,
            gate="range",
            expected=expected,
            tol_pct=tol,
        )


def run_assertions(client: Client, year: int) -> dict[str, int]:
    """Execute every assertion against the loaded year and gate each."""
    sql = _read(SQL_DIR / "assertions_pitches.sql")
    results: dict[str, int] = {}
    for name, body in _parse_assertions(sql):
        bound = _bind(body, {"year": year})
        value = _scalar(client, bound)
        results[name] = value
        _evaluate_assertion(name, value)
    return results


def run_transform(
    year: int,
    *,
    settings: ClickHouseSettings | None = None,
) -> dict[str, Any]:
    """Programmatic entrypoint. Returns a summary dict."""
    client = make_client(settings)
    apply_migrations(client)

    transform_sql = _bind(
        _read(SQL_DIR / "transform_raw_to_pitches.sql"),
        {"year": year},
    )
    started = time.time()
    log.info("running cleaning transform", year=year)
    client.execute(transform_sql)
    transform_elapsed = round(time.time() - started, 2)
    log.info("transform complete", year=year, elapsed_s=transform_elapsed)

    # Force the merge so FINAL counts match physical row counts. Cheap on the
    # 700K-row scale; would not be done on a hot path.
    log.info("optimizing pitches partitions for the year")
    optimize_started = time.time()
    client.execute("OPTIMIZE TABLE pitches FINAL")
    log.info("optimize complete", elapsed_s=round(time.time() - optimize_started, 2))

    assertion_results = run_assertions(client, year)

    raw_count = _scalar(
        client,
        f"SELECT count(*) FROM raw_statcast WHERE toYear(game_date) = {year} AND game_type = 'R'",
    )
    clean_count = _scalar(
        client, f"SELECT count(*) FROM pitches FINAL WHERE toYear(game_date) = {year}"
    )
    summary = {
        "year": year,
        "raw_regular_season_rows": raw_count,
        "pitches_final_rows": clean_count,
        "delta": raw_count - clean_count,
        "transform_elapsed_s": transform_elapsed,
        "assertions": assertion_results,
    }
    log.info("done", **summary)
    return summary


@click.command()
@click.option("--year", type=int, required=True, help="Season year to transform.")
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
    show_default=True,
)
def main(year: int, log_format: str) -> None:
    import os

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    run_transform(year)


if __name__ == "__main__":
    main()
