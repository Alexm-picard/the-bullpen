"""Retrodiction pipeline runner (Phase 2c.4).

Streams BIPs from ``pitches`` in chunks, retrodicts each BIP at all 30
parks, and inserts the results into ``bbip_retrodicted_labels``.

Designed to run end-to-end in well under the leaf's 24 h wall-time
budget on the desktop (where multiprocessing has more cores to spread
across) — on the Mac dev box a 1 k-BIP sample completes in well under
a minute, which is the smoke-test cadence the test layer uses.

Usage examples:

  # smoke test: 1000 BIPs from 2024, no DB writes
  uv run python -m bullpen_training.battedball.retrodict.run_pipeline \\
      --season 2024 --limit 1000 --dry-run

  # full 2024 season -> bbip_retrodicted_labels
  uv run python -m bullpen_training.battedball.retrodict.run_pipeline \\
      --season 2024

  # full 2015-2024 backfill (the "overnight on desktop" run)
  uv run python -m bullpen_training.battedball.retrodict.run_pipeline \\
      --season-from 2015 --season-to 2024

Idempotency: V011's ReplacingMergeTree dedupes on the natural key.
The pipeline can be re-run safely; latest run wins.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from collections.abc import Iterator
from pathlib import Path

from bullpen_training.battedball.parks.loader import load_all_parks
from bullpen_training.battedball.retrodict.labels import (
    BBIP,
    DEFAULT_N_MC,
    DEFAULT_SEED_OFFSET,
    RetrodictionResult,
    retrodict_bip_at_all_parks,
)

# Default spin priors when Statcast didn't measure batted-ball spin
# (which is always — Statcast measures release spin, not batted-ball
# spin). The values match the priors used in 2c.2's validation; tuned
# so the simulator's HR carry distance matches Statcast within the gate
# (~85 % within +/- 25 ft, decision [131]).
DEFAULT_SPIN_RATE_RPM: float = 1800.0
DEFAULT_SPIN_AXIS_TILT_DEG: float = 180.0  # backspin around -y axis

_CHUNK_SIZE: int = 1000


def _pitches_query(
    *,
    season_from: int,
    season_to: int,
    limit: int | None,
    offset: int,
) -> str:
    """SQL for streaming a chunk of in-play BIPs from ``pitches``.

    Filters to clean BIPs: launch_speed + launch_angle + hc_x + hc_y +
    a non-error events string. Skips foul outs (events='foul' is not
    in pitches; foul-tip etc. are description filtering not events).
    """
    where = (
        "WHERE description = 'in_play' "
        "AND launch_speed_mph IS NOT NULL "
        "AND launch_angle_deg IS NOT NULL "
        "AND hc_x IS NOT NULL AND hc_y IS NOT NULL "
        "AND events NOT IN ('', 'field_error', 'catcher_interf', 'fan_interference') "
        f"AND toYear(game_date) BETWEEN {season_from} AND {season_to}"
    )
    limit_clause = f"LIMIT {limit} OFFSET {offset}" if limit else ""
    # Cast columns to String for stable TSV output, but alias them under
    # distinct names so the WHERE clause's `toYear(game_date)` still sees
    # the underlying Date column rather than the stringified alias.
    return (
        "SELECT "
        "toString(game_date) AS game_date_str, "
        "toString(game_id) AS game_id_str, "
        "toString(at_bat_index) AS at_bat_index_str, "
        "toString(pitch_number) AS pitch_number_str, "
        "park_id, "
        "toString(launch_speed_mph) AS launch_speed_mph_str, "
        "toString(launch_angle_deg) AS launch_angle_deg_str, "
        "toString(hc_x) AS hc_x_str, "
        "toString(hc_y) AS hc_y_str, "
        "events "
        f"FROM pitches FINAL {where} "
        "ORDER BY game_date, game_id, at_bat_index, pitch_number "
        f"{limit_clause} "
        "FORMAT TSV"
    )


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    """Execute a query in the local Docker ClickHouse and return stdout TSV."""
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _spray_deg_from_hc(hc_x: float, hc_y: float) -> float:
    """Statcast hc_x/hc_y -> simulator spray angle (deg, + toward 3B/LF).

    Same transform used in 2c.2's fixture builder. Constants 125.42 +
    198.27 are the Statcast home-plate coordinates in the scaled
    hc_x/hc_y system.
    """
    import math

    return math.degrees(math.atan2(125.42 - hc_x, 198.27 - hc_y))


def _row_to_bbip(row: list[str]) -> BBIP | None:
    """TSV row -> BBIP. Returns None if the row's events isn't in the
    5-class label space (errors / interference dropped at the query
    level too, but defensive)."""
    try:
        (
            game_date,
            game_id,
            at_bat_index,
            pitch_number,
            park_id,
            launch_speed_mph,
            launch_angle_deg,
            hc_x,
            hc_y,
            events,
        ) = row
    except ValueError:
        return None
    if not park_id:
        return None
    return BBIP(
        game_date=game_date,
        game_id=int(game_id),
        at_bat_index=int(at_bat_index),
        pitch_number=int(pitch_number),
        home_park_id=park_id,
        launch_speed_mph=float(launch_speed_mph),
        launch_angle_deg=float(launch_angle_deg),
        spray_angle_deg=_spray_deg_from_hc(float(hc_x), float(hc_y)),
        spin_rate_rpm=DEFAULT_SPIN_RATE_RPM,
        spin_axis_tilt_deg=DEFAULT_SPIN_AXIS_TILT_DEG,
        observed_event=events,
    )


def stream_bbips(
    *,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    chunk_size: int = _CHUNK_SIZE,
) -> Iterator[list[BBIP]]:
    """Yield successive chunks of BBIPs streamed from ClickHouse.

    ``limit`` caps total rows yielded (useful for the smoke-test
    cadence and CI). When ``limit`` is set, we issue one query bounded
    by that limit rather than paging — keeps tests cheap.
    """
    offset = 0
    total_yielded = 0
    while True:
        page_size = chunk_size
        if limit is not None:
            remaining = limit - total_yielded
            if remaining <= 0:
                return
            page_size = min(chunk_size, remaining)
        tsv = _run_clickhouse(
            _pitches_query(
                season_from=season_from,
                season_to=season_to,
                limit=page_size,
                offset=offset,
            )
        )
        rows = [line.split("\t") for line in tsv.strip().split("\n") if line]
        if not rows:
            return
        bbips = [b for b in (_row_to_bbip(r) for r in rows) if b is not None]
        if bbips:
            yield bbips
            total_yielded += len(bbips)
        offset += page_size
        if len(rows) < page_size:
            return


# --- writer ----------------------------------------------------------------


def _result_to_tsv_row(r: RetrodictionResult) -> str:
    """Render a RetrodictionResult into the TSV ClickHouse expects."""
    observed = r.observed_outcome if r.observed_outcome is not None else "\\N"
    bbip = r.bbip
    return "\t".join(
        (
            bbip.game_date,
            str(bbip.game_id),
            str(bbip.at_bat_index),
            str(bbip.pitch_number),
            r.park_id,
            "1" if r.is_home_park else "0",
            f"{r.prob_out:.4f}",
            f"{r.prob_1b:.4f}",
            f"{r.prob_2b:.4f}",
            f"{r.prob_3b:.4f}",
            f"{r.prob_hr:.4f}",
            observed,
            str(r.n_mc),
        )
    )


_INSERT_COLUMNS: tuple[str, ...] = (
    "game_date",
    "game_id",
    "at_bat_index",
    "pitch_number",
    "park_id",
    "is_home_park",
    "prob_out",
    "prob_1b",
    "prob_2b",
    "prob_3b",
    "prob_hr",
    "observed_outcome",
    "n_mc",
)


def _insert_results(
    results: list[RetrodictionResult], *, container: str = "bullpen-clickhouse"
) -> None:
    """Stream a list of RetrodictionResults into ClickHouse via stdin.

    Explicit column list lets the table's ``ingested_at DEFAULT now()``
    fill in without requiring a TSV value (otherwise ClickHouse expects
    a column for every table column, default or not)."""
    if not results:
        return
    payload = "\n".join(_result_to_tsv_row(r) for r in results)
    cols = ", ".join(_INSERT_COLUMNS)
    cmd = [
        "docker",
        "exec",
        "-i",
        container,
        "clickhouse-client",
        "--query",
        f"INSERT INTO bbip_retrodicted_labels ({cols}) FORMAT TSV",
    ]
    subprocess.run(cmd, input=payload, check=True, text=True)


# --- main orchestration ----------------------------------------------------


def run_pipeline(
    *,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    n_mc: int = DEFAULT_N_MC,
    seed_offset: int = DEFAULT_SEED_OFFSET,
    chunk_size: int = _CHUNK_SIZE,
    dry_run: bool = False,
    container: str = "bullpen-clickhouse",
) -> dict[str, int | float]:
    """Run the retrodiction pipeline end-to-end. Returns summary stats."""
    parks = sorted(load_all_parks().keys())
    n_parks = len(parks)
    total_bips = 0
    total_rows = 0
    t0 = time.perf_counter()
    for chunk_idx, bbips in enumerate(
        stream_bbips(
            season_from=season_from,
            season_to=season_to,
            limit=limit,
            chunk_size=chunk_size,
        )
    ):
        results: list[RetrodictionResult] = []
        for bbip in bbips:
            results.extend(
                retrodict_bip_at_all_parks(bbip, parks, n_mc=n_mc, seed_offset=seed_offset)
            )
        if not dry_run:
            _insert_results(results, container=container)
        total_bips += len(bbips)
        total_rows += len(results)
        elapsed = time.perf_counter() - t0
        rate = total_bips / elapsed if elapsed > 0 else 0.0
        print(
            f"chunk {chunk_idx:>4}: bips={len(bbips):>4} "
            f"running_bips={total_bips:>7} running_rows={total_rows:>8} "
            f"elapsed={elapsed:>6.1f}s rate={rate:>6.1f} bips/s",
            flush=True,
        )

    elapsed_total = time.perf_counter() - t0
    return {
        "n_bbips": total_bips,
        "n_rows": total_rows,
        "n_parks": n_parks,
        "elapsed_sec": elapsed_total,
        "bbips_per_sec": total_bips / elapsed_total if elapsed_total > 0 else 0.0,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the 2c.4 retrodiction pipeline.")
    season = parser.add_mutually_exclusive_group()
    season.add_argument("--season", type=int, help="Single season to run.")
    season.add_argument("--season-from", type=int, help="First season (inclusive).")
    parser.add_argument("--season-to", type=int, help="Last season (inclusive).")
    parser.add_argument("--limit", type=int, default=None, help="Cap on total BIPs.")
    parser.add_argument("--n-mc", type=int, default=DEFAULT_N_MC)
    parser.add_argument("--chunk-size", type=int, default=_CHUNK_SIZE)
    parser.add_argument("--seed-offset", type=int, default=DEFAULT_SEED_OFFSET)
    parser.add_argument("--dry-run", action="store_true", help="Skip the INSERT.")
    parser.add_argument(
        "--report",
        type=Path,
        default=None,
        help="Optional JSON path to write the run summary.",
    )
    args = parser.parse_args()

    if args.season is None and args.season_from is None:
        print("ERROR: pass --season or --season-from", file=sys.stderr)
        sys.exit(2)
    season_from = args.season if args.season is not None else args.season_from
    season_to = args.season if args.season is not None else (args.season_to or args.season_from)

    summary = run_pipeline(
        season_from=season_from,
        season_to=season_to,
        limit=args.limit,
        n_mc=args.n_mc,
        seed_offset=args.seed_offset,
        chunk_size=args.chunk_size,
        dry_run=args.dry_run,
    )
    print()
    print("== summary ==")
    for k, v in summary.items():
        if isinstance(v, float):
            print(f"  {k}: {v:.2f}")
        else:
            print(f"  {k}: {v}")
    if args.report is not None:
        import json

        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(summary, indent=2))
        print(f"wrote summary -> {args.report}")


if __name__ == "__main__":
    main()


__all__ = ("run_pipeline", "stream_bbips")
