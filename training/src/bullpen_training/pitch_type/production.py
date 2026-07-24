"""Production training CLI for pitch_type_pre (Phase 2a, decision [183]).

Runs the 4-fold rolling-origin CV (the promotion evidence), trains the final production
bundle on fold 4 (train 2015-2023, val 2024, test 2025), and persists the canonical files
to ``training/artifacts/pitch_type_pre/<version>/``. The ONNX export
(``pitch_type.export_onnx``) is a separate step run afterwards on the persisted model.lgb.

The box run: this reads the materialised ``pitch_type_features`` (V029) via the ClickHouse
loader (``pitch_type.train.make_pitch_type_feature_loader``), which slices by date window
(the store is single-pass fold=0). Rule 13 is fenced at the top from the hardcoded FOLDS.
"""

from __future__ import annotations

import gc
import logging
import os
from pathlib import Path
from typing import Any, cast

import click
import numpy as np

from bullpen_training.eval.cv_harness import FOLDS, CVResult
from bullpen_training.eval.cv_harness import run as cv_run
from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)
from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch_type.persist import PitchTypePersistInputs, persist_pitch_type_v1
from bullpen_training.pitch_type.train import make_pitch_type_feature_loader, model_factory

log = get_logger(__name__)

# Provenance snapshot for metadata.json. MUST mirror pitch_type.train.model_factory's
# hardcoded LightGBM params (report appendix); it does not drive training, it records it.
HYPERPARAMS: dict[str, Any] = {
    "objective": "multiclass",
    "num_class": 7,
    "learning_rate": 0.05,
    "num_leaves": 63,
    "min_data_in_leaf": 200,
    "feature_fraction": 0.8,
    "bagging_fraction": 0.8,
    "bagging_freq": 5,
    "seed": 42,
    "num_boost_round": 2000,
    "early_stopping_rounds": 30,
    "calibrator": "temperature",
}


def _empty_cv_result() -> CVResult:
    """Skip-CV fallback for fast iteration; metadata records an empty summary."""
    return CVResult(per_fold=(), summary={})


def train_and_persist(
    loader: Any,
    *,
    version: str = "v1",
    artifacts_dir: Path | None = None,
    skip_cv: bool = False,
    num_boost_round: int = 2000,
    early_stopping_rounds: int = 30,
) -> Path:
    """Run CV (unless skipped), train the fold-4 production bundle, persist the canonical files.

    ``loader`` is any ``(start_year, end_year, fold_id) -> DataFrame`` closure with a
    ``label`` column + PITCH_TYPE_FEATURE_COLUMNS (the ClickHouse loader in prod; a synthetic
    one in tests). ``num_boost_round``/``early_stopping_rounds`` govern only the persisted
    production fit; CV always uses model_factory's own defaults.
    """
    prod_fold = FOLDS[-1]  # fold 4: train 2015-2023, val 2024, test 2025
    cv_result = (
        _empty_cv_result()
        if skip_cv
        else cv_run(
            model_factory=model_factory,
            feature_loader=loader,
            eval_metrics=[multiclass_brier, multiclass_log_loss, expected_calibration_error],
        )
    )
    log.info("CV done", summary={k: v for k, v in cv_result.summary.items()})

    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    bundle = model_factory(
        train_df,
        val_df,
        num_boost_round=num_boost_round,
        early_stopping_rounds=early_stopping_rounds,
    )
    # CV-MEM-1: free train/val before the deferred test load (test only feeds the eval preds).
    del train_df, val_df
    gc.collect()

    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    test_preds = cast(np.ndarray, bundle.predict_proba(test_df))

    inputs = PitchTypePersistInputs(
        model_version=version,
        train_window=f"{prod_fold.train_start_year}-{prod_fold.train_end_year}",
        val_window=str(prod_fold.val_year),
        test_df=test_df,
        test_predictions=test_preds,
        cv_result=cv_result,
        hyperparams=HYPERPARAMS,
        park_id_mapping=getattr(loader, "park_id_mapping", None),
    )
    return persist_pitch_type_v1(bundle, inputs, artifacts_dir=artifacts_dir)


@click.command()
@click.option("--version", default="v1", show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option("--skip-cv", is_flag=True, help="Skip the 4-fold CV; produce an empty cv_result")
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(version: str, artifacts_dir: Path | None, skip_cv: bool, log_format: str) -> None:
    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)

    # Rule-13 defense-in-depth: every season comes from the hardcoded 2015-2025 FOLDS, so this
    # can only fire if someone edits FOLDS or adds season plumbing - the future mistake it exists
    # to catch (the pitch/batted-ball trainers carry the same fence).
    for fold in FOLDS:
        refuse_holdout(season_from=fold.train_start_year, season_to=fold.test_year)

    # Route LightGBM's bare-print progress through Python logging (line-buffered stderr) so the
    # 2000-round run's iteration lines aren't stuck in a block-buffered stdout (the pitch fix).
    import lightgbm as _lgb

    _lgb.register_logger(logging.getLogger("lightgbm"))

    loader = make_pitch_type_feature_loader()
    out_dir = train_and_persist(
        loader, version=version, artifacts_dir=artifacts_dir, skip_cv=skip_cv
    )
    log.info("production bundle persisted", out_dir=str(out_dir), version=version)


if __name__ == "__main__":
    main()
