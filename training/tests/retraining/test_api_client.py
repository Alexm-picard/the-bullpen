"""Tests for ``bullpen_training.retraining._api_client``.

Uses an in-process ``http.server`` for end-to-end coverage without mocking urllib internals.
The fake server records every request and routes by path; tests assert on both the request
body (auth header + JSON) and the typed response.
"""

from __future__ import annotations

import base64
import http.server
import json
import threading
from collections.abc import Generator
from typing import Any, ClassVar

import pytest

from bullpen_training.retraining._api_client import (
    BullpenAdminClient,
    BullpenAdminError,
    ClaimedTrigger,
)

# --- in-process fake admin server ----------------------------------------


class _FakeAdminHandler(http.server.BaseHTTPRequestHandler):
    routes: ClassVar[dict[tuple[str, str], dict[str, Any]]] = {}
    recorded: ClassVar[list[dict[str, Any]]] = []

    def log_message(self, format: str, *args: Any) -> None:
        return  # silence the default access-log

    def _record(self, method: str) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length else b""
        self.recorded.append(
            {
                "method": method,
                "path": self.path,
                "auth": self.headers.get("Authorization"),
                "body": body,
            }
        )
        return body

    def _respond(self) -> None:
        spec = self.routes.get((self.command, self.path))
        if spec is None:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'{"error": "no route"}')
            return
        status = spec.get("status", 200)
        body = spec.get("body", b"")
        if isinstance(body, dict | list):
            body = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_POST(self) -> None:
        self._record("POST")
        self._respond()

    def do_GET(self) -> None:
        self._record("GET")
        self._respond()


@pytest.fixture
def fake_admin() -> Generator[tuple[str, type[_FakeAdminHandler]], None, None]:
    """Spin up the fake admin server on an ephemeral port. Yields (base_url, handler-class)."""
    # Reset class state per test.
    _FakeAdminHandler.routes = {}
    _FakeAdminHandler.recorded = []
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _FakeAdminHandler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{port}", _FakeAdminHandler
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2.0)


# --- claim_next_queued ----------------------------------------------------


def test_claim_next_queued_returns_typed_row(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/retrain/claim")] = {
        "status": 200,
        "body": {
            "id": 7,
            "triggerId": "trig-abc",
            "modelName": "model_a",
            "triggerType": "MANUAL",
            "triggerMetadata": '{"reason":"hello"}',
            "status": "RUNNING",
        },
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    claimed = client.claim_next_queued()
    assert isinstance(claimed, ClaimedTrigger)
    assert claimed.trigger_id == "trig-abc"
    assert claimed.model_name == "model_a"
    assert claimed.trigger_type == "MANUAL"
    assert claimed.trigger_metadata == {"reason": "hello"}
    # Auth header propagated.
    expected_auth = "Basic " + base64.b64encode(b"u:p").decode("ascii")
    assert handler.recorded[0]["auth"] == expected_auth


def test_claim_next_queued_returns_none_on_204(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/retrain/claim")] = {"status": 204, "body": b""}
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    assert client.claim_next_queued() is None


def test_claim_raises_bullpen_error_on_500(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/retrain/claim")] = {
        "status": 500,
        "body": b"boom",
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    with pytest.raises(BullpenAdminError):
        client.claim_next_queued()


# --- mark_complete --------------------------------------------------------


def test_mark_complete_success_sends_correct_body(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/retrain/trig-1/complete")] = {
        "status": 200,
        "body": {
            "id": 1,
            "triggerId": "trig-1",
            "modelName": "model_a",
            "triggerType": "DRIFT",
            "triggerMetadata": "{}",
            "status": "SUCCEEDED",
        },
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    client.mark_complete("trig-1", succeeded=True, produced_version_id=42, error_message=None)
    body = json.loads(handler.recorded[0]["body"])
    assert body == {"succeeded": True, "producedVersionId": 42, "errorMessage": None}


def test_mark_complete_failure_sends_error_message(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/retrain/trig-x/complete")] = {
        "status": 200,
        "body": {
            "id": 9,
            "triggerId": "trig-x",
            "modelName": "model_a",
            "triggerType": "MANUAL",
            "triggerMetadata": "{}",
            "status": "FAILED",
        },
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    client.mark_complete("trig-x", succeeded=False, produced_version_id=None, error_message="OOM")
    body = json.loads(handler.recorded[0]["body"])
    assert body == {"succeeded": False, "producedVersionId": None, "errorMessage": "OOM"}


# --- register -------------------------------------------------------------


def test_register_posts_full_payload_and_returns_version_id(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("POST", "/v1/admin/registry/model_a/register")] = {
        "status": 200,
        "body": {
            "id": 555,
            "modelName": "model_a",
            "version": "v2",
            "stage": "CANDIDATE",
        },
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    version_id = client.register(
        model_name="model_a",
        version="v2",
        artifact_path="/tmp/m.onnx",
        metadata_path="/tmp/m.json",
        feature_pipeline_path="/tmp/p.json",
        training_data_hash="abc123",
        training_data_window="[2024,2024]",
        eval_metrics_json='{"brier":0.18}',
        trained_at="2026-05-25T00:00:00Z",
        created_by="retrain-worker",
        notes="from trigger trig-1",
    )
    assert version_id == 555
    body = json.loads(handler.recorded[0]["body"])
    assert body["modelName"] == "model_a"
    assert body["version"] == "v2"
    assert body["createdBy"] == "retrain-worker"
    assert body["trainedAt"] == "2026-05-25T00:00:00Z"


# --- next_version_label --------------------------------------------------


def test_next_version_label_increments_largest_v_prefixed(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("GET", "/v1/admin/registry/model_a")] = {
        "status": 200,
        "body": [
            {"version": "v1"},
            {"version": "v16"},
            {"version": "v8"},
            {"version": "not_versioned"},
        ],
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    assert client.next_version_label("model_a") == "v17"


def test_next_version_label_starts_at_v1_when_empty(fake_admin):
    base_url, handler = fake_admin
    handler.routes[("GET", "/v1/admin/registry/model_a")] = {
        "status": 200,
        "body": [],
    }
    client = BullpenAdminClient(base_url=base_url, user="u", password="p")
    assert client.next_version_label("model_a") == "v1"
