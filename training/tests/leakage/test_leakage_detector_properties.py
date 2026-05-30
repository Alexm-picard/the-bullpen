"""Property tests for the leakage detector itself (CLAUDE.md rule 10, plan S1d).

The four leakage tests (no-future-contamination, shuffled-target,
calendar-date-trace, id-consistency) are only worth anything if their *checks
have teeth* — i.e. they would actually FIRE on a leaky pipeline and stay quiet on
a clean one. A check that passes no matter what is theatre.

These Hypothesis property tests generate random synthetic folds and assert the
meta-property across all of them:

  * the CLEAN target-encoding pipeline (TE computed from the train window only,
    via conftest.build_fold_inmem) satisfies each leakage invariant, AND
  * a deliberately-LEAKY pipeline (TE computed over the full train+test data,
    ignoring the temporal cutoff) VIOLATES it.

If both halves hold for arbitrary data, the leakage checks are non-vacuous: they
distinguish leak from no-leak rather than always passing.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import cast

import numpy as np
import pandas as pd
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    apply_te,
    compute_prior,
    compute_te,
)
from tests.leakage.conftest import (
    SyntheticFold,
    build_fold_inmem,
    synthetic_pitches,
)

# Keep examples small so the suite stays well under the rule-10 <5min budget.
_SETTINGS = settings(
    max_examples=25,
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
)

_TE_PREFIXES = ("pitcher", "batter")


def _te_columns(df: pd.DataFrame) -> list[str]:
    return [
        f"{prefix}_te_{cls}"
        for prefix in _TE_PREFIXES
        for cls in LABEL_CLASSES
        if f"{prefix}_te_{cls}" in df.columns
    ]


def _build_fold_leaky(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    """The leak we are trying to catch: target-encode over the FULL data
    (train + test) instead of the train window only, then score the test rows.
    This is the canonical temporal leak — test-window labels bleed into the
    encoding the test rows are scored with."""
    test_mask = (pitches["game_date"] >= fold.test_start) & (pitches["game_date"] <= fold.test_end)
    test_df = cast(pd.DataFrame, pitches.loc[test_mask].copy())
    pitcher_te = compute_te(pitches, entity_col="pitcher_id", label_col="label")
    batter_te = compute_te(pitches, entity_col="batter_id", label_col="label")
    prior = compute_prior(pitches, "label")
    encoded = apply_te(
        test_df, pitcher_te, entity_col="pitcher_id", column_prefix="pitcher", prior=prior
    )
    encoded = apply_te(
        encoded, batter_te, entity_col="batter_id", column_prefix="batter", prior=prior
    )
    return cast(pd.DataFrame, encoded)


def _make_fold(n_days: int, train_days: int) -> SyntheticFold:
    base = date(2024, 4, 1)
    return SyntheticFold(
        train_start=base,
        train_end=base + timedelta(days=train_days - 1),
        test_start=base + timedelta(days=train_days),
        test_end=base + timedelta(days=n_days - 1),
    )


def _mutate_post_train_labels(pitches: pd.DataFrame, fold: SyntheticFold) -> pd.DataFrame:
    """Force every post-train-end label to a single class. A streaming-cutoff
    pipeline must be invariant to this; a leaky one must not."""
    mutated = pitches.copy()
    future = mutated["game_date"] > fold.train_end
    mutated.loc[future, "label"] = LABEL_CLASSES[0]
    return mutated


def _te_matrix(encoded: pd.DataFrame) -> np.ndarray:
    cols = _te_columns(encoded)
    return encoded[cols].to_numpy(dtype="float64")


# Strategy: vary fleet size, day span, and the train/test split point + seed.
_data = st.fixed_dictionaries(
    {
        "n_pitchers": st.integers(min_value=8, max_value=18),
        "n_days": st.integers(min_value=16, max_value=30),
        "train_frac": st.floats(min_value=0.4, max_value=0.7),
        "seed": st.integers(min_value=0, max_value=10_000),
    }
)


def _scenario(params: dict[str, object]) -> tuple[pd.DataFrame, SyntheticFold]:
    n_days = int(cast(int, params["n_days"]))
    train_days = max(4, int(n_days * float(cast(float, params["train_frac"]))))
    train_days = min(train_days, n_days - 4)  # leave >=4 test days
    pitches = synthetic_pitches(
        n_pitchers=int(cast(int, params["n_pitchers"])),
        n_batters=40,
        n_days=n_days,
        pitches_per_pitcher_per_day=6,
        seed=int(cast(int, params["seed"])),
    )
    return pitches, _make_fold(n_days, train_days)


@_SETTINGS
@given(params=_data)
def test_clean_pipeline_is_invariant_to_future_labels(params: dict[str, object]) -> None:
    """no-future-contamination has no false positives: the CLEAN encoding is
    bit-identical before and after mutating post-train-end labels."""
    pitches, fold = _scenario(params)
    before = _te_matrix(build_fold_inmem(pitches, fold))
    after = _te_matrix(build_fold_inmem(_mutate_post_train_labels(pitches, fold), fold))
    np.testing.assert_array_equal(
        before,
        after,
        err_msg="clean train-window TE changed when only FUTURE labels were mutated",
    )


@_SETTINGS
@given(params=_data)
def test_leaky_pipeline_is_caught_by_future_label_mutation(
    params: dict[str, object],
) -> None:
    """no-future-contamination has teeth: the LEAKY encoding (TE over full data)
    DOES change when post-train-end labels are mutated — so the check would fire
    on it. If this ever passed (no change), the leakage test would be vacuous."""
    pitches, fold = _scenario(params)
    before = _te_matrix(_build_fold_leaky(pitches, fold))
    after = _te_matrix(_build_fold_leaky(_mutate_post_train_labels(pitches, fold), fold))
    assert before.shape == after.shape and before.size > 0
    assert not np.array_equal(before, after), (
        "leaky full-data TE was unaffected by future-label mutation — the "
        "no-future-contamination check could not distinguish it from clean"
    )


@_SETTINGS
@given(params=_data)
def test_shuffled_target_destroys_encoding_signal(params: dict[str, object]) -> None:
    """shuffled-target has teeth: per-entity TE concentration is high with real
    labels (each synthetic pitcher prefers a class) and collapses toward uniform
    when labels are shuffled, across arbitrary folds."""
    pitches, _ = _scenario(params)
    real_te = compute_te(pitches, entity_col="pitcher_id", label_col="label")

    shuffled = pitches.copy()
    rng = np.random.default_rng(int(cast(int, params["seed"])) + 1)
    shuffled["label"] = rng.permutation(shuffled["label"].to_numpy())
    shuffled_te = compute_te(shuffled, entity_col="pitcher_id", label_col="label")

    te_cols = [f"te_{cls}" for cls in LABEL_CLASSES]
    # Mean peak per-entity probability — high signal ⇒ near 1, uniform ⇒ ~1/K.
    real_peak = real_te[te_cols].to_numpy().max(axis=1).mean()
    shuffled_peak = shuffled_te[te_cols].to_numpy().max(axis=1).mean()
    assert real_peak > shuffled_peak, (
        f"shuffling labels did not reduce TE concentration "
        f"(real peak {real_peak:.3f} <= shuffled {shuffled_peak:.3f})"
    )
