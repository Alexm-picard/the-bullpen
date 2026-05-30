"""Model 4: Pitcher + Batter Embedding Model.

Trainable entity embeddings that learn latent pitcher tendencies
(sequencing aggression, putaway preferences, count patterns) and
batter approach. Similar to NLP entity embeddings.
"""

from __future__ import annotations

import time

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    PITCH_TYPE_CLASSES,
)
from bullpen_training.pitch_comparison.models import PredictionBundle


class _EmbeddingDataset(Dataset):
    def __init__(
        self,
        features: np.ndarray,
        pitcher_ids: np.ndarray,
        batter_ids: np.ndarray,
        targets: np.ndarray,
    ) -> None:
        self.features = features.astype(np.float32)
        self.pitcher_ids = pitcher_ids.astype(np.int64)
        self.batter_ids = batter_ids.astype(np.int64)
        self.targets = targets.astype(np.int64)

    def __len__(self) -> int:
        return self.features.shape[0]

    def __getitem__(
        self, idx: int,
    ) -> tuple[np.ndarray, int, int, int]:
        return (
            self.features[idx],
            int(self.pitcher_ids[idx]),
            int(self.batter_ids[idx]),
            int(self.targets[idx]),
        )


def _collate_emb(
    batch: list[tuple[np.ndarray, int, int, int]],
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    feats = np.stack([b[0] for b in batch])
    pids = np.array([b[1] for b in batch], dtype=np.int64)
    bids = np.array([b[2] for b in batch], dtype=np.int64)
    tgts = np.array([b[3] for b in batch], dtype=np.int64)
    return (
        torch.from_numpy(feats),
        torch.from_numpy(pids),
        torch.from_numpy(bids),
        torch.from_numpy(tgts),
    )


class PitcherBatterEmbeddingModel(nn.Module):
    def __init__(
        self,
        n_pitchers: int,
        n_batters: int,
        n_features: int,
        *,
        pitcher_embed_dim: int = 32,
        batter_embed_dim: int = 16,
        hidden: int = 128,
        n_classes: int = len(PITCH_TYPE_CLASSES),
        dropout: float = 0.15,
    ) -> None:
        super().__init__()
        self.pitcher_emb = nn.Embedding(
            n_pitchers, pitcher_embed_dim, padding_idx=0,
        )
        self.batter_emb = nn.Embedding(
            n_batters, batter_embed_dim, padding_idx=0,
        )
        in_dim = pitcher_embed_dim + batter_embed_dim + n_features
        self.net = nn.Sequential(
            nn.Linear(in_dim, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, n_classes),
        )

    def forward(
        self,
        features: torch.Tensor,
        pitcher_ids: torch.Tensor,
        batter_ids: torch.Tensor,
    ) -> torch.Tensor:
        p_emb = self.pitcher_emb(pitcher_ids)
        b_emb = self.batter_emb(batter_ids)
        x = torch.cat([p_emb, b_emb, features], dim=-1)
        return self.net(x)


def _build_id_mapping(
    ids: np.ndarray,
) -> dict[int, int]:
    """Map raw IDs to sequential indices (0 = UNK)."""
    unique = sorted(set(ids.tolist()))
    return {raw: i + 1 for i, raw in enumerate(unique)}


def _apply_id_mapping(
    ids: np.ndarray, mapping: dict[int, int],
) -> np.ndarray:
    return np.array(
        [mapping.get(int(x), 0) for x in ids], dtype=np.int64,
    )


def train_embedding_model(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    config: ExperimentConfig,
) -> tuple[PitcherBatterEmbeddingModel, dict[int, int], dict[int, int], float]:
    torch.manual_seed(config.seed)
    device = torch.device(config.resolve_device())
    feat = list(FEATURE_COLS)

    # Build ID mappings from training data.
    pitcher_map = _build_id_mapping(train_df["pitcher_id"].values)
    batter_map = _build_id_mapping(train_df["batter_id"].values)
    n_pitchers = len(pitcher_map) + 1  # +1 for UNK at index 0
    n_batters = len(batter_map) + 1

    train_feat = train_df[feat].values.astype(np.float32)
    train_pid = _apply_id_mapping(
        train_df["pitcher_id"].values, pitcher_map,
    )
    train_bid = _apply_id_mapping(
        train_df["batter_id"].values, batter_map,
    )
    train_y = train_df["pitch_type_int"].values

    # Normalize features.
    feat_mean = train_feat.mean(axis=0)
    feat_std = train_feat.std(axis=0)
    feat_std[feat_std < 1e-6] = 1.0
    train_feat = (train_feat - feat_mean) / feat_std

    train_ds = _EmbeddingDataset(train_feat, train_pid, train_bid, train_y)
    train_loader = DataLoader(
        train_ds,
        batch_size=config.embed_batch_size,
        shuffle=True,
        collate_fn=_collate_emb,
        num_workers=0,
    )

    model = PitcherBatterEmbeddingModel(
        n_pitchers=n_pitchers,
        n_batters=n_batters,
        n_features=len(feat),
        pitcher_embed_dim=config.pitcher_embed_dim,
        batter_embed_dim=config.batter_embed_dim,
        hidden=config.embed_hidden,
    ).to(device)

    optimizer = torch.optim.Adam(
        model.parameters(), lr=config.embed_lr, weight_decay=1e-4,
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=config.embed_epochs,
    )

    t0 = time.perf_counter()
    for epoch in range(config.embed_epochs):
        model.train()
        loss_sum = 0.0
        n_batches = 0
        for batch_feat, batch_pid, batch_bid, batch_y in train_loader:
            batch_feat = batch_feat.to(device)
            batch_pid = batch_pid.to(device)
            batch_bid = batch_bid.to(device)
            batch_y = batch_y.to(device)

            logits = model(batch_feat, batch_pid, batch_bid)
            loss = F.cross_entropy(logits, batch_y)
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            loss_sum += loss.detach().item()
            n_batches += 1
        scheduler.step()

        if (epoch + 1) % 5 == 0 or epoch == 0:
            avg = loss_sum / max(n_batches, 1)
            print(
                f"  embed epoch {epoch + 1}/{config.embed_epochs}  "
                f"loss={avg:.4f}",
                flush=True,
            )

    elapsed = time.perf_counter() - t0
    # Store normalization params on model for inference.
    model._feat_mean = feat_mean  # type: ignore[attr-defined]
    model._feat_std = feat_std  # type: ignore[attr-defined]
    return model, pitcher_map, batter_map, elapsed


def predict_embedding_model(
    model: PitcherBatterEmbeddingModel,
    pitcher_map: dict[int, int],
    batter_map: dict[int, int],
    test_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    device = torch.device(config.resolve_device())
    model.to(device).eval()
    feat = list(FEATURE_COLS)

    test_feat = test_df[feat].values.astype(np.float32)
    feat_mean = model._feat_mean  # type: ignore[attr-defined]
    feat_std = model._feat_std  # type: ignore[attr-defined]
    test_feat = (test_feat - feat_mean) / feat_std

    test_pid = _apply_id_mapping(
        test_df["pitcher_id"].values, pitcher_map,
    )
    test_bid = _apply_id_mapping(
        test_df["batter_id"].values, batter_map,
    )

    all_probs: list[np.ndarray] = []
    bs = config.embed_batch_size * 2
    with torch.no_grad():
        for i in range(0, len(test_feat), bs):
            f = torch.from_numpy(test_feat[i : i + bs]).to(device)
            p = torch.from_numpy(test_pid[i : i + bs]).to(device)
            b = torch.from_numpy(test_bid[i : i + bs]).to(device)
            logits = model(f, p, b)
            probs = F.softmax(logits, dim=-1).cpu().numpy()
            all_probs.append(probs)

    proba = np.concatenate(all_probs, axis=0).astype(np.float32)
    n = proba.shape[0]
    return PredictionBundle(
        pitch_type_proba=proba,
        velocity=np.full(n, np.nan, dtype=np.float32),
        outcome_proba=np.full((n, 6), 1 / 6, dtype=np.float32),
        ab_pitch_count=np.full(n, np.nan, dtype=np.float32),
        elapsed_train_sec=0.0,
    )
