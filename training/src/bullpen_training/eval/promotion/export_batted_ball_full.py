"""Full-data batted-ball export: ClickHouse -> the ParquetSampleLoader mirror (box side, H2 gate).

The promotion-evidence driver's ``--data-source full`` reads features from a parquet mirror via
``ParquetSampleLoader`` (it has no ClickHouse loader, by ADR-0007: clients read S3-compatible
parquet mirrors, never live CH). The Mac/CI sample mirror is synthesised by
``generate_sample_dataset``; the full-box H2 re-run needs the REAL data in the same on-disk schema.
This module produces it:

    <root>/batted_ball_mlp/year=<YYYY>.parquet   for YYYY in 2015..2025

faithful to how the per-park MLP champion is actually trained
(``battedball.mlp_per_park.dataset``): the SAME in-play filter, the SAME non-null gates, the SAME
home-park join to ``bbip_retrodicted_labels``, and the SAME feature derivation (``hc_to_spray_deg``
+ ``stand_one_hot``). It adds two things the production training loader does not need but the CV
harness does:

* the realized integer ``label`` (0..4, out/1b/2b/3b/hr) - read straight from
  ``bbip_retrodicted_labels.observed_outcome`` (the home-park row carries it; its Enum8 order is
  exactly ``OUTCOME_NAMES``). The harness scores ``predict_proba`` against this; the MLP NEVER
  trains on it (it trains on the ``retro_*`` distribution via KL).
* one ``year=<YYYY>.parquet`` per CV year - the per-year split IS the streaming temporal cutoff for
  the parquet path (the loader reads only the years a fold needs), so no future year is ever
  resident during an earlier split's load.

Leakage posture (rule 10): every column is a SAME-ROW measurement - the four physics features, the
same-row physics ``retro_*`` distribution, the same-row realized ``observed_outcome``. There are NO
rolling / window / lag features here, so future contamination is not even representable; the
per-year partition is the only temporal structure. (Run ``/review-ml`` for the ml-leakage-auditor
pass.)

Rule 13: 2026 is NEVER exported (holdout-only). The export refuses any season >= 2026.

Run on the box (ADR-0006), against the live ClickHouse container that holds the full history:

    uv run python -m bullpen_training.eval.promotion.export_batted_ball_full --root <out>

then point the driver at it:

    uv run python -m bullpen_training.eval.promotion.driver \
        --model batted_ball_mlp --data-source full --no-generate-sample --sample-root <out>
"""

from __future__ import annotations

import logging
import subprocess
from collections.abc import Callable
from io import StringIO
from pathlib import Path
from typing import cast

import numpy as np
import pandas as pd

from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.eval.promotion.sample_loader import (
    BATTED_BALL_FEATURES,
    HOLDOUT_YEAR,
    RETRO_COLS,
)

log = logging.getLogger(__name__)

DATASET = "batted_ball_mlp"
DEFAULT_CONTAINER = "bullpen-clickhouse"

# The home-plate constants in the scaled hc_x/hc_y system, mirrored from
# battedball.features_shared.hc_to_spray_deg (a test pins this module to that scalar). Vectorised
# here because the full-data pull is ~1M home-park BIPs across 2015-2025.
_HC_HOME_X = 125.42
_HC_HOME_Y = 198.27

# Raw TSV column order emitted by the per-year query. Kept adjacent to the query so the parser and
# the SELECT cannot drift apart.
_RAW_COLS: tuple[str, ...] = (
    "launch_speed_mph",
    "launch_angle_deg",
    "hc_x",
    "hc_y",
    "hit_distance_ft",
    "stand",
    "outs",
    "base_state",
    "park",
    "label",
    "prob_out",
    "prob_1b",
    "prob_2b",
    "prob_3b",
    "prob_hr",
    "carry_ft",
)


