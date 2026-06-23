"""Run the OFFLINE batted-ball backfill accuracy job (Phase 3 PR-alpha).

Scores historical in-play batted balls through the ``battedball_outcome`` champion
bundle and writes a REAL predicted-vs-actual accuracy report (per-class confusion,
HR precision / recall, multiclass Brier, log-loss, ECE, per-park grid).

This is a BOX-side run (ADR-0006): the champion bundle + ClickHouse / the parquet
mirror live on the desktop. Authored + unit-tested on the Mac; the scoring RUN
happens on the box. DO NOT commit the result JSON (a Mac-fabricated accuracy number
would violate the honesty discipline).

HONESTY (see ``backfill_accuracy`` module docstring): scored against the REAL
realized integer ``label``, NEVER ``retro_*``; the report carries
``data_source="historical_pitches_offline_holdout"`` + ``eval_kind="offline_held_out"``
+ a ``disclaimer`` documenting the leakage posture. Rule 13: 2026+ is refused.

Two data sources (pick one):

  * ``--sample-root <root>`` reads the parquet mirror the CV harness already uses
    (``ParquetSampleLoader``, ``<root>/batted_ball_mlp/year=<YYYY>.parquet``) - the
    artifact ``export_batted_ball_full`` produces on the box. Preferred: it is the
    exact on-disk schema the champion was evaluated on.
  * otherwise the script queries the live ClickHouse container per season
    (``export_batted_ball_full.build_year_query`` + ``rows_to_frame`` +
    ``_docker_clickhouse``, ``--container bullpen-clickhouse``).

Example (box):

    uv run python scripts/run_battedball_backfill_accuracy.py \\
        --model-dir artifacts/battedball_outcome/v1 \\
        --season-from 2015 --season-to 2025 \\
        --sample-root data/mirror --out-dir data/eval
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

import pandas as pd

from bullpen_training.battedball.eval.backfill_accuracy import (
    OnnxMlpPredictor,
    save_report,
    score_backfill,
)
from bullpen_training.eval.promotion.export_batted_ball_full import (
    DEFAULT_CONTAINER,
    build_year_query,
    rows_to_frame,
)
from bullpen_training.eval.promotion.export_batted_ball_full import (
    _docker_clickhouse as docker_clickhouse,
)
from bullpen_training.eval.promotion.sample_loader import HOLDOUT_YEAR, ParquetSampleLoader

log = logging.getLogger(__name__)

DATASET = "batted_ball_mlp"

# Default disclaimer. The honesty contract REQUIRES the run to state its leakage
# posture; this default documents the IN-SAMPLE caveat for a training-years span.
# A held-out run should override it via --disclaimer.
DEFAULT_DISCLAIMER = (
    "Offline held-out eval scored against the REALIZED home-park outcome (the integer "
    "label), never the retrodicted physics distribution the MLP trained on. The per-park "
    "MLP trained on the retro distribution of these same home-park BIPs, so scoring on "
    "training years (2015-2025) is an IN-SAMPLE read; treat the numbers as an upper bound "
    "and use a held-out window (or the 2026 holdout, once permitted) for an out-of-sample "
    "estimate. Per-park isotonic calibration is applied outside the ONNX graph, matching "
    "the served path."
)


def _refuse_holdout(season_from: int, season_to: int) -> None:
    """Rule 13: refuse any span touching 2026 before any data is read."""
    if season_from >= HOLDOUT_YEAR or season_to >= HOLDOUT_YEAR:
        raise SystemExit(
            f"rule 13: {HOLDOUT_YEAR}+ is holdout-only; refusing season span "
            f"{season_from}-{season_to}."
        )


def _load_from_sample_root(root: Path, season_from: int, season_to: int) -> pd.DataFrame:
    """Read the per-year parquet mirror via the CV harness's ParquetSampleLoader."""
    loader = ParquetSampleLoader(root, DATASET)
    df = loader(season_from, season_to, fold_id=0)
    log.info("loaded %d BIPs from sample mirror %s", len(df), root / DATASET)
    return df


