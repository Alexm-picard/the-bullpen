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


def test_battedball_outcome_dispatches_to_the_servable_family_adapter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """M2 ruling C2 is CLOSED by the M2-A3 wiring: the served family dispatches to the real
    single-graph battedball/mlp adapter (lazy-imported at call time), never to the per-park
    experiment seam. The lazy import re-reads the module attribute per call, so patching the
    adapter function proves the wiring without running a training loop."""
    calls: list[tuple[str, str, dict]] = []
    sentinel = RetrainOutput(
        artifact_path="/tmp/battedball_outcome/v3/model.onnx",
        metadata_path="/tmp/battedball_outcome/v3/metadata.json",
        feature_pipeline_path="/tmp/p.json",
        eval_metrics_json='{"kind":"training_diagnostics"}',
        training_data_hash="h" * 64,
        training_data_window="[2015,2025]",
        trained_at_iso="2026-07-02T00:00:00Z",
    )

    def fake(trigger_id: str, version: str, trigger_metadata: dict) -> RetrainOutput:
        calls.append((trigger_id, version, trigger_metadata))
        return sentinel

    monkeypatch.setattr(
        "bullpen_training.retraining.battedball_outcome.retrain_battedball_outcome", fake
    )

    out = dispatch_for("battedball_outcome")("trig-xyz", "v3", {"n_epochs": 1})

    assert calls == [("trig-xyz", "v3", {"n_epochs": 1})]
    assert out is sentinel


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
