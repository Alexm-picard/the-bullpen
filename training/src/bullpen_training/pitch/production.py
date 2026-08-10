"""Production-v1 training CLI for the pre-pitch + post-pitch heads
(Phases 2a.7 and 2b.2).

Three flows, one per `--model`:

  lightgbm  → pre-pitch head. 4-fold rolling-origin CV on Tier 1+2+3
               features, then train final production bundle on fold 4
               (train 2015-2023, val 2024, test 2025). Persists to
               `training/artifacts/pitch_outcome_pre/v1/`.
  lr        → pre-pitch LR baseline (Tier 1+2+3). Persists to
               `training/artifacts/pitch_outcome_lr_baseline/v1/`.
  post      → post-pitch head. Same flow but with Tier 1+2+3+4 features.
               Persists to `training/artifacts/pitch_outcome_post/v1/`.

All three write the 5 canonical files + an `eval/` directory.

The strict "train on 2015-2024 + val on 2025" production fold the leaf
plan calls out is NOT implemented (a `--use-extended-fold` flag was once
sketched here but never built - stale reference removed, #188). The flow
uses fold 4 (train 2015-2023, val 2024, test 2025) so the persisted
bundle has a real held-out test set whose metrics we can publish. If
ad-hoc fold plumbing is ever added, it must pass `refuse_holdout` (rule
13) exactly as main() fences the hardcoded FOLDS today.
"""

from __future__ import annotations

import gc
import logging
from pathlib import Path
from typing import Any, cast

import click
import numpy as np
import pandas as pd

from bullpen_training.eval.cv_harness import FOLDS, FoldSpec
from bullpen_training.eval.cv_harness import run as cv_run
from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)
from bullpen_training.logging_config import configure_logging, get_logger
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.fold_store import ParquetFoldLoader
from bullpen_training.pitch.persist import (
    PersistInputs,
    persist_lightgbm_v1,
    persist_lr_baseline_v1,
)
from bullpen_training.pitch.train_lr_baseline import (
    DESIGN_MATRIX_DTYPE as LR_DESIGN_MATRIX_DTYPE,
)
from bullpen_training.pitch.train_lr_baseline import (
    MAX_LR_TRAIN_ROWS,
    LRModelBundle,
    fit_lr_from_arrays,
    subsample_train_rows,
)
from bullpen_training.pitch.train_lr_baseline import (
    model_factory as lr_factory,
)
from bullpen_training.pitch.train_post import ModelBundle as PostModelBundle
from bullpen_training.pitch.train_post import (
    make_feature_loader as make_feature_loader_post,
)
from bullpen_training.pitch.train_post import (
    model_factory as post_factory,
)
from bullpen_training.pitch.train_pre import (
    ModelBundle,
    make_feature_loader,
)
from bullpen_training.pitch.train_pre import (
    model_factory as lgb_factory,
)

log = get_logger(__name__)

# Re-export silencer for the unused-but-needed PostModelBundle alias.
_ = (PostModelBundle,)

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

# Post head uses the same hyperparameters as the pre head (decision [35]:
# the heads differ only in feature set, not in modelling family).
_LGBM_POST_HYPERPARAMS: dict[str, Any] = dict(_LGBM_HYPERPARAMS)


def _train_production_lightgbm(
    loader: Any, prod_fold: FoldSpec
) -> tuple[ModelBundle, pd.DataFrame, np.ndarray]:
    """Train the final production LightGBM on prod_fold's train+val window;
    use the test year for the eval-artifact preds."""
    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    bundle = lgb_factory(train_df, val_df, num_boost_round=2000, early_stopping_rounds=50)
    # CV-MEM-1: free train/val and load test AFTER training - test is only used for
    # the eval-artifact preds below, never during the fit, so it need not be resident
    # during the train-time peak.
    del train_df, val_df
    gc.collect()
    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    test_predictions = cast(np.ndarray, bundle.predict_proba(test_df))
    return bundle, test_df, test_predictions


def _train_production_lr(
    loader: Any, prod_fold: FoldSpec
) -> tuple[LRModelBundle, pd.DataFrame, np.ndarray]:
    feat_cols = list(PITCH_FEATURE_COLUMNS)
    # Path 1: the LR production fit OOMs on the full fold-4 window (~20M rows) - lbfgs
    # upcasts the design matrix to float64 internally (~13.7 GB peak, measured; the
    # frame is already freed before the fit, so freeing it earlier is a no-op, and
    # f1d2b66's float32 only halved the preprocessing intermediates). Subsample the
    # train rows (fixed seed, WITHIN the fixed fold window - NOT a re-split) so the
    # fit's peak fits the box headroom. CV + the test eval-metrics stay full-data; only
    # the persisted LR model is on the subsample.
    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    original_rows = len(train_df)
    train_df = subsample_train_rows(train_df)
    subsample_rows = MAX_LR_TRAIN_ROWS if len(train_df) < original_rows else None

    # Extract the float32 design matrix, then free the (subsampled) frame before the
    # fit; train dwarfs val, so free it before val even loads.
    X_train = train_df[feat_cols].to_numpy(dtype=LR_DESIGN_MATRIX_DTYPE)
    y_train = np.asarray(train_df["label"], dtype=np.int64)
    del train_df
    gc.collect()

    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    X_val = val_df[feat_cols].to_numpy(dtype=LR_DESIGN_MATRIX_DTYPE)
    y_val = np.asarray(val_df["label"], dtype=np.int64)
    del val_df
    gc.collect()

    bundle = fit_lr_from_arrays(X_train, y_train, X_val, y_val)
    bundle.train_subsample_rows = subsample_rows
    del X_train, y_train, X_val, y_val
    gc.collect()

    # test is only needed for the eval-artifact preds (CV-MEM-1), loaded after the fit.
    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    test_predictions = cast(np.ndarray, bundle.predict_proba(test_df))
    return bundle, test_df, test_predictions