def build_year_query(year: int, *, allow_holdout_eval: bool = False) -> str:
    """Per-year, all-parks pull. Mirrors ``mlp_per_park.dataset._query_park_bips`` (same in-play
    filter, same non-null gates, same home-park join), but selects every park's BIPs for one season
    plus the realized ``observed_outcome`` the CV scores against.

    ``allow_holdout_eval`` is the rule-13 carve-out: the 2026 holdout may be QUERIED only for a
    post-training accuracy READ (the backfill-accuracy job sets it). The ``export_batted_ball_full``
    producer and every training/validation caller leave it False, so they keep the hard refusal -
    2026 never enters a training or validation split."""
    if year >= HOLDOUT_YEAR and not allow_holdout_eval:
        raise ValueError(
            f"rule 13: refusing to export season {year} (>= {HOLDOUT_YEAR} is holdout)"
        )
    # join_algorithm='partial_merge': the pitches-FINAL x bbip_retrodicted_labels-FINAL default hash
    # join OOMs (ClickHouse exit 241, MEMORY_LIMIT_EXCEEDED) under the production box's 4 GiB
    # container cap; partial_merge sort-merges and spills to disk, running the same join in seconds
    # with a bounded footprint (proven on the box). It changes only HOW the join executes, never
    # WHICH rows it returns, so every caller (backfill, backfill-accuracy, rolling-CV, carry-promo,
    # full export) gets the identical result set - just memory-safe under the cap.
    return f"""
    SELECT
      toString(p.launch_speed_mph) AS launch_speed_mph,
      toString(p.launch_angle_deg) AS launch_angle_deg,
      toString(p.hc_x)             AS hc_x,
      toString(p.hc_y)             AS hc_y,
      toString(p.hit_distance_ft)  AS hit_distance_ft,
      p.stand                      AS stand,
      toString(p.outs)             AS outs,
      toString(p.base_state)       AS base_state,
      p.park_id                    AS park,
      toString(toUInt8(r.observed_outcome)) AS label,
      toString(r.prob_out)         AS prob_out,
      toString(r.prob_1b)          AS prob_1b,
      toString(r.prob_2b)          AS prob_2b,
      toString(r.prob_3b)          AS prob_3b,
      toString(r.prob_hr)          AS prob_hr,
      toString(r.carry_ft)         AS carry_ft
    FROM pitches AS p FINAL
    JOIN bbip_retrodicted_labels AS r FINAL
      ON r.game_id = p.game_id
     AND r.at_bat_index = p.at_bat_index
     AND r.pitch_number = p.pitch_number
    WHERE p.description = 'in_play'
      AND p.launch_speed_mph IS NOT NULL
      AND p.launch_angle_deg IS NOT NULL
      AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL
      AND p.hit_distance_ft IS NOT NULL
      AND r.park_id = p.park_id
      AND r.observed_outcome IS NOT NULL
      AND toYear(p.game_date) = {year}
    ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number
    SETTINGS join_algorithm = 'partial_merge'
    FORMAT TSV
    """


def rows_to_frame(tsv: str) -> pd.DataFrame:
    """Parse the per-year TSV into the ParquetSampleLoader schema for ``batted_ball_mlp``:
    the 15 ``BATTED_BALL_FEATURES`` (= production ``FEATURE_NAMES``) + integer ``label`` + ``park``
    + ``retro_0..4``.

    Pure (no I/O) so the transform is unit-testable on the Mac with a synthetic TSV."""
    empty = _empty_frame()
    if not tsv.strip():
        return empty
    raw = pd.read_csv(
        StringIO(tsv),
        sep="\t",
        header=None,
        names=list(_RAW_COLS),
        dtype={"stand": "string", "park": "string"},
    )
    if raw.empty:
        return empty

    hc_x = raw["hc_x"].to_numpy(dtype=np.float64)
    hc_y = raw["hc_y"].to_numpy(dtype=np.float64)
    # Vectorised mirror of features_shared.hc_to_spray_deg.
    spray = np.degrees(np.arctan2(_HC_HOME_X - hc_x, _HC_HOME_Y - hc_y)).astype("float32")
    # stand_one_hot convention: index 0 = R (anything not 'L'), index 1 = L.
    is_left = (raw["stand"] == "L").to_numpy()

    out = pd.DataFrame(
        {
            "launch_speed_mph": raw["launch_speed_mph"].to_numpy(dtype="float32"),
            "launch_angle_deg": raw["launch_angle_deg"].to_numpy(dtype="float32"),
            "spray_angle_deg": spray,
            "hit_distance_ft": raw["hit_distance_ft"].to_numpy(dtype="float32"),
            "stand_R": (~is_left).astype("float32"),
            "stand_L": is_left.astype("float32"),
            "outs": raw["outs"].to_numpy(dtype="int16"),
            "label": raw["label"].to_numpy(dtype="int64"),
            "park": raw["park"].astype(str),
        }
    )
    for i, name in enumerate(OUTCOME_NAMES):
        out[f"retro_{i}"] = raw[f"prob_{name}"].to_numpy(dtype="float32")
    # base_state one-hot (FEATURE_NAMES positions 6..13), matching battedball.base_state_one_hot -
    # the production per-park MLP trains on these 8 dims, so the CV must carry them.
    base_state = raw["base_state"].to_numpy(dtype="int64")
    for b in range(8):
        out[f"base_state_{b}"] = (base_state == b).astype("float32")
    # Phase 4: the home-park mean carry (ft), an eval/reference column (NOT the per-park training
    # target - that comes from the 30-park query in mlp/dataset). NULL ("\N") on unbackfilled rows
    # coerces to NaN; consumers gate on notna().
    out["carry_ft"] = np.asarray(pd.to_numeric(raw["carry_ft"], errors="coerce"), dtype="float32")
    # Column order: FEATURE_NAMES, label, park, carry_ft, retro (matches the generator).
    return cast(
        pd.DataFrame, out[[*BATTED_BALL_FEATURES, "label", "park", "carry_ft", *RETRO_COLS]]
    )


