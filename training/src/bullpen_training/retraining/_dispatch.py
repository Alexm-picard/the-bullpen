"""Maps a queued trigger's ``model_name`` to the training callable that produces a fresh
candidate.

The contract for a dispatched function:

* Signature: ``(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput``.
* Returns a :class:`RetrainOutput` whose fields are exactly what the registry's register
  endpoint needs (artifact paths, eval JSON, training-data hash + window).
* Persists ``trigger_id`` into the produced ``metadata.json`` so post-hoc correlation of
  the model row to its retrain trigger is one ``trigger_id`` lookup.

The actual wiring of each model_name to its existing Phase-2 trainer (``train_pre.py``,
``train_post.py``, ``train_toy.py``, etc.) is intentionally left as a follow-up — the Phase-2
trainers don't currently accept ``trigger_id``, and weaving it through each one is invasive.
For 3d.3 the dispatch table holds a sentinel raises so the run.py orchestration can be
exercised end-to-end with tests that mock the callable. The wiring lands in a follow-up
commit per model.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class RetrainOutput:
    """Exactly the fields ``BullpenAdminClient.register`` needs."""

    artifact_path: str
    metadata_path: str
    feature_pipeline_path: str
    eval_metrics_json: str
    training_data_hash: str
    training_data_window: str
    trained_at_iso: str


RetrainFn = Callable[[str, str, dict], RetrainOutput]
"""(trigger_id, version, trigger_metadata) -> RetrainOutput."""


class UnsupportedModel(RuntimeError):
    """Raised when no dispatch entry exists for the requested model_name."""


def _not_yet_wired(model_name: str) -> RetrainFn:
    def _stub(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput:
        raise UnsupportedModel(
            f"retrain dispatch for {model_name!r} is not yet wired — wire the Phase-2 trainer "
            f"to accept trigger_id={trigger_id!r} and return RetrainOutput, then add to DISPATCH"
        )

    return _stub


# Sentinel stubs per the leaf body's dispatch table. Each one raises until the matching
# Phase-2 trainer is updated to accept trigger_id + return RetrainOutput.
DISPATCH: dict[str, RetrainFn] = {
    "pitch_outcome_pre": _not_yet_wired("pitch_outcome_pre"),
    "pitch_outcome_post": _not_yet_wired("pitch_outcome_post"),
    "pitch_outcome_lr_baseline": _not_yet_wired("pitch_outcome_lr_baseline"),
    "batted_ball": _not_yet_wired("batted_ball"),
    "batted_ball_lgbm_baseline": _not_yet_wired("batted_ball_lgbm_baseline"),
}


def dispatch_for(model_name: str) -> RetrainFn:
    """Return the registered retrain callable for ``model_name``."""
    if model_name not in DISPATCH:
        raise UnsupportedModel(f"no retrain dispatch entry for {model_name!r}")
    return DISPATCH[model_name]


def register_retrain_fn(model_name: str, fn: RetrainFn) -> None:
    """Tests use this to inject a mock dispatch entry; production code shouldn't need it."""
    DISPATCH[model_name] = fn
