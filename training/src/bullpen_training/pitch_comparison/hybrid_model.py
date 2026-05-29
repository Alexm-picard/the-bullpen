"""Model 2: Transformer + LightGBM Hybrid.

Uses the trained transformer as a sequence feature extractor. The
d_model-dimensional embedding is concatenated with engineered tabular
features and fed to a LightGBM meta-model.
"""

from __future__ import annotations

import time

import lightgbm as lgb
import numpy as np
import pandas as pd
import torch
from torch.utils.data import DataLoader

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
)
from bullpen_training.pitch_comparison.models import PredictionBundle
from bullpen_training.pitch_comparison.sequence_data import (
    PitcherSequenceIndex,
    PitchSequenceDataset,
    collate_sequences,
)
from bullpen_training.pitch_comparison.transformer_model import (
    PitchSequenceTransformer,
)


def _extract_embeddings(
    model: PitchSequenceTransformer,
    index: PitcherSequenceIndex,
    indices: np.ndarray,
    config: ExperimentConfig,
) -> np.ndarray:
    """Extract transformer embeddings for a set of row indices."""
    device = torch.device(config.resolve_device())
    model.to(device).eval()

    ds = PitchSequenceDataset(
        index, indices, config.seq_window, include_features=False,
    )
    loader = DataLoader(
        ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_sequences,
        num_workers=0,
    )

    embeddings: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, _feat, _targets in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            emb = model.encode(seq, pad_mask).cpu().numpy()
            embeddings.append(emb)

    return np.concatenate(embeddings, axis=0).astype(np.float32)


def train_hybrid(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    full_df: pd.DataFrame,
    transformer: PitchSequenceTransformer,
    index: PitcherSequenceIndex,
    config: ExperimentConfig,
) -> tuple[lgb.Booster, float]:
    t0 = time.perf_counter()

    train_indices = np.where(
        full_df["season"].isin(config.train_years).values
    )[0].astype(np.int32)
    val_indices = np.where(
        full_df["season"].isin(config.val_years).values
    )[0].astype(np.int32)

    print("  extracting train embeddings...", flush=True)
    train_emb = _extract_embeddings(
        transformer, index, train_indices, config,
    )
    print("  extracting val embeddings...", flush=True)
    val_emb = _extract_embeddings(
        transformer, index, val_indices, config,
    )

    # Combine embeddings + tabular features.
    feat_cols = list(FEATURE_COLS)
    train_tab = train_df[feat_cols].values.astype(np.float32)
    val_tab = val_df[feat_cols].values.astype(np.float32)

    train_x = np.hstack([train_emb, train_tab])
    val_x = np.hstack([val_emb, val_tab])

    train_y = train_df["pitch_type_int"].values.astype(int)
    val_y = val_df["pitch_type_int"].values.astype(int)

    print(
        f"  hybrid features: {train_x.shape[1]} "
        f"({train_emb.shape[1]} emb + {train_tab.shape[1]} tab)",
        flush=True,
    )

    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "seed": config.seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbose": -1,
    }
    dtrain = lgb.Dataset(train_x, label=train_y)
    dval = lgb.Dataset(val_x, label=val_y, reference=dtrain)
    booster = lgb.train(
        params,
        dtrain,
        num_boost_round=2000,
        valid_sets=[dtrain, dval],
        valid_names=["t", "v"],
        callbacks=[
            lgb.early_stopping(50, first_metric_only=True, verbose=False),
        ],
    )

    elapsed = time.perf_counter() - t0
    return booster, elapsed


def predict_hybrid(
    booster: lgb.Booster,
    transformer: PitchSequenceTransformer,
    index: PitcherSequenceIndex,
    test_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    test_indices = np.where(
        full_df["season"].isin(config.test_years).values
    )[0].astype(np.int32)
    test_emb = _extract_embeddings(
        transformer, index, test_indices, config,
    )
    feat_cols = list(FEATURE_COLS)
    test_tab = test_df[feat_cols].values.astype(np.float32)
    test_x = np.hstack([test_emb, test_tab])

    proba = np.asarray(booster.predict(test_x), dtype=np.float32)
    n = proba.shape[0]
    return PredictionBundle(
        pitch_type_proba=proba,
        velocity=np.full(n, np.nan, dtype=np.float32),
        outcome_proba=np.full((n, 6), 1 / 6, dtype=np.float32),
        ab_pitch_count=np.full(n, np.nan, dtype=np.float32),
        elapsed_train_sec=0.0,
    )