def _empty_frame() -> pd.DataFrame:
    cols: dict[str, pd.Series] = {c: pd.Series([], dtype="float32") for c in BATTED_BALL_FEATURES}
    cols["outs"] = pd.Series([], dtype="int16")
    cols["label"] = pd.Series([], dtype="int64")
    cols["park"] = pd.Series([], dtype="string")
    cols["carry_ft"] = pd.Series([], dtype="float32")
    for c in RETRO_COLS:
        cols[c] = pd.Series([], dtype="float32")
    return cast(
        pd.DataFrame,
        pd.DataFrame(cols)[[*BATTED_BALL_FEATURES, "label", "park", "carry_ft", *RETRO_COLS]],
    )


def _docker_clickhouse(query: str, *, container: str) -> str:
    """Run a query in the live ClickHouse container - mirrors mlp_per_park.dataset._run_clickhouse,
    so whatever auth works for per-park training works here."""
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def export_batted_ball_full(
    root: Path,
    *,
    season_from: int = 2015,
    season_to: int = HOLDOUT_YEAR - 1,
    container: str = DEFAULT_CONTAINER,
    runner: Callable[[str], str] | None = None,
) -> Path:
    """Write one ``year=<YYYY>.parquet`` per season under ``<root>/batted_ball_mlp/``.

    ``runner`` executes a query and returns its TSV stdout; defaults to the docker-exec client.
    Injected in tests. Returns the dataset directory. Refuses any season >= 2026 (rule 13)."""
    if season_to >= HOLDOUT_YEAR:
        raise ValueError(
            f"rule 13: season_to={season_to} touches holdout {HOLDOUT_YEAR}; export 2015-2025 only"
        )
    run = runner if runner is not None else (lambda q: _docker_clickhouse(q, container=container))
    out_dir = Path(root) / DATASET
    out_dir.mkdir(parents=True, exist_ok=True)
    for year in range(season_from, season_to + 1):
        df = rows_to_frame(run(build_year_query(year)))
        path = out_dir / f"year={year}.parquet"
        df.to_parquet(path, index=False)
        log.info("exported %s rows -> %s", len(df), path)
    return out_dir


def _build_arg_parser() -> Callable[..., None]:
    import click

    @click.command()
    @click.option(
        "--root",
        type=click.Path(file_okay=False, path_type=Path),
        required=True,
        help="Output root; files land under <root>/batted_ball_mlp/year=<YYYY>.parquet.",
    )
    @click.option("--season-from", type=int, default=2015, show_default=True)
    @click.option(
        "--season-to",
        type=int,
        default=HOLDOUT_YEAR - 1,
        show_default=True,
        help="Inclusive; must be <= 2025 (rule 13: 2026 is holdout).",
    )
    @click.option("--container", default=DEFAULT_CONTAINER, show_default=True)
    def main(root: Path, season_from: int, season_to: int, container: str) -> None:
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
        out = export_batted_ball_full(
            root, season_from=season_from, season_to=season_to, container=container
        )
        log.info("done: %s", out)

    return main


if __name__ == "__main__":
    _build_arg_parser()()
