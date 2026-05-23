"""Unit tests for the toy HR classifier.

Live training is covered by the manual run at end of Phase 1.3; here we
lock down the pure DataFrame transforms + the training loop on a tiny
separable fixture.
"""

from __future__ import annotations

from typing import cast

import numpy as np
import pandas as pd

from bullpen_training.battedball.features_toy import (
    FEATURES,
    TARGET,
    _engineer_features,
)
from bullpen_training.battedball.train_toy import _train_on_frame


def _synthetic_frame(n: int = 1000, *, rng_seed: int = 0) -> pd.DataFrame:
    """Build a clearly separable HR dataset: high launch_speed + mid-30s angle = HR."""
    rng = np.random.default_rng(rng_seed)
    df = pd.DataFrame(
        {
            "launch_speed_mph": rng.uniform(40, 115, n).astype("float32"),
            "launch_angle_deg": rng.uniform(-30, 60, n).astype("float32"),
            "release_speed_mph": rng.uniform(70, 100, n).astype("float32"),
            "park_id_encoded": rng.uniform(0.02, 0.08, n).astype("float32"),
            "stand_is_left": rng.integers(0, 2, n).astype("int8"),
        }
    )
    is_hr = (df["launch_speed_mph"] > 100) & df["launch_angle_deg"].between(20, 40)
    df[TARGET] = is_hr.astype("uint8")
    return cast(pd.DataFrame, df[[*list(FEATURES), TARGET]])


def test_engineer_features_emits_expected_columns() -> None:
    raw = pd.DataFrame(
        {
            "launch_speed_mph": [95.0, 80.0],
            "launch_angle_deg": [28.0, 12.0],
            "release_speed_mph": [92.0, 88.0],
            "park_id": ["NYY", "BOS"],
            "stand": ["L", "R"],
            TARGET: [1, 0],
        }
    )
    out = _engineer_features(raw)
    assert list(out.columns) == [*list(FEATURES), TARGET]
    assert out["stand_is_left"].tolist() == [1, 0]
    # Each park has 1 row; HR rate is 1.0 and 0.0 respectively.
    assert out["park_id_encoded"].tolist() == [1.0, 0.0]


def test_engineer_features_drops_null_release_speed() -> None:
    raw = pd.DataFrame(
        {
            "launch_speed_mph": [95.0, 80.0],
            "launch_angle_deg": [28.0, 12.0],
            "release_speed_mph": [92.0, None],
            "park_id": ["NYY", "BOS"],
            "stand": ["L", "R"],
            TARGET: [1, 0],
        }
    )
    out = _engineer_features(raw)
    assert len(out) == 1
    assert out[TARGET].iloc[0] == 1


def test_train_loop_learns_separable_signal() -> None:
    df = _synthetic_frame(n=2000, rng_seed=7)
    _booster, metrics = _train_on_frame(df)
    assert metrics["auc"] > 0.95, f"AUC unexpectedly low on synthetic: {metrics['auc']}"


def test_train_loop_is_deterministic() -> None:
    df = _synthetic_frame(n=1000, rng_seed=3)
    _, m1 = _train_on_frame(df.copy())
    _, m2 = _train_on_frame(df.copy())
    assert m1["auc"] == m2["auc"]


def test_train_loop_constant_label_yields_undefined_metric() -> None:
    """sklearn's roc_auc_score returns NaN (with a warning) on a single-class
    label rather than raising — codify that so a future sklearn change is
    caught by the test, not by surprise."""
    df = _synthetic_frame(n=400, rng_seed=1)
    df[TARGET] = 0
    _, metrics = _train_on_frame(df)
    # auc is NaN; NaN != NaN
    assert metrics["auc"] != metrics["auc"]
