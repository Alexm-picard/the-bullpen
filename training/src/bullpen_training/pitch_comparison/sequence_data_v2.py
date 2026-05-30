"""Enhanced sequence data with richer tokens and live-game features.

Token v2 adds to each historical pitch:
  - velocity relative to pitcher career avg (fatigue signal)
  - inning (game progression)
  - outs (situation)
  - base_state (leverage)
  - pitch_number_in_game (workload)

Live-game features computed from the sequence at __getitem__ time:
  - pitches_in_game (total thrown so far)
  - fastball_pct_game (FF+SI usage today)
  - velocity_trend (current vs first 10 pitches of game)
  - times_through_order_proxy (inning / 3, capped)
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset

from bullpen_training.pitch_comparison.data import (
    OUTCOME_CLASSES,
    PITCH_TYPE_CLASSES,
)

N_PT = len(PITCH_TYPE_CLASSES)
N_OC = len(OUTCOME_CLASSES)
# Token v2: type(8) + velo(1) + velo_vs_avg(1) + outcome(6) +
#   count(2) + inning(1) + outs(1) + base_state_norm(1) +
#   pitch_num_in_game(1) = 22
TOKEN_V2_DIM = N_PT + 1 + 1 + N_OC + 2 + 1 + 1 + 1 + 1

# Live-game features appended to tabular side.
LIVE_GAME_FEATURE_NAMES = (
    "pitches_in_game",
    "fastball_pct_game",
    "velo_trend_game",
    "times_through_order",
)
N_LIVE_FEATURES = len(LIVE_GAME_FEATURE_NAMES)


class EnrichedSequenceIndex:
    """Extended pitcher sequence index with richer per-row data."""

    def __init__(self, df: pd.DataFrame) -> None:
        pitcher_ids = df["pitcher_id"].values.astype(np.int32)
        unique_pitchers = np.unique(pitcher_ids)

        self.n_rows = len(df)

        # Core token data.
        self.pitch_type_int = df["pitch_type_int"].values.astype(np.int8)
        self.release_speed = df["release_speed_mph"].values.astype(
            np.float32,
        )
        self.outcome_int = df["outcome_int"].values.astype(np.int8)
        self.count_balls = df["count_balls"].values.astype(np.int8)
        self.count_strikes = df["count_strikes"].values.astype(np.int8)

        # New context data for enriched tokens.
        z8 = np.zeros(self.n_rows, dtype=np.int8)
        z64 = np.zeros(self.n_rows, dtype=np.int64)
        self.inning = df["inning"].values.astype(np.int8) if "inning" in df else z8.copy()
        self.outs = df["outs"].values.astype(np.int8) if "outs" in df else z8.copy()
        self.base_state = (
            df["base_state"].values.astype(np.int8) if "base_state" in df else z8.copy()
        )
        self.game_id = df["game_id"].values.astype(np.int64) if "game_id" in df else z64.copy()

        # Per-pitcher career avg velocity (for velo_vs_avg).
        self.pitcher_avg_velo = np.zeros(self.n_rows, dtype=np.float32)

        # Build per-pitcher sorted row indices (CSR-like).
        self.row_to_pitcher = pitcher_ids
        self.row_to_pos = np.zeros(self.n_rows, dtype=np.int32)
        self._flat_indices = np.empty(self.n_rows, dtype=np.int32)
        self.pitcher_to_start: dict[int, int] = {}
        self.pitcher_to_length: dict[int, int] = {}

        offset = 0
        for pid in unique_pitchers:
            mask = pitcher_ids == pid
            rows = np.where(mask)[0]
            n = len(rows)
            self._flat_indices[offset : offset + n] = rows
            self.pitcher_to_start[int(pid)] = offset
            self.pitcher_to_length[int(pid)] = n
            self.row_to_pos[rows] = np.arange(n, dtype=np.int32)

            # Career avg velocity for this pitcher.
            avg_v = float(np.nanmean(self.release_speed[rows]))
            self.pitcher_avg_velo[rows] = avg_v
            offset += n

        # Precompute the enriched token matrix once (vectorized). Mirrors
        # build_token_v2; the dataset gathers rows instead of looping. The
        # per-row pitch_num_in_game slot stays 0 (matching build_token_v2),
        # and live-game features remain per-item (they depend on the window).
        self.token_matrix = self._build_token_matrix_v2()

    def _build_token_matrix_v2(self) -> np.ndarray:
        """Vectorized equivalent of build_token_v2 over all rows."""
        n = self.n_rows
        tm = np.zeros((n, TOKEN_V2_DIM), dtype=np.float32)
        velo = self.release_speed
        pt = self.pitch_type_int.astype(np.int64)
        valid_pt = (pt >= 0) & (pt < N_PT)
        tm[np.nonzero(valid_pt)[0], pt[valid_pt]] = 1.0  # [0:N_PT]
        tm[:, N_PT] = (velo - 90.0) / 10.0  # velo
        avg = self.pitcher_avg_velo
        tm[:, N_PT + 1] = np.where(avg > 0, (velo - avg) / 5.0, 0.0)  # velo_vs_avg
        oc = self.outcome_int.astype(np.int64)
        valid_oc = (oc >= 0) & (oc < N_OC)
        base_oc = N_PT + 2
        tm[np.nonzero(valid_oc)[0], base_oc + oc[valid_oc]] = 1.0  # outcome
        c = base_oc + N_OC
        tm[:, c] = self.count_balls.astype(np.float32) / 3.0
        tm[:, c + 1] = self.count_strikes.astype(np.float32) / 2.0
        tm[:, c + 2] = self.inning.astype(np.float32) / 9.0
        tm[:, c + 3] = self.outs.astype(np.float32) / 2.0
        tm[:, c + 4] = self.base_state.astype(np.float32) / 7.0
        # c+5 (pitch_num_in_game) stays 0.0, matching build_token_v2.
        return tm

    def get_sequence_rows(
        self,
        global_row: int,
        window_size: int,
    ) -> np.ndarray:
        pid = int(self.row_to_pitcher[global_row])
        pos = int(self.row_to_pos[global_row])
        start = self.pitcher_to_start[pid]
        seq_start = max(0, pos - window_size)
        return self._flat_indices[start + seq_start : start + pos]

    def build_token_v2(self, row: int) -> np.ndarray:
        """Build enriched token vector for one historical pitch."""
        token = np.zeros(TOKEN_V2_DIM, dtype=np.float32)
        idx = 0

        # Pitch type one-hot (8).
        pt = int(self.pitch_type_int[row])
        if 0 <= pt < N_PT:
            token[pt] = 1.0
        idx += N_PT

        # Velocity normalized.
        token[idx] = (float(self.release_speed[row]) - 90.0) / 10.0
        idx += 1

        # Velocity relative to career avg (fatigue signal).
        avg_v = float(self.pitcher_avg_velo[row])
        if avg_v > 0:
            token[idx] = (float(self.release_speed[row]) - avg_v) / 5.0
        idx += 1

        # Outcome one-hot (6).
        oc = int(self.outcome_int[row])
        if 0 <= oc < N_OC:
            token[idx + oc] = 1.0
        idx += N_OC

        # Count state normalized.
        token[idx] = float(self.count_balls[row]) / 3.0
        token[idx + 1] = float(self.count_strikes[row]) / 2.0
        idx += 2

        # Inning normalized.
        token[idx] = float(self.inning[row]) / 9.0
        idx += 1

        # Outs normalized.
        token[idx] = float(self.outs[row]) / 2.0
        idx += 1

        # Base state normalized (0-7 → 0-1).
        token[idx] = float(self.base_state[row]) / 7.0
        idx += 1

        # Pitch number in game (rough proxy via position in
        # pitcher's game sequence). Normalized by ~100.
        # Computed from the sequence context in the dataset.
        token[idx] = 0.0  # filled by dataset
        idx += 1

        return token

    def compute_live_game_features(
        self,
        global_row: int,
        seq_rows: np.ndarray,
    ) -> np.ndarray:
        """Compute live-game rolling features from the sequence."""
        features = np.zeros(N_LIVE_FEATURES, dtype=np.float32)

        if len(seq_rows) == 0:
            return features

        current_game = self.game_id[global_row]

        # Find which sequence pitches are from the current game.
        game_mask = self.game_id[seq_rows] == current_game
        game_rows = seq_rows[game_mask]

        # pitches_in_game (normalized by ~100).
        n_game = len(game_rows)
        features[0] = min(n_game / 100.0, 1.5)

        if n_game > 0:
            game_types = self.pitch_type_int[game_rows]
            # fastball_pct_game (FF=0, SI=1 are fastballs).
            fb_count = int(np.sum((game_types == 0) | (game_types == 1)))
            features[1] = fb_count / max(n_game, 1)

            # velo_trend: avg of last 5 vs first 5 in game.
            game_velos = self.release_speed[game_rows]
            if n_game >= 10:
                early = float(np.mean(game_velos[:5]))
                late = float(np.mean(game_velos[-5:]))
                features[2] = (late - early) / 5.0  # normalized
            elif n_game >= 2:
                features[2] = (float(game_velos[-1]) - float(game_velos[0])) / 5.0

        # times_through_order proxy: inning / 3.
        features[3] = (
            min(
                float(self.inning[global_row]) / 3.0,
                3.0,
            )
            / 3.0
        )

        return features


class EnrichedSequenceDataset(Dataset):
    """Dataset yielding enriched tokens + live-game features."""

    def __init__(
        self,
        index: EnrichedSequenceIndex,
        valid_indices: np.ndarray,
        pitcher_ids: np.ndarray,
        batter_ids: np.ndarray,
        window_size: int = 20,
    ) -> None:
        self.index = index
        self.valid_indices = valid_indices
        self.pitcher_ids = pitcher_ids
        self.batter_ids = batter_ids
        self.window_size = window_size

    def __len__(self) -> int:
        return len(self.valid_indices)

    def __getitem__(
        self,
        idx: int,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, int, int, int]:
        """Returns (seq, pad_mask, live_features, pitcher_id, batter_id, target)."""
        global_row = int(self.valid_indices[idx])
        seq_rows = self.index.get_sequence_rows(
            global_row,
            self.window_size,
        )

        seq = np.zeros(
            (self.window_size, TOKEN_V2_DIM),
            dtype=np.float32,
        )
        pad_mask = np.ones(self.window_size, dtype=np.bool_)
        n_real = len(seq_rows)
        if n_real:
            seq[self.window_size - n_real :] = self.index.token_matrix[seq_rows]
            pad_mask[self.window_size - n_real :] = False

        live_feats = self.index.compute_live_game_features(
            global_row,
            seq_rows,
        )
        target = int(self.index.pitch_type_int[global_row])
        return (
            seq,
            pad_mask,
            live_feats,
            int(self.pitcher_ids[idx]),
            int(self.batter_ids[idx]),
            target,
        )


def collate_enriched(
    batch: list[tuple[np.ndarray, np.ndarray, np.ndarray, int, int, int]],
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    seqs = np.stack([b[0] for b in batch])
    masks = np.stack([b[1] for b in batch])
    lives = np.stack([b[2] for b in batch])
    pids = np.array([b[3] for b in batch], dtype=np.int64)
    bids = np.array([b[4] for b in batch], dtype=np.int64)
    targets = np.array([b[5] for b in batch], dtype=np.int64)
    return (
        torch.from_numpy(seqs),
        torch.from_numpy(masks),
        torch.from_numpy(lives),
        torch.from_numpy(pids),
        torch.from_numpy(bids),
        torch.from_numpy(targets),
    )
