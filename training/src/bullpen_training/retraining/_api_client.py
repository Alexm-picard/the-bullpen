"""Thin REST client for the Spring admin endpoints the Python retrain worker calls.

Uses stdlib ``urllib.request`` to keep the dep surface flat — no httpx / requests
addition just for two POSTs and one GET. The client owns the HTTP-Basic header + JSON
serialization; callers see typed methods.

The four endpoints used:

* ``POST /v1/admin/retrain/claim`` — atomic claim of the next queued trigger. Returns
  200 with row on win, 204 when the queue is empty.
* ``POST /v1/admin/retrain/{trigger_id}/complete`` — report success/failure.
* ``POST /v1/admin/registry/{model_name}/register`` — register the newly-trained
  candidate version (3a.4).
* ``GET  /v1/admin/registry/{model_name}`` — list versions, used to pick the next
  monotonic version string ("v17" if v16 exists).
"""

from __future__ import annotations

import base64
import json
import logging
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

log = logging.getLogger(__name__)


class BullpenAdminError(RuntimeError):
    """Raised when an admin call returns an unexpected status."""


@dataclass(frozen=True)
class ClaimedTrigger:
    """One row claimed from the retraining queue. Mirrors the Java ``RetrainingTrigger``."""

    id: int
    trigger_id: str
    model_name: str
    trigger_type: str
    trigger_metadata: dict[str, Any]
    status: str

    @classmethod
    def from_json(cls, payload: dict[str, Any]) -> ClaimedTrigger:
        raw_meta = payload.get("triggerMetadata") or "{}"
        try:
            meta = json.loads(raw_meta) if isinstance(raw_meta, str) else dict(raw_meta)
        except json.JSONDecodeError:
            meta = {}
        return cls(
            id=int(payload["id"]),
            trigger_id=payload["triggerId"],
            model_name=payload["modelName"],
            trigger_type=payload["triggerType"],
            trigger_metadata=meta,
            status=payload["status"],
        )


class BullpenAdminClient:
    """Stdlib-urllib client for Spring's admin endpoints."""

    def __init__(self, base_url: str, user: str, password: str, timeout_s: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout_s = timeout_s
        credentials = f"{user}:{password}".encode()
        self._auth_header = "Basic " + base64.b64encode(credentials).decode("ascii")

    # --- retraining queue ----------------------------------------------------

    def claim_next_queued(self) -> ClaimedTrigger | None:
        """Atomic claim of the next queued trigger. ``None`` on empty queue."""
        url = f"{self.base_url}/v1/admin/retrain/claim"
        try:
            status, body = self._post(url, body=None)
        except urllib.error.HTTPError as e:
            raise BullpenAdminError(
                f"claim failed: HTTP {e.code} body={e.read().decode('utf-8', 'replace')}"
            ) from e
        if status == 204 or not body:
            return None
        if status == 200:
            return ClaimedTrigger.from_json(json.loads(body))
        raise BullpenAdminError(f"claim unexpected status {status}: {body!r}")

    def mark_complete(
        self,
        trigger_id: str,
        *,
        succeeded: bool,
        produced_version_id: int | None,
        error_message: str | None,
    ) -> None:
        """Report retrain result. Caller MUST set exactly one of producedVersionId/errorMessage."""
        url = f"{self.base_url}/v1/admin/retrain/{trigger_id}/complete"
        payload = {
            "succeeded": succeeded,
            "producedVersionId": produced_version_id,
            "errorMessage": error_message,
        }
        try:
            status, body = self._post(url, body=payload)
        except urllib.error.HTTPError as e:
            raise BullpenAdminError(
                f"mark_complete failed: HTTP {e.code} body={e.read().decode('utf-8', 'replace')}"
            ) from e
        if status != 200:
            raise BullpenAdminError(f"mark_complete unexpected status {status}: {body!r}")

    # --- registry ------------------------------------------------------------

    def register(
        self,
        model_name: str,
        version: str,
        artifact_path: str,
        metadata_path: str,
        feature_pipeline_path: str,
        training_data_hash: str,
        training_data_window: str,
        eval_metrics_json: str,
        trained_at: str,  # ISO-8601 instant
        created_by: str | None = None,
        notes: str | None = None,
    ) -> int:
        """Register a fresh CANDIDATE version. Returns the new ``model_versions.id``."""
        url = f"{self.base_url}/v1/admin/registry/{model_name}/register"
        payload = {
            "modelName": model_name,
            "version": version,
            "artifactPath": artifact_path,
            "metadataPath": metadata_path,
            "featurePipelinePath": feature_pipeline_path,
            "trainingDataHash": training_data_hash,
            "trainingDataWindow": training_data_window,
            "evalMetricsJson": eval_metrics_json,
            "trainedAt": trained_at,
            "createdBy": created_by,
            "notes": notes,
        }
        try:
            status, body = self._post(url, body=payload)
        except urllib.error.HTTPError as e:
            raise BullpenAdminError(
                f"register failed: HTTP {e.code} body={e.read().decode('utf-8', 'replace')}"
            ) from e
        if status != 200:
            raise BullpenAdminError(f"register unexpected status {status}: {body!r}")
        registered = json.loads(body)
        return int(registered["id"])

    def list_versions(self, model_name: str) -> list[dict[str, Any]]:
        """All registered versions for ``model_name``, newest-first per registry contract."""
        url = f"{self.base_url}/v1/admin/registry/{model_name}"
        status, body = self._get(url)
        if status != 200:
            raise BullpenAdminError(f"list_versions unexpected status {status}: {body!r}")
        return list(json.loads(body))

    def next_version_label(self, model_name: str, prefix: str = "v") -> str:
        """Pick the next monotonic version string.

        Returns ``v17`` if ``v16`` is the highest ``v``-prefixed existing version.
        """
        existing = self.list_versions(model_name)
        max_n = 0
        for row in existing:
            v = row.get("version", "")
            if not v.startswith(prefix):
                continue
            suffix = v[len(prefix) :]
            if suffix.isdigit():
                max_n = max(max_n, int(suffix))
        return f"{prefix}{max_n + 1}"

    # --- internal HTTP helpers ----------------------------------------------

    def _post(self, url: str, body: dict[str, Any] | None) -> tuple[int, bytes]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url=url, data=data, method="POST")
        req.add_header("Authorization", self._auth_header)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError:
            raise

    def _get(self, url: str) -> tuple[int, bytes]:
        req = urllib.request.Request(url=url, method="GET")
        req.add_header("Authorization", self._auth_header)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            raise BullpenAdminError(
                f"GET {url} failed: HTTP {e.code} body={e.read().decode('utf-8', 'replace')}"
            ) from e