def _load_from_clickhouse(season_from: int, season_to: int, container: str) -> pd.DataFrame:
    """Query the live ClickHouse container per season, concatenating the years."""
    frames: list[pd.DataFrame] = []
    for year in range(season_from, season_to + 1):
        tsv = docker_clickhouse(build_year_query(year), container=container)
        frame = rows_to_frame(tsv)
        log.info("loaded %d BIPs for %d from ClickHouse", len(frame), year)
        frames.append(frame)
    if not frames:
        raise SystemExit(f"no seasons in range {season_from}-{season_to}")
    return pd.concat(frames, ignore_index=True)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the offline batted-ball backfill accuracy job."
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        required=True,
        help="The battedball_outcome champion bundle (model.onnx + metadata.json + "
        "calibrator.json). park_order / feature_scaler / calibrators are read from it - "
        "do NOT hardcode the stale _battedball_mlp_v1.stale toy.",
    )
    parser.add_argument("--season-from", type=int, default=2015)
    parser.add_argument(
        "--season-to",
        type=int,
        default=HOLDOUT_YEAR - 1,
        help="Inclusive; must be <= 2025 (rule 13: 2026 is holdout).",
    )
    parser.add_argument(
        "--sample-root",
        type=Path,
        default=None,
        help="If set, read the parquet mirror (<root>/batted_ball_mlp/year=*.parquet) "
        "via ParquetSampleLoader. Otherwise query live ClickHouse per season.",
    )
    parser.add_argument("--container", default=DEFAULT_CONTAINER, help="ClickHouse container name.")
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("data/eval"),
        help="Output dir for the JSON (+ HTML) artifact.",
    )
    parser.add_argument(
        "--disclaimer",
        default=DEFAULT_DISCLAIMER,
        help="Honesty disclaimer documenting the leakage posture of THIS run.",
    )
    parser.add_argument("--n-bins", type=int, default=15, help="ECE / per-park ECE bin count.")
    parser.add_argument("--no-html", action="store_true", help="Skip the HTML artifact.")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    _refuse_holdout(args.season_from, args.season_to)

    predictor = OnnxMlpPredictor(args.model_dir)
    log.info(
        "loaded champion %s %s (%d parks) from %s",
        predictor.model_name,
        predictor.model_version,
        len(predictor.park_order),
        args.model_dir,
    )

    if args.sample_root is not None:
        df = _load_from_sample_root(args.sample_root, args.season_from, args.season_to)
    else:
        df = _load_from_clickhouse(args.season_from, args.season_to, args.container)

    if len(df) == 0:
        raise SystemExit(
            f"no BIPs loaded for {args.season_from}-{args.season_to}; nothing to score."
        )

    report = score_backfill(
        predictor=predictor,
        df=df,
        park_order=predictor.park_order,
        model_name=predictor.model_name,
        model_version=predictor.model_version,
        season_from=args.season_from,
        season_to=args.season_to,
        disclaimer=args.disclaimer,
        n_bins=args.n_bins,
    )

    json_path = args.out_dir / "battedball_backfill_accuracy_v1.json"
    html_path = None if args.no_html else args.out_dir / "battedball_backfill_accuracy_v1.html"
    save_report(report, json_path, html_path)

    agg = report.aggregate
    log.info("== backfill accuracy (vs realized label) ==")
    log.info("  n_samples:    %d", report.n_samples)
    log.info("  Brier:        %.4f", agg["brier"])
    log.info("  Log-loss:     %.4f", agg["log_loss"])
    log.info("  ECE:          %.4f", agg["ece"])
    log.info("  Accuracy:     %.3f", agg["accuracy"])
    log.info("  HR precision: %.3f", report.hr_precision)
    log.info("  HR recall:    %.3f", report.hr_recall)
    log.info("wrote -> %s", json_path)


if __name__ == "__main__":
    main()
