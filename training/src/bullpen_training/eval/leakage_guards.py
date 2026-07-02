"""Tripwires that fail loudly on the operations we've ruled out.

CLAUDE.md hard-never rule: "Never use `random_state` on data splits.
Splits must be temporal (rolling-origin)."

If you find yourself reaching for `sklearn.model_selection.train_test_split`
on this project's data, import the sentinel here instead — it raises
LeakageError so the disallowed call is impossible to merge.
"""

from __future__ import annotations

from typing import Any, NoReturn


class LeakageError(RuntimeError):
    """Raised when a forbidden split or contamination pattern is attempted."""


def assert_no_random_split(*_args: Any, **_kwargs: Any) -> NoReturn:
    """Drop-in stand-in for `sklearn.model_selection.train_test_split` that
    always raises. Import it where someone might reach for the real one.

    Usage:
        # in a module where random splits are forbidden:
        from bullpen_training.eval.leakage_guards import (
            assert_no_random_split as train_test_split,
        )
    """
    raise LeakageError(
        "Random splits are forbidden on this project's data — "
        "use bullpen_training.eval.cv_harness.run instead (decision [56])."
    )


def refuse_holdout(
    *,
    season_from: int | None = None,
    season_to: int | None = None,
    val_season: int | None = None,
) -> None:
    """Rule-13 fence: refuse any training/validation season touching the holdout year.

    CLAUDE.md rule 13: 2026 season data is holdout-only - never for training or
    validation. Every trainer entry point (CLI main and programmatic) calls this
    before any data loads, so a stray `--train-season-to 2026` fails loudly
    instead of silently contaminating a production training set.
    """
    # Local import: keeps this module a dependency-free leaf while sample_loader
    # (pandas-heavy) stays the single source of truth for the holdout year.
    from bullpen_training.eval.promotion.sample_loader import HOLDOUT_YEAR

    offending = {
        name: year
        for name, year in (
            ("season_from", season_from),
            ("season_to", season_to),
            ("val_season", val_season),
        )
        if year is not None and year >= HOLDOUT_YEAR
    }
    if offending:
        detail = ", ".join(f"{name}={year}" for name, year in offending.items())
        raise LeakageError(
            f"rule 13: {HOLDOUT_YEAR} is holdout-only; trainers refuse "
            f"training/validation seasons >= {HOLDOUT_YEAR} (got {detail}). "
            f"The {HOLDOUT_YEAR} pull exists exclusively for post-training, "
            "post-validation accuracy testing."
        )
