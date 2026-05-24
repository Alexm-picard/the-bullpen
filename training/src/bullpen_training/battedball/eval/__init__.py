"""MLP vs LightGBM head-to-head eval artifact (Phase 2c.9).

Decision-support for the 2c bake-off: did the MLP's multi-output
complexity pay off vs. a single-output LightGBM with park_id as a
categorical feature? Produces a per-park + aggregate side-by-side
report + a ``prefer_for_production`` flag (Brier first, ECE tiebreak,
simpler model on ties).

Public surface:

- :func:`per_park_metrics` — pred vs label → per-park Brier + ECE +
  argmax-confusion.
- :func:`compare_models` — (MLP probs, LGBM probs, labels) → fully
  populated :class:`ComparisonReport`.
- :func:`save_report` / :func:`render_html` — JSON + HTML artefacts.
"""

from __future__ import annotations

from bullpen_training.battedball.eval.comparison import (
    AggregateMetrics,
    ComparisonReport,
    ParkMetrics,
    compare_models,
    decide_winner,
    per_park_metrics,
)
from bullpen_training.battedball.eval.report import (
    render_html,
    save_report,
)

__all__ = (
    "AggregateMetrics",
    "ComparisonReport",
    "ParkMetrics",
    "compare_models",
    "decide_winner",
    "per_park_metrics",
    "render_html",
    "save_report",
)
