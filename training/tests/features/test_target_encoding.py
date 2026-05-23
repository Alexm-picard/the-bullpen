"""Unit tests for the Bayesian-smoothed target encoder (Phase 2a.1).

Hand-computed expected values keep the test pure-arithmetic; no
ClickHouse needed. The leakage-test suite in Phase 2a.3 covers the
temporal-cutoff side of the encoding.
"""

from __future__ import annotations

import json
from pathlib import Path

import pandas as pd
import pytest

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.features.target_encoding import (
    DEFAULT_SMOOTHING_K,
    apply_te,
    compute_prior,
    compute_te,
    load_encoding,
    save_encoding,
)


def _toy_pitches() -> pd.DataFrame:
    # 3 pitchers w/ varying class distributions.
    return pd.DataFrame(
        {
            "pitcher_id": [
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,
                1,  # 10 pitches
                2,
                2,
                2,
                2,
                2,  # 5 pitches
                3,  # 1 pitch (cold-start)
            ],
            "label": [
                # pitcher 1: 4 ball, 2 called_strike, 1 swinging_strike, 1 foul, 2 in_play
                "ball",
                "ball",
                "ball",
                "ball",
                "called_strike",
                "called_strike",
                "swinging_strike",
                "foul",
                "in_play",
                "in_play",
                # pitcher 2: 5 in_play
                "in_play",
                "in_play",
                "in_play",
                "in_play",
                "in_play",
                # pitcher 3: 1 ball
                "ball",
            ],
        }
    )


def test_prior_sums_to_one() -> None:
    df = _toy_pitches()
    prior = compute_prior(df, "label")
    assert pytest.approx(sum(prior.values()), abs=1e-9) == 1.0
    assert set(prior.keys()) == set(LABEL_CLASSES)


def test_compute_te_columns_match_label_classes() -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label")
    expected = {"pitcher_id", *(f"te_{c}" for c in LABEL_CLASSES)}
    assert set(te.columns) == expected


def test_compute_te_smoothing_formula_for_known_pitcher() -> None:
    """Spot-check pitcher 2 (5/5 in_play): te_in_play = (5 + k*p_in_play) / (5 + k)."""
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label", smoothing_k=10.0)
    prior = compute_prior(df, "label")
    pitcher2 = te.loc[te["pitcher_id"] == 2].iloc[0]
    expected = (5 + 10.0 * prior["in_play"]) / (5 + 10.0)
    assert pytest.approx(pitcher2["te_in_play"], rel=1e-5) == expected
    # And the ball column should be (0 + 10 * prior_ball) / 15
    expected_ball = (0 + 10.0 * prior["ball"]) / 15
    assert pytest.approx(pitcher2["te_ball"], rel=1e-5) == expected_ball


def test_compute_te_cold_start_pitcher_pulls_toward_prior() -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label", smoothing_k=20.0)
    prior = compute_prior(df, "label")
    cold = te.loc[te["pitcher_id"] == 3].iloc[0]
    expected_ball = (1 + 20.0 * prior["ball"]) / (1 + 20.0)
    assert pytest.approx(cold["te_ball"], rel=1e-5) == expected_ball


def test_compute_te_rejects_unknown_labels() -> None:
    df = pd.DataFrame({"pitcher_id": [1], "label": ["hit_by_pitch"]})
    with pytest.raises(ValueError, match="unexpected labels"):
        compute_te(df, entity_col="pitcher_id", label_col="label")


def test_compute_te_rejects_missing_columns() -> None:
    df = pd.DataFrame({"pitcher_id": [1]})
    with pytest.raises(ValueError, match="missing columns"):
        compute_te(df, entity_col="pitcher_id", label_col="label")


def test_apply_te_unseen_entity_gets_prior() -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label")
    prior = compute_prior(df, "label")
    targets = pd.DataFrame({"pitcher_id": [1, 99]})  # 99 is unseen
    out = apply_te(targets, te, entity_col="pitcher_id", column_prefix="pitcher")
    unseen = out.loc[out["pitcher_id"] == 99].iloc[0]
    for cls in LABEL_CLASSES:
        assert pytest.approx(unseen[f"pitcher_te_{cls}"], rel=1e-5) == prior[cls]


def test_apply_te_known_entity_uses_encoding_value() -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label", smoothing_k=10.0)
    targets = pd.DataFrame({"pitcher_id": [2]})
    out = apply_te(targets, te, entity_col="pitcher_id", column_prefix="pitcher")
    row = out.iloc[0]
    expected_te = te.loc[te["pitcher_id"] == 2].iloc[0]
    for cls in LABEL_CLASSES:
        assert pytest.approx(row[f"pitcher_te_{cls}"], rel=1e-5) == expected_te[f"te_{cls}"]


def test_apply_te_requires_prior_when_attrs_empty() -> None:
    encoding = pd.DataFrame(
        {
            "pitcher_id": [1],
            "te_ball": [0.3],
            "te_called_strike": [0.2],
            "te_swinging_strike": [0.1],
            "te_foul": [0.2],
            "te_in_play": [0.2],
        }
    )
    # No .attrs, no explicit prior — should fail
    targets = pd.DataFrame({"pitcher_id": [99]})
    with pytest.raises(ValueError, match="prior not provided"):
        apply_te(targets, encoding, entity_col="pitcher_id", column_prefix="pitcher")


def test_save_and_load_roundtrip(tmp_path: Path) -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label", smoothing_k=15.0)
    path = tmp_path / "pitcher_fold1.json"
    save_encoding(te, path, entity_col="pitcher_id")

    loaded_df, loaded_prior = load_encoding(path)
    assert loaded_prior == compute_prior(df, "label")
    assert loaded_df.attrs["smoothing_k"] == pytest.approx(15.0)
    # Ensure round-trip preserves all 5 TE columns
    for cls in LABEL_CLASSES:
        assert f"te_{cls}" in loaded_df.columns


def test_save_encoding_emits_deterministic_json(tmp_path: Path) -> None:
    df = _toy_pitches()
    te = compute_te(df, entity_col="pitcher_id", label_col="label")
    path_a = tmp_path / "a.json"
    path_b = tmp_path / "b.json"
    save_encoding(te, path_a, entity_col="pitcher_id")
    save_encoding(te, path_b, entity_col="pitcher_id")
    assert path_a.read_bytes() == path_b.read_bytes()
    # And the rows are sorted by entity_id (defensive — readers rely on it
    # for visual diffs across folds)
    payload = json.loads(path_a.read_text())
    ids = [row["pitcher_id"] for row in payload["rows"]]
    assert ids == sorted(ids)


def test_default_smoothing_constant_locked() -> None:
    """If someone bumps DEFAULT_SMOOTHING_K, every saved encoding becomes
    incomparable to the previous one. Force a deliberate decision."""
    assert DEFAULT_SMOOTHING_K == 20.0