def _train_production_post(
    loader: Any, prod_fold: FoldSpec
) -> tuple[PostModelBundle, pd.DataFrame, np.ndarray]:
    """Post head — same flow as pre, different feature set + different
    `model_name` at persistence time."""
    train_df = loader(prod_fold.train_start_year, prod_fold.train_end_year, prod_fold.fold_id)
    val_df = loader(prod_fold.val_year, prod_fold.val_year, prod_fold.fold_id)
    bundle = post_factory(train_df, val_df, num_boost_round=2000, early_stopping_rounds=50)
    del train_df, val_df  # CV-MEM-1: free before the deferred test load
    gc.collect()
    test_df = loader(prod_fold.test_year, prod_fold.test_year, prod_fold.fold_id)
    test_predictions = cast(np.ndarray, bundle.predict_proba(test_df))
    return bundle, test_df, test_predictions


@click.command()
@click.option("--model", type=click.Choice(["lightgbm", "lr", "post"]), required=True)
@click.option("--version", default="v1", show_default=True)
@click.option(
    "--artifacts-dir",
    type=click.Path(file_okay=False, dir_okay=True, path_type=Path),
    default=None,
)
@click.option("--skip-cv", is_flag=True, help="Skip the 4-fold CV; produce empty cv_result")
@click.option(
    "--folds-dir",
    type=click.Path(exists=True, file_okay=False, dir_okay=True, path_type=Path),
    default=None,
    help=(
        "Train off a fetched fold-parquet export (ADR-0007 / WS-B) via "
        "ParquetFoldLoader instead of the ClickHouse loader. Required for "
        "off-box (Mac) training, which has no ClickHouse."
    ),
)
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
    folds_dir: Path | None,
    log_format: str,
) -> None:
    import os

    if log_format.lower() == "json":
        os.environ["LOG_FORMAT"] = "json"
    configure_logging(level=logging.INFO)

    # Rule-13 defense-in-depth (#188): every season below comes from the hardcoded 2015-2025
    # FOLDS, so this can only fire if someone edits FOLDS or adds season plumbing - which is
    # exactly the future mistake it exists to catch (the batted-ball trainers carry the same
    # fence per [170]). season_to=test_year covers the fold's maximum year.
    for fold in FOLDS:
        refuse_holdout(season_from=fold.train_start_year, season_to=fold.test_year)

    # LightGBM's default Python logger uses bare `print(msg)`. When stdout is
    # piped (e.g. `... 2>&1 | tee log`), Python defaults to block-buffered
    # stdout, so `log_evaluation` iteration lines sit in an 8KB buffer for the
    # entire 2000-round run — looks identical to a hang. Route LightGBM's
    # output through Python's logging module, which is line-buffered on
    # stderr by default (configured in `configure_logging`). This is the
    # actual fix for the fold-3+4 "hang" reported in the 2b.2 status log —
    # training was completing fine, the progress just wasn't visible.
    import lightgbm as _lgb

    _lgb.register_logger(logging.getLogger("lightgbm"))

    # The post head needs the Tier-4-aware loader (extra columns + pitch_type
    # categorical mapping). Pre + LR share the Tier 1+2+3 loader.
    loader: Any
    factory: Any
    if model == "post":
        factory = post_factory
    elif model == "lightgbm":
        factory = lgb_factory
    else:  # lr
        factory = lr_factory

    if folds_dir is not None:
        # Off-box training (ADR-0007 / WS-B): ParquetFoldLoader is a drop-in for
        # the ClickHouse loader closure (same (start, end, fold) -> frame call
        # signature, same park_id + pitch_type mappings persist reads), so the
        # Mac trains the head on the fetched fold export with no ClickHouse
        # dependency. The export is POST-shaped (Tier 1+2+3+4); the pre/lr
        # factories subselect their own columns from the wider frame.
        loader = ParquetFoldLoader(folds_dir)
    elif model == "post":
        loader = make_feature_loader_post()
    else:
        loader = make_feature_loader()

    prod_fold = FOLDS[-1]  # fold 4: train 2015-2023, val 2024, test 2025
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
    elif model == "post":
        bundle, test_df, test_preds = _train_production_post(loader, prod_fold)
        model_name = "pitch_outcome_post"
        hyperparams = _LGBM_POST_HYPERPARAMS
    else:  # lr
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
        # Post head has a second lookup (pitch_type_int); pre + LR loaders don't
        # expose this attribute, so getattr-with-None keeps the call uniform.
        pitch_type_mapping=getattr(loader, "pitch_type_mapping", None),
    )

    if model in ("lightgbm", "post"):
        # The post head reuses persist_lightgbm_v1 — same on-disk layout
        # (model.lgb + calibrator.json + metadata + eval/); only the
        # model_name + feature_pipeline.json contract differ. The contract
        # for `pitch_outcome_post` lands in 2b.3 alongside the Spring serve;
        # for now `_copy_feature_pipeline` will copy the pre contract — the
        # post bundle's metadata.json carries the actual feature list. 2b.3
        # introduces a per-model contract dispatch.
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
