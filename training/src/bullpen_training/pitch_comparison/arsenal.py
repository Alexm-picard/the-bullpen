"""Pitcher arsenal analysis and dynamic masking.

Computes per-pitcher pitch usage stats (overall, by count, by batter
handedness, by inning bucket) from training data only. Provides hard
masking and soft Bayesian prior weighting for pitch predictions.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Final

import numpy as np
import pandas as pd

from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES

N_PITCH_TYPES: Final[int] = len(PITCH_TYPE_CLASSES)
INNING_BUCKETS: Final[dict[str, tuple[int, ...]]] = {
    "early": (1, 2, 3),
    "mid": (4, 5, 6),
    "late": (7, 8, 9, 10, 11, 12, 13, 14, 15),
}


@dataclass
class ArsenalConfig:
    active_threshold: float = 0.02
    min_pitches_career: int = 50
    min_pitches_context: int = 10
    smooth_alpha: float = 0.01


@dataclass
class PitcherArsenal:
    pitcher_id: int
    overall_mix: np.ndarray  # (8,)
    active_mask: np.ndarray  # (8,) bool
    arsenal_size: int
    entropy: float
    by_count: dict[tuple[int, int], np.ndarray] = field(
        default_factory=dict,
    )
    by_hand: dict[str, np.ndarray] = field(default_factory=dict)
    by_inning: dict[str, np.ndarray] = field(default_factory=dict)


def _compute_mix(
    series: pd.Series,
    n_classes: int = N_PITCH_TYPES,
    alpha: float = 0.01,
) -> np.ndarray:
    """Dirichlet-smoothed pitch mix from a Series of pitch_type_int."""
    counts = np.zeros(n_classes, dtype=np.float64)
    for v in series:
        idx = int(v)
        if 0 <= idx < n_classes:
            counts[idx] += 1
    total = counts.sum() + alpha * n_classes
    return ((counts + alpha) / total).astype(np.float32)


def _entropy(mix: np.ndarray) -> float:
    p = mix[mix > 0]
    return float(-np.sum(p * np.log2(p)))


def compute_arsenal_stats(
    df: pd.DataFrame,
    train_mask: np.ndarray,
    config: ArsenalConfig | None = None,
) -> dict[int, PitcherArsenal]:
    """Compute per-pitcher arsenal stats from training data only."""
    if config is None:
        config = ArsenalConfig()
    train = df.loc[train_mask]
    alpha = config.smooth_alpha

    arsenals: dict[int, PitcherArsenal] = {}
    grouped = train.groupby("pitcher_id")

    for pid, group in grouped:
        pid = int(pid)
        if len(group) < config.min_pitches_career:
            continue

        overall = _compute_mix(group["pitch_type_int"], alpha=alpha)
        active = overall >= config.active_threshold
        arsenal_size = int(active.sum())
        ent = _entropy(overall)

        # By count.
        by_count: dict[tuple[int, int], np.ndarray] = {}
        if "count_balls" in group.columns and "count_strikes" in group.columns:
            for (b, s), sub in group.groupby(
                ["count_balls", "count_strikes"],
            ):
                if len(sub) >= config.min_pitches_context:
                    by_count[(int(b), int(s))] = _compute_mix(
                        sub["pitch_type_int"], alpha=alpha,
                    )

        # By batter handedness.
        by_hand: dict[str, np.ndarray] = {}
        if "batter_stand_int" in group.columns:
            for hand_int, sub in group.groupby("batter_stand_int"):
                hand = "R" if int(hand_int) == 1 else "L"
                if len(sub) >= config.min_pitches_context:
                    by_hand[hand] = _compute_mix(
                        sub["pitch_type_int"], alpha=alpha,
                    )

        # By inning bucket.
        by_inning: dict[str, np.ndarray] = {}
        if "inning" in group.columns:
            innings = group["inning"].values.astype(int)
            for bucket_name, bucket_innings in INNING_BUCKETS.items():
                bucket_mask = np.isin(innings, bucket_innings)
                sub = group.loc[bucket_mask]
                if len(sub) >= config.min_pitches_context:
                    by_inning[bucket_name] = _compute_mix(
                        sub["pitch_type_int"], alpha=alpha,
                    )

        arsenals[pid] = PitcherArsenal(
            pitcher_id=pid,
            overall_mix=overall,
            active_mask=active,
            arsenal_size=arsenal_size,
            entropy=ent,
            by_count=by_count,
            by_hand=by_hand,
            by_inning=by_inning,
        )

    return arsenals


def _get_contextual_prior(
    arsenal: PitcherArsenal,
    balls: int,
    strikes: int,
    batter_hand_int: int,
    inning: int,
) -> np.ndarray:
    """Get the best available contextual prior for a pitch row."""
    # Try count-specific first.
    count_key = (int(balls), int(strikes))
    if count_key in arsenal.by_count:
        return arsenal.by_count[count_key]
    # Fall back to overall.
    return arsenal.overall_mix


def _global_prior(df: pd.DataFrame, train_mask: np.ndarray) -> np.ndarray:
    """Compute a global pitch mix from all training data."""
    return _compute_mix(df.loc[train_mask, "pitch_type_int"])


def arsenal_features_for_df(
    df: pd.DataFrame,
    arsenals: dict[int, PitcherArsenal],
    train_mask: np.ndarray,
) -> pd.DataFrame:
    """Build arsenal feature columns for every row in df.

    Returns a DataFrame with the same index as df containing:
    - arsenal_size, arsenal_entropy
    - context_prior_FF .. context_prior_OTHER (8 cols)
    - pitch_predictability (max of context prior)
    """
    n = len(df)
    global_mix = _global_prior(df, train_mask)

    # Pre-allocate arrays.
    size_arr = np.zeros(n, dtype=np.float32)
    entropy_arr = np.zeros(n, dtype=np.float32)
    priors = np.tile(global_mix, (n, 1)).astype(np.float32)
    predictability = np.zeros(n, dtype=np.float32)
    active_masks = np.ones((n, N_PITCH_TYPES), dtype=np.float32)

    pitcher_ids = df["pitcher_id"].values
    balls = df["count_balls"].values
    strikes = df["count_strikes"].values

    # Vectorized: build per-pitcher arrays, then scatter to rows.
    # Group rows by pitcher_id for batch assignment.
    pid_to_rows: dict[int, np.ndarray] = {}
    for pid in arsenals:
        mask = pitcher_ids == pid
        if mask.any():
            pid_to_rows[pid] = np.where(mask)[0]

    for pid, row_indices in pid_to_rows.items():
        arsenal = arsenals[pid]
        size_arr[row_indices] = arsenal.arsenal_size
        entropy_arr[row_indices] = arsenal.entropy
        active_masks[row_indices] = arsenal.active_mask.astype(np.float32)

        # Try count-specific priors per unique count in this pitcher's rows.
        row_balls = balls[row_indices]
        row_strikes = strikes[row_indices]
        unique_counts = set(
            zip(row_balls.tolist(), row_strikes.tolist(), strict=True),
        )
        for b_val, s_val in unique_counts:
            count_key = (int(b_val), int(s_val))
            ctx = arsenal.by_count.get(count_key, arsenal.overall_mix)
            count_mask = (row_balls == b_val) & (row_strikes == s_val)
            sub_indices = row_indices[count_mask]
            priors[sub_indices] = ctx
            predictability[sub_indices] = float(ctx.max())

    result = pd.DataFrame(index=df.index)
    result["arsenal_size"] = size_arr
    result["arsenal_entropy"] = entropy_arr
    result["pitch_predictability"] = predictability
    for j, pt_name in enumerate(PITCH_TYPE_CLASSES):
        result[f"context_prior_{pt_name}"] = priors[:, j]
        result[f"active_{pt_name}"] = active_masks[:, j]
    return result


ARSENAL_FEATURE_COLS: Final[tuple[str, ...]] = (
    "arsenal_size",
    "arsenal_entropy",
    "pitch_predictability",
    *(f"context_prior_{pt}" for pt in PITCH_TYPE_CLASSES),
)


def apply_hard_mask(
    proba: np.ndarray,
    active_masks: np.ndarray,
) -> np.ndarray:
    """Zero out pitches not in the pitcher's arsenal, renormalize."""
    masked = proba * active_masks
    row_sums = masked.sum(axis=1, keepdims=True)
    row_sums = np.maximum(row_sums, 1e-9)
    return masked / row_sums


def apply_soft_prior(
    logits: np.ndarray,
    context_priors: np.ndarray,
    temperature: float = 1.0,
) -> np.ndarray:
    """Add log-prior to logits for Bayesian posterior weighting."""
    log_prior = np.log(context_priors + 1e-9)
    adjusted = logits + log_prior / max(temperature, 1e-6)
    # Softmax.
    shifted = adjusted - adjusted.max(axis=1, keepdims=True)
    exp = np.exp(shifted)
    return exp / exp.sum(axis=1, keepdims=True)


__all__ = (
    "ARSENAL_FEATURE_COLS",
    "ArsenalConfig",
    "PitcherArsenal",
    "apply_hard_mask",
    "apply_soft_prior",
    "arsenal_features_for_df",
    "compute_arsenal_stats",
)
