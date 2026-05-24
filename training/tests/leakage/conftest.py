"""Synthetic-data fixtures shared by the 4 leakage tests (Phase 2a.3).

CI runs against pure-pandas synthetic data — no ClickHouse, no
pybaseball. Live correctness on real CH data is covered by the
end-of-leaf hand-trace + the integration smoke in the build script.

The four tests are the CI gate for CLAUDE.md rule 10. They MUST stay
fast (<5 min total) and deterministic so they can run on every push.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import cast

import numpy as np
import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    apply_te,
    compute_prior,
    compute_te,
)


@dataclass(frozen=True)
class SyntheticFold:
    train_start: date
    train_end: date
    test_start: date
    test_end: date


def _signal_label(pitcher_id: int, rng: np.random.Generator) -> str:
    """Each pitcher has a strong preferred class — gives target encoding real
    signal so the shuffled-target test has something to detect."""
    bias_idx = pitcher_id % len(LABEL_CLASSES)
    weights = np.full(len(LABEL_CLASSES), 0.05)
    weights[bias_idx] = 1.0 - 0.05 * (len(LABEL_CLASSES) - 1)
    return rng.choice(LABEL_CLASSES, p=weights)


def synthetic_pitches(
    *,
    n_pitchers: int = 30,
    n_batters: int = 50,
    n_days: int = 60,
    pitches_per_pitcher_per_day: int = 8,
    seed: int = 1337,
) -> pd.DataFrame:
    """Deterministic synthetic pitches: 60 days by 30 pitchers by 8 pitches/day
    = 14,400 rows. Small enough to keep tests <30s, big enough that the
    Bayesian smoothing (k=20) doesn't drown out the per-pitcher signal."""
    rng = np.random.default_rng(seed)
    base = date(2024, 4, 1)
    rows: list[dict[str, object]] = []
    game_pk_counter = 700_000
    for d in range(n_days):
        gd = base + timedelta(days=d)
        game_pk_counter += 1
        for pitcher_id in range(1, n_pitchers + 1):
            batter_id = int(rng.integers(1, n_batters + 1))
            for pitch_no in range(1, pitches_per_pitcher_per_day + 1):
                rows.append(
                    {
                        "game_id": game_pk_counter,
                        "at_bat_index": pitcher_id,
                        "pitch_number": pitch_no,
                        "game_date": gd,
                        "pitcher_id": pitcher_id,
                        "batter_id": batter_id,
                        "label": _signal_label(pitcher_id, rng),
                    }
                )
    return pd.DataFrame(rows)


def build_fold_inmem(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    """Pure-pandas mirror of `tier_1_2.build_fold_features` so leakage tests
    don't need ClickHouse. Returns the encoded test-window rows."""
    train_mask = (pitches["game_date"] >= fold.train_start) & (
        pitches["game_date"] <= fold.train_end
    )
    test_mask = (pitches["game_date"] >= fold.test_start) & (pitches["game_date"] <= fold.test_end)
    train_df = cast(pd.DataFrame, pitches.loc[train_mask].copy())
    test_df = cast(pd.DataFrame, pitches.loc[test_mask].copy())

    pitcher_te = compute_te(train_df, entity_col="pitcher_id", label_col="label")
    batter_te = compute_te(train_df, entity_col="batter_id", label_col="label")
    prior = compute_prior(train_df, "label")

    encoded = apply_te(
        test_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=prior
    )
    encoded = apply_te(
        encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=prior
    )
    return cast(pd.DataFrame, encoded)


@pytest.fixture(scope="session")
def fold() -> SyntheticFold:
    """30-day train window then 30-day test window — adjacent, non-overlapping."""
    return SyntheticFold(
        train_start=date(2024, 4, 1),
        train_end=date(2024, 4, 30),
        test_start=date(2024, 5, 1),
        test_end=date(2024, 5, 30),
    )


@pytest.fixture(scope="session")
def pitches() -> pd.DataFrame:
    return synthetic_pitches()


@pytest.fixture(scope="session")
def encoded(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    return build_fold_inmem(pitches, fold)
