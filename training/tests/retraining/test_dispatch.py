"""Tests for ``bullpen_training.retraining._dispatch``."""

from __future__ import annotations

import pytest

from bullpen_training.retraining._dispatch import (
    DISPATCH,
    RetrainOutput,
    UnsupportedModel,
    dispatch_for,
    register_retrain_fn,
)


def test_default_dispatch_table_contains_all_models_from_leaf() -> None:
    expected = {
        "pitch_outcome_pre",
        "pitch_outcome_post",
        "pitch_outcome_lr_baseline",
        "batted_ball",
        "batted_ball_lgbm_baseline",
    }
    assert expected <= set(DISPATCH.keys())


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
