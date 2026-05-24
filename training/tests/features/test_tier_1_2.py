"""Unit tests for the Tier 1 + 2 feature builder (Phase 2a.1).

ClickHouse-side correctness (the SQL projection + the per-fold insert
sweep) is covered by the live drill at the end of the leaf. Here we
lock the pure Python pieces: FoldWindow validation, _bind correctness,
fold-window generator.
"""

from __future__ import annotations

from datetime import date

import pytest

from bullpen_training.features.tier_1_2 import (
    FoldWindow,
    _bind,
    _default_folds_for,
)


def test_foldwindow_rejects_overlap() -> None:
    with pytest.raises(ValueError, match="leakage by construction"):
        FoldWindow(
            fold_id=1,
            train_start=date(2015, 1, 1),
            train_end=date(2020, 12, 31),
            test_start=date(2020, 6, 1),  # before train_end
            test_end=date(2021, 12, 31),
        )


def test_foldwindow_allows_adjacent_dates() -> None:
    # train_end Dec 31; test_start Jan 1 of next year — strict less-than holds
    fold = FoldWindow(
        fold_id=1,
        train_start=date(2015, 1, 1),
        train_end=date(2022, 12, 31),
        test_start=date(2023, 1, 1),
        test_end=date(2023, 12, 31),
    )
    assert fold.fold_id == 1


def test_bind_substitutes_dates_with_quotes() -> None:
    sql = "WHERE game_date BETWEEN :start_date AND :end_date"
    out = _bind(sql, {"start_date": date(2024, 4, 1), "end_date": date(2024, 4, 30)})
    assert out == "WHERE game_date BETWEEN '2024-04-01' AND '2024-04-30'"


def test_bind_respects_word_boundary() -> None:
    """`:end_date` must not match `:end_dateline` if such a name ever exists."""
    sql = "SELECT :end_date, :end_dateline"
    out = _bind(sql, {"end_date": date(2024, 1, 1), "end_dateline": "noon"})
    assert out == "SELECT '2024-01-01', 'noon'"


def test_default_folds_for_full_corpus_with_val_held_out() -> None:
    """Per decision [56] + Phase 2a.5 prereq: train_end = test_year - 2 so
    the val year (test_year - 1) is reserved as the calibration / early-
    stopping holdout. The encoded `features` table covers val + test rows
    per fold; the harness in 2a.4 partitions them by calendar year."""
    folds = _default_folds_for(2015, 2025)
    assert len(folds) == 4
    assert [f.fold_id for f in folds] == [1, 2, 3, 4]
    # Fold 1 tests 2022, vals 2021, train ends 2020
    assert folds[0].train_end == date(2020, 12, 31)
    assert folds[0].test_start == date(2021, 1, 1)  # encoded window covers val year
    assert folds[0].test_end == date(2022, 12, 31)
    # Fold 4 tests 2025, vals 2024, train ends 2023
    assert folds[-1].train_end == date(2023, 12, 31)
    assert folds[-1].test_start == date(2024, 1, 1)
    assert folds[-1].test_end == date(2025, 12, 31)
    for fold in folds:
        assert fold.train_end < fold.test_start


def test_default_folds_rejects_too_short_corpus() -> None:
    with pytest.raises(ValueError, match="at least 6 seasons"):
        _default_folds_for(2022, 2025)


def test_default_folds_each_fold_covers_complete_calendar_years() -> None:
    folds = _default_folds_for(2015, 2025)
    for fold in folds:
        assert fold.test_start.month == 1 and fold.test_start.day == 1
        assert fold.test_end.month == 12 and fold.test_end.day == 31
