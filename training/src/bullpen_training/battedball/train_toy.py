"""Toy HR classifier trainer (Phase 1.3).

Trains a deliberately under-engineered binary LightGBM model on 5 features.
The output `model.lgb` is the only thing 1.4 (ONNX export) cares about;
`metadata.json` carries the provenance Java side reads to render the
model identity in the UI.

Reproducibility: `deterministic=True` + `force_row_wise=True` + a fixed LightGBM
seed, with a positional (non-random) temporal split. Two runs from the same git
commit on the same data produce a byte-identical `model.lgb`.

Limits — **intentional and Phase-1-only**:
    - Single binary output (HR vs not-HR). No 5-class outcome head.
    - No calibration (no isotonic, no Platt scaling).
    - No rolling-origin CV - a single temporal 80/20 holdout (trailing slice of the
      game-date-ordered frame). No random_state: the no-random-split rule has no toy
      exception (TD sign-off 2026-06-06).
    - Park target-encoding still leaks within-year HR rate (the real Phase-2a pipeline
      ships out-of-fold TE); so the reported AUC remains optimistic on that axis even
      with the split now temporal.
    - Not registered in the Spring registry; lives at training/artifacts/_toy/v0/.

Usage:
    uv run python -m bullpen_training.battedball.train_toy
    uv run python -m bullpen_training.battedball.train_toy --year 2024 --out-dir /tmp/x
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

import click
import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from bullpen_training.battedball.features_toy import (
    FEATURES,
    TARGET,
    load_training_frame,
)
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.logging_config import configure_logging, get_logger

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_OUTPUT_DIR = REPO_ROOT / "training" / "artifacts" / "_toy" / "v0"
SEED = 42
MIN_ACCEPTABLE_AUC = 0.70


def _git_commit_sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT), text=True
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def _train_on_frame(df: pd.DataFrame) -> tuple[lgb.Booster, dict[str, Any]]:
    """Fit the toy booster on a TEMPORAL holdout (last 20% of rows). Returns model + metrics.

    NOT a random split. The production frame arrives ordered by game_date
    (features_toy.load_training_frame ORDERs by the PK, game_date leading), so the trailing
    slice is the latest dates - a leakage-safe split with zero random_state. The
    no-random-split rule (CLAUDE.md hard-never) has no toy exception: a stratified random
    split mixes future rows into train and inflates the reported AUC with leakage, which is
    exactly the optimism the rule exists to prevent. The positional slice is also fully
    deterministic, so the byte-identical-model reproducibility property is preserved.
    """
    np.random.seed(SEED)
    split_idx = int(len(df) * 0.8)
    train = cast(pd.DataFrame, df.iloc[:split_idx].copy())
    test = cast(pd.DataFrame, df.iloc[split_idx:].copy())
    params: dict[str, Any] = {
        "objective": "binary",
        "metric": "auc",
        "learning_rate": 0.05,
        "num_leaves": 31,
        "min_data_in_leaf": 50,
        "seed": SEED,
        "deterministic": True,
        "force_row_wise": True,
        "verbosity": -1,
    }
    dtrain = lgb.Dataset(train[list(FEATURES)], label=train[TARGET])
    booster = cast(lgb.Booster, lgb.train(params, dtrain, num_boost_round=200))
    preds = booster.predict(test[list(FEATURES)])
    auc = float(roc_auc_score(test[TARGET], preds))
    metrics = {
        "auc": auc,
        "n_train": len(train),
        "n_test": len(test),
        "hr_rate_train": float(cast(pd.Series, train[TARGET]).mean()),
        "hr_rate_test": float(cast(pd.Series, test[TARGET]).mean()),
    }
    return booster, metrics


def run_training(
    year: int = 2024,
    *,
    settings: ClickHouseSettings | None = None,
    output_dir: Path | None = None,
) -> dict[str, Any]:
    """Programmatic entrypoint. Returns the metadata dict written to disk."""
    client = make_client(settings)
    started = time.time()
    df = load_training_frame(client, year)
    log.info("training frame loaded", rows=len(df), year=year)
    if len(df) < 1000:
        raise RuntimeError(f"training frame too small ({len(df)} rows); did 1.2 finish?")

    booster, metrics = _train_on_frame(df)
    if metrics["auc"] < MIN_ACCEPTABLE_AUC:
        raise RuntimeError(
            f"AUC {metrics['auc']:.4f} below floor {MIN_ACCEPTABLE_AUC} — "
            "features may have lost signal; investigate"
        )

    outdir = output_dir or DEFAULT_OUTPUT_DIR
    outdir.mkdir(parents=True, exist_ok=True)
    model_path = outdir / "model.lgb"
    booster.save_model(str(model_path))
    model_sha = _sha256(model_path)

    metadata: dict[str, Any] = {
        "model_name": "_toy_batted_ball",
        "version": "v0",
        "phase": "1.3",
        "features": list(FEATURES),
        "target": TARGET,
        "train_year": year,
        "metrics": metrics,
        "git_commit": _git_commit_sha(),
        "trained_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "seed": SEED,
        "model_sha256": model_sha,
        "elapsed_s": round(time.time() - started, 2),
        "registry_status": "not_registered (toy)",
    }
    (outdir / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    log.info(
        "toy model trained", **{k: metadata[k] for k in ("metrics", "elapsed_s", "model_sha256")}
    )
    return metadata


@click.command()
@click.option("--year", type=int, default=2024, show_default=True)
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
    help="Output directory (defaults to training/artifacts/_toy/v0/).",
)
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
    show_default=True,
)
def main(year: int, out_dir: Path | None, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)
    run_training(year, output_dir=out_dir)


if __name__ == "__main__":
    main()
