"""Tests for ``bullpen_training.retraining._dispatch``."""

from __future__ import annotations

import pytest

from bullpen_training.retraining._dispatch import (
    CANONICAL_REGISTRY_MODEL_NAMES,
    DISPATCH,
    EXPERIMENT_KEY_PREFIX,
    EXPERIMENT_MLP_PER_PARK_KEY,
    RetrainOutput,
    UnsupportedModel,
    dispatch_for,
    register_retrain_fn,
)


def test_dispatch_keys_equal_registry_model_names_exactly() -> None:
    """M2 ruling C1: registry model_name values are the single source of truth. A renamed or
    added key that does not match the registry (experiment keys excluded by design) must fail
    here loudly, because a mismatched key is unreachable by real queue rows."""
    registry_keys = {k for k in DISPATCH if not k.startswith(EXPERIMENT_KEY_PREFIX)}
    assert registry_keys == CANONICAL_REGISTRY_MODEL_NAMES


def test_experiment_keys_are_prefixed_and_not_registry_names() -> None:
    experiment_keys = {k for k in DISPATCH if k.startswith(EXPERIMENT_KEY_PREFIX)}
    assert EXPERIMENT_MLP_PER_PARK_KEY in experiment_keys
    assert experiment_keys.isdisjoint(CANONICAL_REGISTRY_MODEL_NAMES)


def test_battedball_outcome_is_an_honest_sentinel_pointing_at_the_servable_adapter() -> None:
    """M2 ruling C2: the served family must not dispatch to the per-park experiment adapter."""
    fn = dispatch_for("battedball_outcome")
    with pytest.raises(UnsupportedModel) as exc:
        fn("trig-xyz", "v3", {})
    message = str(exc.value)
    assert "DELIBERATELY unwired" in message
    assert "M2-A3" in message
    assert EXPERIMENT_MLP_PER_PARK_KEY in message


def test_default_dispatch_fns_raise_unsupported_until_wired() -> None:
    fn = dispatch_for("pitch_outcome_pre")
    with pytest.raises(UnsupportedModel) as exc:
        fn("trig-abc", "v17", {})
    assert "pitch_outcome_pre" in str(exc.value)
    assert "trig-abc" in str(exc.value)


def test_dispatch_for_unknown_model_raises_unsupported() -> None:
    with pytest.raises(UnsupportedModel):
        dispatch_for("never_heard_of_this_one")


def test_register_retrain_fn_lets_tests_inject(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "bullpen_training.retraining._dispatch.DISPATCH",
        dict(DISPATCH),  # local copy so we don't pollute later tests
        raising=False,
    )

    def fake(trigger_id: str, version: str, metadata: dict) -> RetrainOutput:
        return RetrainOutput(
            artifact_path="/tmp/x.onnx",
            metadata_path="/tmp/m.json",
            feature_pipeline_path="/tmp/p.json",
            eval_metrics_json='{"brier":0.18}',
            training_data_hash="h",
            training_data_window="[2024,2024]",
            trained_at_iso="2026-05-25T00:00:00Z",
        )

    register_retrain_fn("custom_model", fake)
    out = dispatch_for("custom_model")("trig-1", "v1", {})
    assert out.artifact_path == "/tmp/x.onnx"
