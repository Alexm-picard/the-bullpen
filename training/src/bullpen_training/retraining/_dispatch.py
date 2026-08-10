"""Maps a queued trigger's ``model_name`` to the training callable that produces a fresh
candidate.

The contract for a dispatched function:

* Signature: ``(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput``.
* Returns a :class:`RetrainOutput` whose fields are exactly what the registry's register
  endpoint needs (artifact paths, eval JSON, training-data hash + window).
* Persists ``trigger_id`` into the produced ``metadata.json`` so post-hoc correlation of
  the model row to its retrain trigger is one ``trigger_id`` lookup.

Wiring status: ``battedball_outcome`` (the SERVED family) dispatches to the real
single-graph ``battedball/mlp`` adapter via
:mod:`bullpen_training.retraining.battedball_outcome` (M2-A3; closes ruling C2). The
per-park EXPERIMENT adapter from M1 task 3 stays reachable only under its explicit
experiment key via :mod:`bullpen_training.retraining.batted_ball`. The remaining registry
entries stay honest sentinels until each Phase-2 trainer is wired to accept ``trigger_id``
and return ``RetrainOutput``. Naming is settled by ruling C1: dispatch keys equal the
registry model names (``champ.modelName()``) exactly.
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


def _experiment_mlp_per_park(
    trigger_id: str, version: str, trigger_metadata: dict
) -> RetrainOutput:
    # Lazy import: keeps torch/onnx out of the import path of every OTHER dispatch, and out
    # of run.py's queue-empty fast path.
    from bullpen_training.retraining.batted_ball import retrain_batted_ball

    return retrain_batted_ball(trigger_id, version, trigger_metadata)


def _servable_battedball_outcome(
    trigger_id: str, version: str, trigger_metadata: dict
) -> RetrainOutput:
    # M2 ruling C2 is CLOSED by this wiring (M2-A3): the served single-graph battedball/mlp
    # family (shared backbone + carry head + per-park calibrators) retrains via the
    # servable-family adapter, in the SERVED artifact format. The per-park EXPERIMENT seam
    # stays at '_experiment_battedball_mlp_per_park' (test/manual-only).
    # Lazy import: keeps torch/onnx/sklearn out of the import path of every OTHER dispatch,
    # and out of run.py's queue-empty fast path.
    from bullpen_training.retraining.battedball_outcome import retrain_battedball_outcome

    return retrain_battedball_outcome(trigger_id, version, trigger_metadata)


# M2 ruling C1: the registry's model_name values are the SINGLE SOURCE OF TRUTH for this
# list - real retraining_queue rows carry champ.modelName(), so any other spelling is
# unreachable by construction. If a name here drifts from the registry, the pinned-list
# test (test_dispatch.py) fails loudly. Verified against model_versions on the box
# 2026-07-02.
CANONICAL_REGISTRY_MODEL_NAMES: frozenset[str] = frozenset(
    {
        "battedball_outcome",
        "pitch_outcome_pre",
        "pitch_outcome_post",
        "pitch_outcome_lr_baseline",
        "battedball_lgbm_per_park",
        "lr_baseline_batted_ball",
    }
)

# Keys with this prefix are deliberately NON-registry (excluded from the pinned-list
# equality by design): reachable only by a hand-built trigger, never by a real queue row.
EXPERIMENT_KEY_PREFIX = "_experiment_"
EXPERIMENT_MLP_PER_PARK_KEY = "_experiment_battedball_mlp_per_park"

# battedball_outcome: wired to the SERVABLE-family adapter (M2-A3, closes ruling C2).
# The per-park adapter (M1 task 3) remains the proven claim->train->register->complete
# seam under its experiment key. The remaining registry entries stay honest sentinels
# until each family's trainer is wired to accept trigger_id + return RetrainOutput.
DISPATCH: dict[str, RetrainFn] = {
    "battedball_outcome": _servable_battedball_outcome,
    "pitch_outcome_pre": _not_yet_wired("pitch_outcome_pre"),
    "pitch_outcome_post": _not_yet_wired("pitch_outcome_post"),
    "pitch_outcome_lr_baseline": _not_yet_wired("pitch_outcome_lr_baseline"),
    "battedball_lgbm_per_park": _not_yet_wired("battedball_lgbm_per_park"),
    "lr_baseline_batted_ball": _not_yet_wired("lr_baseline_batted_ball"),
    EXPERIMENT_MLP_PER_PARK_KEY: _experiment_mlp_per_park,
}


def dispatch_for(model_name: str) -> RetrainFn:
    """Return the registered retrain callable for ``model_name``."""
    if model_name not in DISPATCH:
        raise UnsupportedModel(f"no retrain dispatch entry for {model_name!r}")
    return DISPATCH[model_name]


def register_retrain_fn(model_name: str, fn: RetrainFn) -> None:
    """Tests use this to inject a mock dispatch entry; production code shouldn't need it."""
    DISPATCH[model_name] = fn
