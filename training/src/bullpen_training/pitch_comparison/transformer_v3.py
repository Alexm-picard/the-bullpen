"""TransformerV3: Context-aware sequence model.

Enhancements over V2:
  - Richer 22-dim tokens (velocity fatigue, inning, outs, base state)
  - Live-game features fused into the classification head
  - Pitcher embeddings (retained from V2)
  - Temporal sample weighting (recent seasons weighted higher)
"""

from __future__ import annotations

import time

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader

from bullpen_training.pitch_comparison.config import ExperimentConfig
from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES
from bullpen_training.pitch_comparison.models import PredictionBundle
from bullpen_training.pitch_comparison.sequence_data_v2 import (
    LIVE_GAME_FEATURE_NAMES,
    TOKEN_V2_DIM,
    EnrichedSequenceDataset,
    EnrichedSequenceIndex,
    collate_enriched,
)
from bullpen_training.pitch_comparison.transformer_model import (
    PositionalEncoding,
    unmask_fully_padded,
)


def _build_id_map(ids: np.ndarray) -> dict[int, int]:
    return {raw: i + 1 for i, raw in enumerate(sorted(set(ids.tolist())))}


def _map_ids(ids: np.ndarray, mapping: dict[int, int]) -> np.ndarray:
    return np.array(
        [mapping.get(int(x), 0) for x in ids],
        dtype=np.int64,
    )


class TransformerV3(nn.Module):
    """Context-aware transformer with enriched tokens + live features."""

    def __init__(
        self,
        *,
        token_dim: int = TOKEN_V2_DIM,
        d_model: int = 64,
        nhead: int = 4,
        num_layers: int = 2,
        dim_feedforward: int = 128,
        n_classes: int = len(PITCH_TYPE_CLASSES),
        dropout: float = 0.1,
        n_pitchers: int = 1,
        pitcher_embed_dim: int = 32,
        n_live_features: int = len(LIVE_GAME_FEATURE_NAMES),
    ) -> None:
        super().__init__()
        self.d_model = d_model
        self.token_proj = nn.Linear(token_dim, d_model)
        self.pos_enc = PositionalEncoding(d_model)
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True,
        )
        self.encoder = nn.TransformerEncoder(
            encoder_layer,
            num_layers=num_layers,
        )
        self.pitcher_emb = nn.Embedding(
            n_pitchers,
            pitcher_embed_dim,
            padding_idx=0,
        )
        head_input = d_model + pitcher_embed_dim + n_live_features
        self.head = nn.Sequential(
            nn.Linear(head_input, d_model),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(d_model, n_classes),
        )
        self.dropout_layer = nn.Dropout(dropout)

    def encode(
        self,
        seq: torch.Tensor,
        pad_mask: torch.Tensor,
    ) -> torch.Tensor:
        x = self.token_proj(seq)
        x = self.pos_enc(x)
        x = self.encoder(x, src_key_padding_mask=unmask_fully_padded(pad_mask))
        mask_expand = (~pad_mask).unsqueeze(-1).float()
        pooled = (x * mask_expand).sum(dim=1) / mask_expand.sum(
            dim=1,
        ).clamp(min=1)
        return pooled

    def forward(
        self,
        seq: torch.Tensor,
        pad_mask: torch.Tensor,
        live_features: torch.Tensor,
        pitcher_ids: torch.Tensor,
    ) -> torch.Tensor:
        pooled = self.encode(seq, pad_mask)
        p_emb = self.pitcher_emb(pitcher_ids)
        combined = torch.cat(
            [pooled, p_emb, live_features],
            dim=-1,
        )
        return self.head(self.dropout_layer(combined))


def _compute_temporal_weights(
    seasons: np.ndarray,
    decay: float = 0.85,
) -> np.ndarray:
    """Weight recent seasons higher. 2023=1.0, 2022=0.85, 2021=0.72..."""
    max_season = int(seasons.max())
    weights = np.ones(len(seasons), dtype=np.float32)
    for i, s in enumerate(seasons):
        years_ago = max_season - int(s)
        weights[i] = decay**years_ago
    return weights


