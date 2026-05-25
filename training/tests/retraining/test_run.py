"""Tests for ``bullpen_training.retraining.run``.

Uses a fake ``BullpenAdminClient`` (duck-typed) so we don't pay HTTP setup. Asserts on the
exact sequence of admin calls — claim → dispatch → register → mark_complete — plus error
paths.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from bullpen_training.retraining import _dispatch
from bullpen_training.retraining._api_client import BullpenAdminError, ClaimedTrigger
from bullpen_training.retraining._dispatch import RetrainOutput
from bullpen_training.retraining.run import run_once

# --- fake admin client ----------------------------------------------------


@dataclass
class FakeAdminClient:
    next_claim: ClaimedTrigger | None = None
    next_version_value: str = "v1"
    register_returns: int = 100
    claim_raises: bool = False
    register_raises: bool = False
    mark_complete_raises_on_success: bool = False

    claim_calls: int = 0
    register_calls: list[dict] = field(default_factory=list)
    complete_calls: list[dict] = field(default_factory=list)

    # --- match BullpenAdminClient surface ---------------------------------

    def claim_next_queued(self) -> ClaimedTrigger | None:
        self.claim_calls += 1
        if self.claim_raises:
            raise BullpenAdminError("simulated claim failure")
        return self.next_claim

    def next_version_label(self, model_name: str, prefix: str = "v") -> str:
        return self.next_version_value

    def register(self, **kwargs: Any) -> int:
        self.register_calls.append(kwargs)
        if self.register_raises:
            raise BullpenAdminError("simulated register failure")
        return self.register_returns

    def mark_complete(
        self,
        trigger_id: str,
        *,
        succeeded: bool,
        produced_version_id: int | None,
        error_message: str | None,
    ) -> None:
        self.complete_calls.append(
            {
                "trigger_id": trigger_id,
                "succeeded": succeeded,
                "produced_version_id": produced_version_id,
                "error_message": error_message,
            }
        )
        if self.mark_complete_raises_on_success and succeeded:
            raise BullpenAdminError("simulated mark_complete failure")


# --- helpers --------------------------------------------------------------


def _claim(trigger_id: str = "trig-1", model_name: str = "model_a") -> ClaimedTrigger:
    return ClaimedTrigger(
        id=1,
        trigger_id=trigger_id,
        model_name=model_name,
        trigger_type="MANUAL",
        trigger_metadata={"reason": "test"},
        status="RUNNING",
    )


def _output() -> RetrainOutput:
    return RetrainOutput(
        artifact_path="/tmp/m.onnx",
        metadata_path="/tmp/m.json",
        feature_pipeline_path="/tmp/p.json",
        eval_metrics_json='{"brier":0.18}',
        training_data_hash="hashy",
        training_data_window="[2024,2024]",
        trained_at_iso="2026-05-25T00:00:00Z",
    )


# --- happy path -----------------------------------------------------------


def test_run_once_happy_path_returns_zero_and_marks_succeeded(monkeypatch):
    client = FakeAdminClient(next_claim=_claim(), next_version_value="v17")
    monkeypatch.setattr(
        _dispatch, "DISPATCH", {**_dispatch.DISPATCH, "model_a": lambda *a: _output()}
    )

    exit_code = run_once(client)

    assert exit_code == 0
    assert client.claim_calls == 1
    assert len(client.register_calls) == 1
    reg = client.register_calls[0]
    assert reg["model_name"] == "model_a"
    assert reg["version"] == "v17"
    assert reg["artifact_path"] == "/tmp/m.onnx"
    assert reg["created_by"] == "retrain-worker"
    assert "trig-1" in reg["notes"]
    assert client.complete_calls == [
        {
            "trigger_id": "trig-1",
            "succeeded": True,
            "produced_version_id": 100,
            "error_message": None,
        }
    ]


# --- empty queue ----------------------------------------------------------


def test_run_once_empty_queue_returns_zero_without_calling_register():
    client = FakeAdminClient(next_claim=None)

    exit_code = run_once(client)

    assert exit_code == 0
    assert client.claim_calls == 1
    assert client.register_calls == []
    assert client.complete_calls == []


# --- training-pipeline failure --------------------------------------------


def test_run_once_training_exception_marks_failed_and_returns_one(monkeypatch):
    client = FakeAdminClient(next_claim=_claim())

    def boom(trigger_id: str, version: str, metadata: dict) -> RetrainOutput:
        raise RuntimeError("CUDA OOM at epoch 47")

    monkeypatch.setattr(_dispatch, "DISPATCH", {**_dispatch.DISPATCH, "model_a": boom})

    exit_code = run_once(client)

    assert exit_code == 1
    assert client.register_calls == []
    assert len(client.complete_calls) == 1
    fc = client.complete_calls[0]
    assert fc["succeeded"] is False
    assert fc["produced_version_id"] is None
    assert "RuntimeError" in fc["error_message"]
    assert "OOM" in fc["error_message"]


def test_run_once_unsupported_model_marks_failed(monkeypatch):
    client = FakeAdminClient(next_claim=_claim(model_name="never_wired"))
    # Don't add to dispatch — UnsupportedModel raises from dispatch_for.

    exit_code = run_once(client)

    assert exit_code == 1
    assert any("never_wired" in (c["error_message"] or "") for c in client.complete_calls)


# --- register failure -----------------------------------------------------


def test_run_once_register_failure_marks_failed_and_returns_one(monkeypatch):
    client = FakeAdminClient(next_claim=_claim(), register_raises=True)
    monkeypatch.setattr(
        _dispatch,
        "DISPATCH",
        {**_dispatch.DISPATCH, "model_a": lambda *a: _output()},
    )

    exit_code = run_once(client)

    assert exit_code == 1
    assert len(client.register_calls) == 1
    assert len(client.complete_calls) == 1
    assert client.complete_calls[0]["succeeded"] is False
    assert "register call failed" in client.complete_calls[0]["error_message"]


# --- claim failure --------------------------------------------------------


def test_run_once_claim_failure_returns_two_without_touching_anything():
    client = FakeAdminClient(claim_raises=True)

    exit_code = run_once(client)

    assert exit_code == 2
    assert client.register_calls == []
    assert client.complete_calls == []


# --- mark_complete-after-register failure ---------------------------------


def test_run_once_mark_complete_failure_after_register_returns_two(monkeypatch):
    client = FakeAdminClient(
        next_claim=_claim(),
        mark_complete_raises_on_success=True,
    )
    monkeypatch.setattr(
        _dispatch,
        "DISPATCH",
        {**_dispatch.DISPATCH, "model_a": lambda *a: _output()},
    )

    exit_code = run_once(client)

    # Register succeeded but mark_complete failed → exit 2 (operator needs to clean up).
    assert exit_code == 2
    assert len(client.register_calls) == 1
    assert len(client.complete_calls) == 1
