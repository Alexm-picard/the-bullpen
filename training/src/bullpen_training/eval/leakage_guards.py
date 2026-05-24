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