def train_transformer_v3(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
    *,
    variant_name: str = "TransformerV3",
    use_temporal_weights: bool = True,
) -> tuple[TransformerV3, EnrichedSequenceIndex, dict[int, int], float]:
    torch.manual_seed(config.seed)
    device = torch.device(config.resolve_device())

    print("  building enriched sequence index...", flush=True)
    index = EnrichedSequenceIndex(full_df)

    pitcher_map = _build_id_map(train_df["pitcher_id"].values)
    all_pitcher_mapped = _map_ids(
        full_df["pitcher_id"].values,
        pitcher_map,
    )
    all_batter_mapped = np.zeros(len(full_df), dtype=np.int64)

    train_mask = full_df["season"].isin(config.train_years).values
    val_mask = full_df["season"].isin(config.val_years).values
    train_indices = np.where(train_mask)[0].astype(np.int32)
    val_indices = np.where(val_mask)[0].astype(np.int32)

    train_ds = EnrichedSequenceDataset(
        index,
        train_indices,
        all_pitcher_mapped[train_indices],
        all_batter_mapped[train_indices],
        config.seq_window,
    )
    val_ds = EnrichedSequenceDataset(
        index,
        val_indices,
        all_pitcher_mapped[val_indices],
        all_batter_mapped[val_indices],
        config.seq_window,
    )

    train_loader = DataLoader(
        train_ds,
        batch_size=config.transformer_batch_size,
        shuffle=True,
        collate_fn=collate_enriched,
        **config.loader_kwargs(persistent=True),
    )
    val_loader = DataLoader(
        val_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_enriched,
        **config.loader_kwargs(persistent=True),
    )

    model = TransformerV3(
        d_model=config.d_model,
        nhead=config.nhead,
        num_layers=config.num_encoder_layers,
        dim_feedforward=config.dim_feedforward,
        dropout=config.transformer_dropout,
        n_pitchers=len(pitcher_map) + 1,
        pitcher_embed_dim=config.pitcher_embed_dim,
    ).to(device)

    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=config.transformer_lr,
        weight_decay=1e-4,
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer,
        T_max=config.transformer_epochs,
    )

    # Temporal weights for training samples.
    if use_temporal_weights:
        train_seasons = full_df.loc[train_mask, "season"].values
        sample_weights = _compute_temporal_weights(train_seasons)
    else:
        sample_weights = None

    best_val_loss = float("inf")
    best_state = None
    patience_counter = 0

    t0 = time.perf_counter()
    for epoch in range(config.transformer_epochs):
        model.train()
        loss_sum = 0.0
        n_b = 0
        for batch_idx, (seq, pad_mask, live, pids, _bids, targets) in enumerate(train_loader):
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            live = live.to(device)
            pids = pids.to(device)
            targets = targets.to(device)

            logits = model(seq, pad_mask, live, pids)

            if sample_weights is not None:
                bs = len(targets)
                start = batch_idx * config.transformer_batch_size
                end = min(start + bs, len(sample_weights))
                w = torch.from_numpy(
                    sample_weights[start:end],
                ).to(device)
                if len(w) == bs:
                    loss = (F.cross_entropy(logits, targets, reduction="none") * w).mean()
                else:
                    loss = F.cross_entropy(logits, targets)
            else:
                loss = F.cross_entropy(logits, targets)

            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            loss_sum += loss.detach().item()
            n_b += 1
        scheduler.step()
        train_loss = loss_sum / max(n_b, 1)

        # Validation.
        model.eval()
        val_loss_sum = 0.0
        val_b = 0
        with torch.no_grad():
            for seq, pad_mask, live, pids, _bids, targets in val_loader:
                seq = seq.to(device)
                pad_mask = pad_mask.to(device)
                live = live.to(device)
                pids = pids.to(device)
                targets = targets.to(device)
                logits = model(seq, pad_mask, live, pids)
                val_loss_sum += F.cross_entropy(
                    logits,
                    targets,
                ).item()
                val_b += 1
        val_loss = val_loss_sum / max(val_b, 1)

        if val_loss < best_val_loss:
            best_val_loss = val_loss
            best_state = {k: v.cpu().clone() for k, v in model.state_dict().items()}
            patience_counter = 0
        else:
            patience_counter += 1

        if (epoch + 1) % 5 == 0 or epoch == 0:
            print(
                f"  {variant_name} epoch {epoch + 1}/{config.transformer_epochs}  "
                f"train={train_loss:.4f}  val={val_loss:.4f}",
                flush=True,
            )
        if patience_counter >= 5:
            print(f"  early stopping at epoch {epoch + 1}", flush=True)
            break

    if best_state is not None:
        model.load_state_dict(best_state)
    elapsed = time.perf_counter() - t0
    return model, index, pitcher_map, elapsed


