"""Maps a queued trigger's ``model_name`` to the training callable that produces a fresh
candidate.

The contract for a dispatched function:

* Signature: ``(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput``.
* Returns a :class:`RetrainOutput` whose fields are exactly what the registry's register
  endpoint needs (artifact paths, eval JSON, training-data hash + window).
* Persists ``trigger_id`` into the produced ``metadata.json`` so post-hoc correlation of
  the model row to its retrain trigger is one ``trigger_id`` lookup.

Wiring status (M1 task 3): ``batted_ball`` dispatches to the REAL per-park trainer via
:mod:`bullpen_training.retraining.batted_ball` (claim -> train_all_parks -> ONNX export ->
RetrainOutput, integration-tested on a synthetic miniature). The other four entries stay
honest sentinels until each Phase-2 trainer is wired to accept ``trigger_id`` and return
``RetrainOutput`` - and note the open naming question recorded in ``batted_ball.py``: real
triggers enqueue the registry model name (``champ.modelName()``), which these dispatch keys
do not all match yet.
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


def _batted_ball(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput:
    # Lazy import: keeps torch/onnx out of the import path of every OTHER dispatch, and out
    # of run.py's queue-empty fast path.
    from bullpen_training.retraining.batted_ball import retrain_batted_ball

    return retrain_batted_ball(trigger_id, version, trigger_metadata)


# The batted_ball entry is REAL (M1 task 3): it drives the mlp_per_park trainer end-to-end
# (see retraining/batted_ball.py, including the naming caveat that real triggers enqueue the
# registry model name). The remaining four stay honest sentinels until each Phase-2 trainer
# is wired to accept trigger_id + return RetrainOutput.
DISPATCH: dict[str, RetrainFn] = {
    "pitch_outcome_pre": _not_yet_wired("pitch_outcome_pre"),
    "pitch_outcome_post": _not_yet_wired("pitch_outcome_post"),
    "pitch_outcome_lr_baseline": _not_yet_wired("pitch_outcome_lr_baseline"),
    "batted_ball": _batted_ball,
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
