"""Model 1: Transformer Sequence Encoder for next-pitch prediction.

Learns pitch sequencing patterns from rolling windows of a pitcher's
recent pitch history. Each historical pitch is tokenized as a vector
of (pitch_type, velocity, outcome, count state). A transformer encoder
processes the sequence and a classification head predicts the next
pitch type.
"""

from __future__ import annotations

import math
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
from bullpen_training.pitch_comparison.sequence_data import (
    RAW_TOKEN_DIM,
    PitcherSequenceIndex,
    PitchSequenceDataset,
    collate_sequences,
)


def unmask_fully_padded(pad_mask: torch.Tensor) -> torch.Tensor:
    """Force position 0 visible for any fully-padded row.

    A pitcher's first pitch has an empty history window, so its
    ``src_key_padding_mask`` row is all-True. Attention then softmaxes over
    an all-``-inf`` row and produces NaN, which poisons the pooled output
    (``NaN * 0 = NaN``) and the loss. Unmasking a single position keeps the
    attention numerically defined; callers must pool over the ORIGINAL mask
    so the forced position contributes nothing (the entity embedding carries
    those rows instead). Returns the original tensor untouched when no row is
    fully padded.
    """
    fully = pad_mask.all(dim=1)
    if bool(fully.any()):
        pad_mask = pad_mask.clone()
        pad_mask[fully, 0] = False
    return pad_mask


class PositionalEncoding(nn.Module):
    def __init__(self, d_model: int, max_len: int = 100) -> None:
        super().__init__()
        pe = torch.zeros(max_len, d_model)
        position = torch.arange(0, max_len, dtype=torch.float).unsqueeze(1)
        div_term = torch.exp(
            torch.arange(0, d_model, 2).float()
            * (-math.log(10000.0) / d_model)
        )
        pe[:, 0::2] = torch.sin(position * div_term)
        pe[:, 1::2] = torch.cos(position * div_term)
        self.register_buffer("pe", pe.unsqueeze(0))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return x + self.pe[:, : x.size(1)]


