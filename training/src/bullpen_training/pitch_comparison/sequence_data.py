"""Sequence-aware data loader for transformer pitch models.

Builds a per-pitcher index from the full DataFrame, then creates
rolling windows of N previous pitches on-the-fly at __getitem__
time. Never pre-materializes all sequences — O(N_rows) memory.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset

from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    OUTCOME_CLASSES,
    PITCH_TYPE_CLASSES,
)

N_PITCH_TYPES = len(PITCH_TYPE_CLASSES)
N_OUTCOMES = len(OUTCOME_CLASSES)
# Raw token: pitch_type_onehot(8) + velo(1) + outcome_onehot(6) +
#            balls(1) + strikes(1) = 17
RAW_TOKEN_DIM = N_PITCH_TYPES + 1 + N_OUTCOMES + 2


class PitcherSequenceIndex:
    """Lightweight index mapping each row to its pitcher's sequence."""

    def __init__(self, df: pd.DataFrame) -> None:
        pitcher_ids = df["pitcher_id"].values.astype(np.int32)
        unique_pitchers = np.unique(pitcher_ids)

        self.n_rows = len(df)
        self.pitcher_to_start: dict[int, int] = {}
        self.pitcher_to_length: dict[int, int] = {}

        # Extract column arrays for fast token construction.
        self.pitch_type_int = df["pitch_type_int"].values.astype(np.int8)
        self.release_speed = df["release_speed_mph"].values.astype(np.float32)
        self.outcome_int = df["outcome_int"].values.astype(np.int8)
        self.count_balls = df["count_balls"].values.astype(np.int8)
        self.count_strikes = df["count_strikes"].values.astype(np.int8)

        # Precompute the full token matrix once (vectorized). __getitem__
        # then gathers rows instead of building each token in a Python loop
        # — the dominant per-item cost. Layout matches build_token exactly.
        self.token_matrix = self._build_token_matrix()

        # Features array for hybrid model.
        feat_cols = [c for c in FEATURE_COLS if c in df.columns]
        self.features = df[feat_cols].values.astype(np.float32) if feat_cols else None

        # Build per-pitcher sorted row indices.
        # Data is already sorted by game_date/game_id/at_bat_index/pitch_number,
        # so within a pitcher group, the order is temporal.
        self.row_to_pitcher = pitcher_ids
        self.row_to_pos = np.zeros(self.n_rows, dtype=np.int32)

        # CSR-like: flat array of row indices grouped by pitcher.
        self._flat_indices = np.empty(self.n_rows, dtype=np.int32)
        offset = 0
        for pid in unique_pitchers:
            mask = pitcher_ids == pid
            rows = np.where(mask)[0]
            n = len(rows)
            self._flat_indices[offset : offset + n] = rows
            self.pitcher_to_start[int(pid)] = offset
            self.pitcher_to_length[int(pid)] = n
            self.row_to_pos[rows] = np.arange(n, dtype=np.int32)
            offset += n

    def _build_token_matrix(self) -> np.ndarray:
        """Vectorized equivalent of build_token over all rows."""
        n = self.n_rows
        tm = np.zeros((n, RAW_TOKEN_DIM), dtype=np.float32)
        pt = self.pitch_type_int.astype(np.int64)
        valid_pt = (pt >= 0) & (pt < N_PITCH_TYPES)
        tm[np.nonzero(valid_pt)[0], pt[valid_pt]] = 1.0
        tm[:, N_PITCH_TYPES] = (self.release_speed - 90.0) / 10.0
        oc = self.outcome_int.astype(np.int64)
        valid_oc = (oc >= 0) & (oc < N_OUTCOMES)
        tm[np.nonzero(valid_oc)[0], N_PITCH_TYPES + 1 + oc[valid_oc]] = 1.0
        tm[:, -2] = self.count_balls.astype(np.float32) / 3.0
        tm[:, -1] = self.count_strikes.astype(np.float32) / 2.0
        return tm

    def get_sequence_rows(
        self,
        global_row: int,
        window_size: int,
    ) -> np.ndarray:
        """Return up to window_size prior row indices for this row's pitcher."""
        pid = int(self.row_to_pitcher[global_row])
        pos = int(self.row_to_pos[global_row])
        start = self.pitcher_to_start[pid]
        seq_start = max(0, pos - window_size)
        return self._flat_indices[start + seq_start : start + pos]

    def build_token(self, row: int) -> np.ndarray:
        """Build a raw token vector for one historical pitch."""
        token = np.zeros(RAW_TOKEN_DIM, dtype=np.float32)
        pt = int(self.pitch_type_int[row])
        if 0 <= pt < N_PITCH_TYPES:
            token[pt] = 1.0
        # Normalized velocity (center ~90 mph, scale ~10).
        token[N_PITCH_TYPES] = (float(self.release_speed[row]) - 90.0) / 10.0
        oc = int(self.outcome_int[row])
        if 0 <= oc < N_OUTCOMES:
            token[N_PITCH_TYPES + 1 + oc] = 1.0
        token[-2] = float(self.count_balls[row]) / 3.0
        token[-1] = float(self.count_strikes[row]) / 2.0
        return token


class PitchSequenceDataset(Dataset):
    """PyTorch dataset that yields (sequence, features, target) tuples.

    Sequences are computed on-the-fly from the pitcher index.
    """

    def __init__(
        self,
        index: PitcherSequenceIndex,
        valid_indices: np.ndarray,
        window_size: int = 20,
        include_features: bool = False,
    ) -> None:
        self.index = index
        self.valid_indices = valid_indices
        self.window_size = window_size
        self.include_features = include_features

    def __len__(self) -> int:
        return len(self.valid_indices)

    def __getitem__(
        self,
        idx: int,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, int]:
        """Returns (sequence, padding_mask, features, target_pitch_type)."""
        global_row = int(self.valid_indices[idx])
        seq_rows = self.index.get_sequence_rows(global_row, self.window_size)

        # Build sequence tensor with left-padding (gather precomputed tokens).
        seq = np.zeros(
            (self.window_size, RAW_TOKEN_DIM),
            dtype=np.float32,
        )
        pad_mask = np.ones(self.window_size, dtype=np.bool_)
        n_real = len(seq_rows)
        if n_real:
            seq[self.window_size - n_real :] = self.index.token_matrix[seq_rows]
            pad_mask[self.window_size - n_real :] = False

        features = np.zeros(0, dtype=np.float32)
        if self.include_features and self.index.features is not None:
            features = self.index.features[global_row]

        target = int(self.index.pitch_type_int[global_row])
        return seq, pad_mask, features, target


def collate_sequences(
    batch: list[tuple[np.ndarray, np.ndarray, np.ndarray, int]],
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    seqs = np.stack([b[0] for b in batch])
    masks = np.stack([b[1] for b in batch])
    feats = np.stack([b[2] for b in batch])
    targets = np.array([b[3] for b in batch], dtype=np.int64)
    return (
        torch.from_numpy(seqs),
        torch.from_numpy(masks),
        torch.from_numpy(feats),
        torch.from_numpy(targets),
    )
