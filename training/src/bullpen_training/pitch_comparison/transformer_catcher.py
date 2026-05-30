"""Catcher-aware transformer: pitcher + catcher entity embeddings.

Tests whether catcher identity materially alters pitch sequencing.
The model learns a latent embedding for each catcher alongside the
pitcher embedding; both are concatenated with the pooled sequence
representation before the classification head.

Research question: does the same pitcher sequence differently with
different catchers? If catcher embeddings improve prediction, the
answer is yes.
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
from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES
from bullpen_training.pitch_comparison.models import PredictionBundle
from bullpen_training.pitch_comparison.sequence_data import (
    RAW_TOKEN_DIM,
    PitcherSequenceIndex,
    PitchSequenceDataset,
)
from bullpen_training.pitch_comparison.transformer_model import (
    PositionalEncoding,
    unmask_fully_padded,
)


def _build_id_map(ids: np.ndarray) -> dict[int, int]:
    return {raw: i + 1 for i, raw in enumerate(sorted(set(ids.tolist())))}


def _map_ids(ids: np.ndarray, mapping: dict[int, int]) -> np.ndarray:
    return np.array([mapping.get(int(x), 0) for x in ids], dtype=np.int64)


class _SeqCatcherDataset(Dataset):
    """Yields (seq, pad_mask, pitcher_id, catcher_id, target)."""

    def __init__(
        self,
        index: PitcherSequenceIndex,
        valid_indices: np.ndarray,
        pitcher_ids: np.ndarray,
        catcher_ids: np.ndarray,
        window_size: int = 20,
    ) -> None:
        self._inner = PitchSequenceDataset(
            index,
            valid_indices,
            window_size,
        )
        self._pitcher_ids = pitcher_ids
        self._catcher_ids = catcher_ids

    def __len__(self) -> int:
        return len(self._inner)

    def __getitem__(
        self,
        idx: int,
    ) -> tuple[np.ndarray, np.ndarray, int, int, int]:
        seq, pad_mask, _feat, target = self._inner[idx]
        return (
            seq,
            pad_mask,
            int(self._pitcher_ids[idx]),
            int(self._catcher_ids[idx]),
            target,
        )


def _collate_catcher(
    batch: list[tuple[np.ndarray, np.ndarray, int, int, int]],
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    seqs = np.stack([b[0] for b in batch])
    masks = np.stack([b[1] for b in batch])
    pids = np.array([b[2] for b in batch], dtype=np.int64)
    cids = np.array([b[3] for b in batch], dtype=np.int64)
    targets = np.array([b[4] for b in batch], dtype=np.int64)
    return (
        torch.from_numpy(seqs),
        torch.from_numpy(masks),
        torch.from_numpy(pids),
        torch.from_numpy(cids),
        torch.from_numpy(targets),
    )


class CatcherAwareTransformer(nn.Module):
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
        n_pitchers: int = 1,
        n_catchers: int = 1,
        pitcher_embed_dim: int = 32,
        catcher_embed_dim: int = 16,
        use_catcher: bool = True,
    ) -> None:
        super().__init__()
        self.d_model = d_model
        self.use_catcher = use_catcher
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
            encoder_layer,
            num_layers=num_layers,
        )
        self.pitcher_emb = nn.Embedding(
            n_pitchers,
            pitcher_embed_dim,
            padding_idx=0,
        )
        head_input = d_model + pitcher_embed_dim
        if use_catcher:
            self.catcher_emb = nn.Embedding(
                n_catchers,
                catcher_embed_dim,
                padding_idx=0,
            )
            head_input += catcher_embed_dim
        else:
            self.catcher_emb = None
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
        return (x * mask_expand).sum(dim=1) / mask_expand.sum(
            dim=1,
        ).clamp(min=1)

    def forward(
        self,
        seq: torch.Tensor,
        pad_mask: torch.Tensor,
        pitcher_ids: torch.Tensor,
        catcher_ids: torch.Tensor,
    ) -> torch.Tensor:
        pooled = self.encode(seq, pad_mask)
        parts = [pooled, self.pitcher_emb(pitcher_ids)]
        if self.use_catcher and self.catcher_emb is not None:
            parts.append(self.catcher_emb(catcher_ids))
        combined = torch.cat(parts, dim=-1)
        return self.head(self.dropout_layer(combined))


def train_catcher_transformer(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    full_df: pd.DataFrame,
    config: ExperimentConfig,
    *,
    use_catcher: bool = True,
    variant_name: str = "Catcher",
) -> tuple[CatcherAwareTransformer, PitcherSequenceIndex, dict[int, int], dict[int, int], float]:
    torch.manual_seed(config.seed)
    device = torch.device(config.resolve_device())

    print("  building sequence index...", flush=True)
    index = PitcherSequenceIndex(full_df)

    pitcher_map = _build_id_map(train_df["pitcher_id"].values)
    catcher_map = _build_id_map(train_df["catcher_id"].values)

    train_mask = full_df["season"].isin(config.train_years).values
    val_mask = full_df["season"].isin(config.val_years).values
    train_indices = np.where(train_mask)[0].astype(np.int32)
    val_indices = np.where(val_mask)[0].astype(np.int32)

    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_cid = _map_ids(full_df["catcher_id"].values, catcher_map)

    train_ds = _SeqCatcherDataset(
        index,
        train_indices,
        all_pid[train_indices],
        all_cid[train_indices],
        config.seq_window,
    )
    val_ds = _SeqCatcherDataset(
        index,
        val_indices,
        all_pid[val_indices],
        all_cid[val_indices],
        config.seq_window,
    )
    train_loader = DataLoader(
        train_ds,
        batch_size=config.transformer_batch_size,
        shuffle=True,
        collate_fn=_collate_catcher,
        **config.loader_kwargs(persistent=True),
    )
    val_loader = DataLoader(
        val_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=_collate_catcher,
        **config.loader_kwargs(persistent=True),
    )

    model = CatcherAwareTransformer(
        d_model=config.d_model,
        nhead=config.nhead,
        num_layers=config.num_encoder_layers,
        dim_feedforward=config.dim_feedforward,
        dropout=config.transformer_dropout,
        n_pitchers=len(pitcher_map) + 1,
        n_catchers=len(catcher_map) + 1,
        pitcher_embed_dim=config.pitcher_embed_dim,
        catcher_embed_dim=config.batter_embed_dim,
        use_catcher=use_catcher,
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

    best_val_loss = float("inf")
    best_state = None
    patience_counter = 0

    t0 = time.perf_counter()
    for epoch in range(config.transformer_epochs):
        model.train()
        loss_sum = 0.0
        n_b = 0
        for seq, pad_mask, pids, cids, targets in train_loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pids = pids.to(device)
            cids = cids.to(device)
            targets = targets.to(device)
            logits = model(seq, pad_mask, pids, cids)
            loss = F.cross_entropy(logits, targets)
            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            loss_sum += loss.detach().item()
            n_b += 1
        scheduler.step()
        train_loss = loss_sum / max(n_b, 1)

        model.eval()
        val_loss_sum = 0.0
        val_b = 0
        with torch.no_grad():
            for seq, pad_mask, pids, cids, targets in val_loader:
                seq = seq.to(device)
                pad_mask = pad_mask.to(device)
                pids = pids.to(device)
                cids = cids.to(device)
                targets = targets.to(device)
                logits = model(seq, pad_mask, pids, cids)
                val_loss_sum += F.cross_entropy(logits, targets).item()
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
    # Reap persistent worker processes before the caller moves on to the
    # memory-heavy extraction phase (lingering forked workers + the large
    # parent were part of the host OOM).
    import gc

    del train_loader, val_loader, train_ds, val_ds
    gc.collect()
    return model, index, pitcher_map, catcher_map, elapsed


def predict_catcher_transformer(
    model: CatcherAwareTransformer,
    index: PitcherSequenceIndex,
    pitcher_map: dict[int, int],
    catcher_map: dict[int, int],
    full_df: pd.DataFrame,
    config: ExperimentConfig,
) -> PredictionBundle:
    device = torch.device(config.resolve_device())
    model.to(device).eval()
    test_mask = full_df["season"].isin(config.test_years).values
    test_indices = np.where(test_mask)[0].astype(np.int32)
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_cid = _map_ids(full_df["catcher_id"].values, catcher_map)

    test_ds = _SeqCatcherDataset(
        index,
        test_indices,
        all_pid[test_indices],
        all_cid[test_indices],
        config.seq_window,
    )
    loader = DataLoader(
        test_ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=_collate_catcher,
        **config.loader_kwargs(force_sync=True),
    )
    all_probs: list[np.ndarray] = []
    with torch.no_grad():
        for seq, pad_mask, pids, cids, _t in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pids = pids.to(device)
            cids = cids.to(device)
            logits = model(seq, pad_mask, pids, cids)
            all_probs.append(F.softmax(logits, dim=-1).cpu().numpy())

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


def extract_catcher_hybrid_embeddings(
    model: CatcherAwareTransformer,
    index: PitcherSequenceIndex,
    pitcher_map: dict[int, int],
    catcher_map: dict[int, int],
    full_df: pd.DataFrame,
    indices: np.ndarray,
    config: ExperimentConfig,
) -> np.ndarray:
    """Pooled sequence + pitcher emb (+ catcher emb) for a hybrid meta-model.

    Mirrors the V2 hybrid extraction but appends the catcher embedding when
    the model was trained with it. This is the embedding the combined
    Catcher-Hybrid + Context model feeds into LightGBM.
    """
    device = torch.device(config.resolve_device())
    model.to(device).eval()
    indices = indices.astype(np.int32)
    all_pid = _map_ids(full_df["pitcher_id"].values, pitcher_map)
    all_cid = _map_ids(full_df["catcher_id"].values, catcher_map)
    ds = _SeqCatcherDataset(
        index,
        indices,
        all_pid[indices],
        all_cid[indices],
        config.seq_window,
    )
    loader = DataLoader(
        ds,
        batch_size=config.transformer_batch_size * 2,
        shuffle=False,
        collate_fn=_collate_catcher,
        **config.loader_kwargs(force_sync=True),
    )
    # Preallocate the output and fill row-slices instead of accumulating a
    # list of chunks + np.concatenate. On the 5.98M-row train split the
    # list+concatenate kept ~2x the result (chunks AND final array) live at
    # once — a multi-GB transient that overran the 15 GB WSL box. Writing
    # into a single preallocated array holds the result exactly once.
    out: np.ndarray | None = None
    write = 0
    with torch.no_grad():
        for seq, pad_mask, pids, cids, _t in loader:
            seq = seq.to(device)
            pad_mask = pad_mask.to(device)
            pooled = model.encode(seq, pad_mask)
            parts = [pooled, model.pitcher_emb(pids.to(device))]
            if model.use_catcher and model.catcher_emb is not None:
                parts.append(model.catcher_emb(cids.to(device)))
            chunk = torch.cat(parts, dim=-1).cpu().numpy().astype(np.float32)
            if out is None:
                out = np.empty((len(indices), chunk.shape[1]), dtype=np.float32)
            out[write : write + len(chunk)] = chunk
            write += len(chunk)
    if out is None:
        return np.empty((0, 0), dtype=np.float32)
    return out[:write]


def extract_catcher_embeddings(
    model: CatcherAwareTransformer,
    catcher_map: dict[int, int],
) -> tuple[np.ndarray, list[int]]:
    """Extract catcher embedding vectors for clustering/visualization."""
    if model.catcher_emb is None:
        return np.empty(0), []
    model.cpu().eval()
    ids = sorted(catcher_map.keys())
    mapped = [catcher_map[cid] for cid in ids]
    with torch.no_grad():
        idx_tensor = torch.tensor(mapped, dtype=torch.long)
        embs = model.catcher_emb(idx_tensor).numpy()
    return embs, ids
