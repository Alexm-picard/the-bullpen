"""Production-v1 training CLI for the pre-pitch head (Phase 2a.7).

Two flows, one per `--model`:

  lightgbm  → run 4-fold rolling-origin CV, then train final production
               bundle on fold 4's train+val window (LightGBM ModelBundle),
               persist via persist_lightgbm_v1 + eval/
  lr        → same flow against the LR baseline factory

Both write to `training/artifacts/<model_name>/v<N>/` with the 5 canonical
files + an `eval/` directory.

For the strict "train on 2015-2024 + val on 2025" production model the
leaf plan calls out, see the `--use-extended-fold` flag which builds an
ad-hoc fold (train=2015-2024, val=2025, no test). The default uses
fold 4 (train 2015-2023, val 2024, test 2025) so the persisted bundle
has a real held-out test set whose metrics we can publish.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, cast

import click
import numpy as np
import pandas as pd

from bullpen_training.eval.cv_harness import FOLDS, FoldSpec
from bullpen_training.eval.cv_harness import run as cv_run
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)
from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch.persist import (
    PersistInputs,
    persist_lightgbm_v1,
    persist_lr_baseline_v1,
)
from bullpen_training.pitch.train_lr_baseline import (
    LRModelBundle,
)
from bullpen_training.pitch.train_lr_baseline import (
    model_factory as lr_factory,
)
from bullpen_training.pitch.train_pre import (
    ModelBundle,
    make_feature_loader,
)
from bullpen_training.pitch.train_pre import (
    model_factory as lgb_factory,
)

log = get_logger(__name__)

_LGBM_HYPERPARAMS: dict[str, Any] = {
    "objective": "multiclass",
    "num_class": 5,
    "learning_rate": 0.05,
    "num_leaves": 63,
    "min_data_in_leaf": 200,
    "feature_fraction": 0.8,
    "bagging_fraction": 0.8,
    "bagging_freq": 5,
    "seed": 42,
}

_LR_HYPERPARAMS: dict[str, Any] = {
    "solver": "lbfgs",
    "max_iter": 2000,
    "C": 1.0,
    "seed": 42,
    "imputer_strategy": "median",
    "scaler": "standard",
}


def _train_production_lightgbm(
    loader: Any, prod_fold: FoldSpec
) -> tuple[ModelBundle, pd.DataFrame, np.ndarray]:
    """Train the final production LightGBM on prod_fold's train+val window;
    use the test year for the eval-artifact preds."""
    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    bundle = lgb_factory(train_df, val_df, num_boost_round=2000, early_stopping_rounds=50)
    test_predictions = cast(np.ndarray, bundle.predict_proba(test_df))
    return bundle, test_df, test_predictions


def _train_production_lr(
    loader: Any, prod_fold: FoldSpec
) -> tuple[LRModelBundle, pd.DataFrame, np.ndarray]:
    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    bundle = lr_factory(train_df, val_df)
    test_predictions = cast(np.ndarray, bundle.predict_proba(test_df))
    return bundle, test_df, test_predictions


@click.command()
@click.option("--model", type=click.Choice(["lightgbm", "lr"]), required=True)
@click.option("--version", default="v1", show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option("--skip-cv", is_flag=True, help="Skip the 4-fold CV; produce empty cv_result")
@click.option(
    "--log-format",
    type=click.Choice(["console", "json"], case_sensitive=False),
    default="console",
)
def main(
    model: str,
    version: str,
    artifacts_dir: Path | None,
    skip_cv: bool,
    log_format: str,
) -> None:
    import os

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)

    loader = make_feature_loader()
    prod_fold = FOLDS[-1]  # fold 4: train 2015-2023, val 2024, test 2025

    factory = lgb_factory if model == "lightgbm" else lr_factory
    log.info("starting production training", model=model, version=version)
    cv_result = (
        _empty_cv_result()
        if skip_cv
        else cv_run(
            model_factory=factory,
            feature_loader=loader,
            eval_metrics=[
                multiclass_brier,
                multiclass_log_loss,
                expected_calibration_error,
            ],
        )
    )
    log.info("CV done", summary={k: v for k, v in cv_result.summary.items()})

    if model == "lightgbm":
        bundle, test_df, test_preds = _train_production_lightgbm(loader, prod_fold)
        model_name = "pitch_outcome_pre"
        hyperparams = _LGBM_HYPERPARAMS
    else:
        bundle, test_df, test_preds = _train_production_lr(loader, prod_fold)
        model_name = "pitch_outcome_lr_baseline"
        hyperparams = _LR_HYPERPARAMS

    inputs = PersistInputs(
        model_name=model_name,
        model_version=version,
        train_window=f"{prod_fold.train_start_year}-{prod_fold.train_end_year}",
        val_window=str(prod_fold.val_year),
        test_df=test_df,
        test_predictions=test_preds,
        cv_result=cv_result,
        hyperparams=hyperparams,
        fold_id=prod_fold.fold_id,
        park_id_mapping=loader.park_id_mapping,
    )

    if model == "lightgbm":
        out_dir = persist_lightgbm_v1(
            cast(ModelBundle, bundle), inputs, artifacts_dir=artifacts_dir
        )
    else:
        out_dir = persist_lr_baseline_v1(
            cast(LRModelBundle, bundle), inputs, artifacts_dir=artifacts_dir
        )
    log.info("production bundle persisted to %s (model=%s)", out_dir, model)


def _empty_cv_result() -> Any:
    """Skip-CV fallback for fast iteration; metadata records n_folds=0."""
    from bullpen_training.eval.cv_harness import CVResult

    return CVResult(per_fold=(), summary={})


if __name__ == "__main__":
    main()