def predict_transformer_v3(
    model: TransformerV3,
    index: EnrichedSequenceIndex,
    pitcher_map: dict[int, int],
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    device = torch.device(config.resolve_device())
    model.to(device).eval()

    test_mask = full_df["season"].isin(config.test_years).values
    test_indices = np.where(test_mask)[0].astype(np.int32)
    all_pitcher_mapped = _map_ids(
        full_df["pitcher_id"].values,
        pitcher_map,
    )
    all_batter_mapped = np.zeros(len(full_df), dtype=np.int64)

    test_ds = EnrichedSequenceDataset(
        index,
        test_indices,
        all_pitcher_mapped[test_indices],
        all_batter_mapped[test_indices],
        config.seq_window,
    )
    loader = DataLoader(
        test_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_enriched,
        **config.loader_kwargs(force_sync=True),
    )

    all_probs: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, live, pids, _bids, _targets in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            live = live.to(device)
            pids = pids.to(device)
            logits = model(seq, pad_mask, live, pids)
            probs = F.softmax(logits, dim=-1).cpu().numpy()
            all_probs.append(probs)

    proba = np.concatenate(all_probs, axis=0).astype(np.float32)
    nan_rows = np.isnan(proba).any(axis=1)
    if nan_rows.any():
        proba[nan_rows] = 1.0 / proba.shape[1]
    n = proba.shape[0]
    return PredictionBundle(
        pitch_type_proba=proba,
        velocity=np.full(n, np.nan, dtype=np.float32),
        outcome_proba=np.full((n, 6), 1 / 6, dtype=np.float32),
        ab_pitch_count=np.full(n, np.nan, dtype=np.float32),
        elapsed_train_sec=0.0,
    )


def extract_v3_embeddings(
    model: TransformerV3,
    index: EnrichedSequenceIndex,
    pitcher_map: dict[int, int],
    full_df: pd.DataFrame,
    indices: np.ndarray,
    config: ExperimentConfig,
) -> np.ndarray:
    """Extract sequence + pitcher embeddings for hybrid meta-model."""
    device = torch.device(config.resolve_device())
    model.to(device).eval()
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_bid = np.zeros(len(full_df), dtype=np.int64)

    ds = EnrichedSequenceDataset(
        index,
        indices,
        all_pid[indices],
        all_bid[indices],
        config.seq_window,
    )
    loader = DataLoader(
        ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_enriched,
        **config.loader_kwargs(force_sync=True),
    )
    embs: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, live, pids, _bids, _t in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pids = pids.to(device)
            live = live.to(device)
            pooled = model.encode(seq, pad_mask)
            p_emb = model.pitcher_emb(pids)
            combined = torch.cat([pooled, p_emb, live], dim=-1)
            embs.append(combined.cpu().numpy())
    return np.concatenate(embs, axis=0).astype(np.float32)
