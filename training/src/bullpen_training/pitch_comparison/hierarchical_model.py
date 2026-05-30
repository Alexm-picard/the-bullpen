"""Model 3: Hierarchical Pitch Classifier.

Two-stage classification:
  Stage 1: Predict broad category (Fastball / Breaking / Offspeed)
  Stage 2: Predict specific type within category

Final probabilities: P(type) = P(category) * P(type | category)
"""

from __future__ import annotations

import time
from typing import Final

import lightgbm as lgb
import numpy as np
import pandas as pd

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
    PITCH_TYPE_TO_INT,
)
from bullpen_training.pitch_comparison.models import PredictionBundle

COARSE_CLASSES: Final[tuple[str, ...]] = ("Fastball", "Breaking", "Offspeed")
COARSE_MAP: Final[dict[str, str]] = {
    "FF": "Fastball",
    "SI": "Fastball",
    "FC": "Fastball",
    "SL": "Breaking",
    "CU": "Breaking",
    "ST": "Breaking",
    "CH": "Offspeed",
    "OTHER": "Offspeed",
}
COARSE_TO_INT: Final[dict[str, int]] = {c: i for i, c in enumerate(COARSE_CLASSES)}
FINE_WITHIN_COARSE: Final[dict[str, tuple[str, ...]]] = {
    "Fastball": ("FF", "SI", "FC"),
    "Breaking": ("SL", "CU", "ST"),
    "Offspeed": ("CH", "OTHER"),
}


def _map_to_coarse(pitch_type_int: np.ndarray) -> np.ndarray:
    """Map 8-class pitch type ints to 3-class coarse ints."""
    coarse = np.zeros(len(pitch_type_int), dtype=np.int8)
    for fine_name, coarse_name in COARSE_MAP.items():
        fine_idx = PITCH_TYPE_TO_INT[fine_name]
        coarse_idx = COARSE_TO_INT[coarse_name]
        coarse[pitch_type_int == fine_idx] = coarse_idx
    return coarse


def _map_fine_within(
    pitch_type_int: np.ndarray,
    coarse_name: str,
) -> np.ndarray:
    """Map global 8-class ints to local within-coarse ints."""
    fine_types = FINE_WITHIN_COARSE[coarse_name]
    global_to_local = {PITCH_TYPE_TO_INT[ft]: i for i, ft in enumerate(fine_types)}
    local = np.zeros(len(pitch_type_int), dtype=np.int8)
    for g_idx, l_idx in global_to_local.items():
        local[pitch_type_int == g_idx] = l_idx
    return local


def train_hierarchical(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    config: ExperimentConfig,
) -> tuple[dict, float]:
    feat = list(FEATURE_COLS)
    t0 = time.perf_counter()

    base_params = {
        "learning_rate": config.hier_lr,
        "num_leaves": config.hier_num_leaves,
        "seed": config.seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
    }
    has_val = len(val_df) > 0

    # Stage 1: Coarse classifier.
    train_coarse = _map_to_coarse(
        train_df["pitch_type_int"].values,
    )
    val_coarse = _map_to_coarse(val_df["pitch_type_int"].values) if has_val else None

    coarse_params = {
        **base_params,
        "objective": "multiclass",
        "num_class": len(COARSE_CLASSES),
        "metric": "multi_logloss",
    }
    dt = lgb.Dataset(train_df[feat], label=train_coarse)
    valid_sets = [dt]
    valid_names = ["t"]
    cbs: list = []
    if has_val:
        dv = lgb.Dataset(val_df[feat], label=val_coarse, reference=dt)
        valid_sets.append(dv)
        valid_names.append("v")
        cbs.append(lgb.early_stopping(50, first_metric_only=True, verbose=False))
    coarse_model = lgb.train(
        coarse_params,
        dt,
        config.hier_num_boost_round,
        valid_sets=valid_sets,
        valid_names=valid_names,
        callbacks=cbs,
    )
    print("  coarse classifier trained", flush=True)

    # Stage 2: Per-category fine classifiers.
    fine_models: dict[str, lgb.Booster] = {}
    for coarse_name, fine_types in FINE_WITHIN_COARSE.items():
        n_fine = len(fine_types)
        # Filter training data to this coarse category.
        coarse_idx = COARSE_TO_INT[coarse_name]
        train_mask = train_coarse == coarse_idx
        if train_mask.sum() == 0:
            continue

        train_sub = train_df.loc[train_mask]
        train_fine_y = _map_fine_within(
            train_sub["pitch_type_int"].values,
            coarse_name,
        )

        fine_params = {
            **base_params,
            "objective": "multiclass",
            "num_class": n_fine,
            "metric": "multi_logloss",
        }
        dt = lgb.Dataset(train_sub[feat], label=train_fine_y)
        valid_sets = [dt]
        valid_names = ["t"]
        cbs = []

        if has_val and val_coarse is not None:
            val_mask = val_coarse == coarse_idx
            if val_mask.sum() > 0:
                val_sub = val_df.loc[val_mask]
                val_fine_y = _map_fine_within(
                    val_sub["pitch_type_int"].values,
                    coarse_name,
                )
                dv = lgb.Dataset(
                    val_sub[feat],
                    label=val_fine_y,
                    reference=dt,
                )
                valid_sets.append(dv)
                valid_names.append("v")
                cbs.append(
                    lgb.early_stopping(
                        50,
                        first_metric_only=True,
                        verbose=False,
                    )
                )

        fine_models[coarse_name] = lgb.train(
            fine_params,
            dt,
            config.hier_num_boost_round,
            valid_sets=valid_sets,
            valid_names=valid_names,
            callbacks=cbs,
        )
        print(f"  fine classifier '{coarse_name}' trained", flush=True)

    elapsed = time.perf_counter() - t0
    bundle = {"coarse": coarse_model, "fine": fine_models}
    return bundle, elapsed


def predict_hierarchical(
    bundle: dict,
    test_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    feat = list(FEATURE_COLS)
    x = test_df[feat]
    n = len(test_df)

    # Stage 1: coarse probabilities.
    coarse_proba = np.asarray(
        bundle["coarse"].predict(x),
        dtype=np.float64,
    )

    # Stage 2: fine probabilities within each category.
    n_types = len(PITCH_TYPE_CLASSES)
    final_proba = np.zeros((n, n_types), dtype=np.float64)

    for coarse_name, fine_types in FINE_WITHIN_COARSE.items():
        coarse_idx = COARSE_TO_INT[coarse_name]
        p_coarse = coarse_proba[:, coarse_idx]

        if coarse_name in bundle["fine"]:
            fine_proba = np.asarray(
                bundle["fine"][coarse_name].predict(x),
                dtype=np.float64,
            )
        else:
            fine_proba = np.ones((n, len(fine_types))) / len(fine_types)

        for local_idx, ft_name in enumerate(fine_types):
            global_idx = PITCH_TYPE_TO_INT[ft_name]
            final_proba[:, global_idx] = p_coarse * fine_proba[:, local_idx]

    # Renormalize.
    row_sums = final_proba.sum(axis=1, keepdims=True)
    row_sums = np.maximum(row_sums, 1e-9)
    final_proba = final_proba / row_sums

    return PredictionBundle(
        pitch_type_proba=final_proba.astype(np.float32),
        velocity=np.full(n, np.nan, dtype=np.float32),
        outcome_proba=np.full((n, 6), 1 / 6, dtype=np.float32),
        ab_pitch_count=np.full(n, np.nan, dtype=np.float32),
        elapsed_train_sec=0.0,
    )