class PitchSequenceTransformer(nn.Module):
    def __init__(
        self,
        *,
        raw_token_dim: int = RAW_TOKEN_DIM,
        d_model: int = 64,
        nhead: int = 4,
        num_layers: int = 2,
        dim_feedforward: int = 128,
        n_classes: int = len(PITCH_TYPE_CLASSES),
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.d_model = d_model
        self.token_proj = nn.Linear(raw_token_dim, d_model)
        self.pos_enc = PositionalEncoding(d_model)
        encoder_layer = nn.TransformerEncoderLayer(
            d_model=d_model,
            nhead=nhead,
            dim_feedforward=dim_feedforward,
            dropout=dropout,
            batch_first=True,
        )
        self.encoder = nn.TransformerEncoder(
            encoder_layer, num_layers=num_layers,
        )
        self.head = nn.Linear(d_model, n_classes)
        self.dropout = nn.Dropout(dropout)

    def encode(
        self,
        seq: torch.Tensor,
        pad_mask: torch.Tensor,
    ) -> torch.Tensor:
        """Return pooled sequence embedding (batch, d_model)."""
        x = self.token_proj(seq)
        x = self.pos_enc(x)
        x = self.encoder(x, src_key_padding_mask=unmask_fully_padded(pad_mask))
        # Mean-pool over non-padded positions (ORIGINAL mask).
        mask_expand = (~pad_mask).unsqueeze(-1).float()
        pooled = (x * mask_expand).sum(dim=1) / mask_expand.sum(
            dim=1
        ).clamp(min=1)
        return pooled

    def forward(
        self,
        seq: torch.Tensor,
        pad_mask: torch.Tensor,
    ) -> torch.Tensor:
        pooled = self.encode(seq, pad_mask)
        return self.head(self.dropout(pooled))


def train_transformer(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> tuple[PitchSequenceTransformer, PitcherSequenceIndex, float]:
    torch.manual_seed(config.seed)
    device = torch.device(config.resolve_device())

    print("  building pitcher sequence index...", flush=True)
    index = PitcherSequenceIndex(full_df)

    train_indices = np.where(
        full_df["season"].isin(config.train_years).values
    )[0].astype(np.int32)
    val_indices = np.where(
        full_df["season"].isin(config.val_years).values
    )[0].astype(np.int32)

    train_ds = PitchSequenceDataset(
        index, train_indices, config.seq_window,
    )
    has_val = len(val_indices) > 0 and set(config.val_years) != set(config.train_years)
    val_ds = PitchSequenceDataset(
        index, val_indices if has_val else train_indices[:0],
        config.seq_window,
    )
    train_loader = DataLoader(
        train_ds,
        batch_size=config.transformer_batch_size,
        shuffle=True,
        collate_fn=collate_sequences,
        **config.loader_kwargs(persistent=True),
    )
    val_loader = DataLoader(
        val_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_sequences,
        **config.loader_kwargs(persistent=has_val),
    )

    model = PitchSequenceTransformer(
        d_model=config.d_model,
        nhead=config.nhead,
        num_layers=config.num_encoder_layers,
        dim_feedforward=config.dim_feedforward,
        dropout=config.transformer_dropout,
    ).to(device)

    optimizer = torch.optim.Adam(
        model.parameters(), lr=config.transformer_lr, weight_decay=1e-4,
    )
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=config.transformer_epochs,
    )

    best_val_loss = float("inf")
    best_state = None
    patience = 5
    patience_counter = 0

    t0 = time.perf_counter()
    for epoch in range(config.transformer_epochs):
        model.train()
        train_loss_sum = 0.0
        n_batches = 0
        for seq, pad_mask, _feat, targets in train_loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            targets = targets.to(device)

            logits = model(seq, pad_mask)
            loss = F.cross_entropy(logits, targets)
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            train_loss_sum += loss.detach().item()
            n_batches += 1
        scheduler.step()

        # Validation.
        train_loss = train_loss_sum / max(n_batches, 1)
        val_loss = float("nan")
        if has_val:
            model.eval()
            val_loss_sum = 0.0
            val_batches = 0
            with torch.no_grad():
                for seq, pad_mask, _feat, targets in val_loader:
                    seq = seq.to(device)
                    pad_mask = pad_mask.to(device)
                    targets = targets.to(device)
                    logits = model(seq, pad_mask)
                    val_loss_sum += F.cross_entropy(
                        logits, targets,
                    ).item()
                    val_batches += 1
            val_loss = val_loss_sum / max(val_batches, 1)

        if has_val and val_loss < best_val_loss:
            best_val_loss = val_loss
            best_state = {
                k: v.cpu().clone() for k, v in model.state_dict().items()
            }
            patience_counter = 0
        else:
            patience_counter += 1

        if (epoch + 1) % 5 == 0 or epoch == 0:
            print(
                f"  epoch {epoch + 1}/{config.transformer_epochs}  "
                f"train_loss={train_loss:.4f}  "
                f"val_loss={val_loss:.4f}",
                flush=True,
            )

        if has_val and patience_counter >= patience:
            print(
                f"  early stopping at epoch {epoch + 1}", flush=True,
            )
            break

    if best_state is not None:
        model.load_state_dict(best_state)
    elapsed = time.perf_counter() - t0
    return model, index, elapsed


def predict_transformer(
    model: PitchSequenceTransformer,
    index: PitcherSequenceIndex,
    df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    device = torch.device(config.resolve_device())
    model.to(device).eval()

    test_indices = np.where(
        full_df["season"].isin(config.test_years).values
    )[0].astype(np.int32)
    test_ds = PitchSequenceDataset(
        index, test_indices, config.seq_window,
    )
    test_loader = DataLoader(
        test_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=collate_sequences,
        **config.loader_kwargs(force_sync=True),
    )

    all_probs: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, _feat, _targets in test_loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            logits = model(seq, pad_mask)
            probs = F.softmax(logits, dim=-1).cpu().numpy()
            all_probs.append(probs)

    proba = np.concatenate(all_probs, axis=0).astype(np.float32)
    # Guard: replace any NaN with uniform distribution.
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
