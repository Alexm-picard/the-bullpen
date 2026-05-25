"""Single-shot retrain runner. One claim per invocation; 3d.4's systemd timer fires this
hourly during the 2-6 AM ET window.

Usage:
    python -m bullpen_training.retraining.run

Required env:
    BULLPEN_ADMIN_BASE_URL  — e.g. http://localhost:8080
    BULLPEN_ADMIN_USER      — HTTP Basic user (matches THEBULLPEN_ADMIN_BASIC_AUTH)
    BULLPEN_ADMIN_PASSWORD  — HTTP Basic password

Exit codes:
    0 — claimed + completed successfully OR queue empty (nothing to do)
    1 — claimed but the retrain raised; row marked FAILED, error surfaced to operator
    2 — admin-API call failed in a way that left the queue in an inconsistent state (a
        running row may need manual cancellation via the admin endpoint)
"""

from __future__ import annotations

import logging
import os
import sys
from typing import Protocol

import structlog

from bullpen_training.retraining._api_client import (
    BullpenAdminClient,
    BullpenAdminError,
    ClaimedTrigger,
)
from bullpen_training.retraining._dispatch import (
    RetrainOutput,
    UnsupportedModel,
    dispatch_for,
)


class AdminClient(Protocol):
    """Structural protocol matching the slice of ``BullpenAdminClient`` ``run_once`` uses.

    Lets the unit tests pass a duck-typed fake without pyright complaining about the
    nominal mismatch. The production callsite passes a real ``BullpenAdminClient`` (which
    structurally matches).
    """

    def claim_next_queued(self) -> ClaimedTrigger | None: ...

    def next_version_label(self, model_name: str, prefix: str = "v") -> str: ...

    def register(
        self,
        *,
        model_name: str,
        version: str,
        artifact_path: str,
        metadata_path: str,
        feature_pipeline_path: str,
        training_data_hash: str,
        training_data_window: str,
        eval_metrics_json: str,
        trained_at: str,
        created_by: str | None = None,
        notes: str | None = None,
    ) -> int: ...

    def mark_complete(
        self,
        trigger_id: str,
        *,
        succeeded: bool,
        produced_version_id: int | None,
        error_message: str | None,
    ) -> None: ...


log = structlog.get_logger(__name__)


def main() -> int:
    base_url = os.environ.get("BULLPEN_ADMIN_BASE_URL", "http://localhost:8080")
    user = os.environ["BULLPEN_ADMIN_USER"]
    password = os.environ["BULLPEN_ADMIN_PASSWORD"]
    client = BullpenAdminClient(base_url=base_url, user=user, password=password)
    return run_once(client)


def run_once(client: AdminClient) -> int:
    """Visible-for-tests entry point. Claim one trigger, run, report back. Returns exit code."""
    structlog.contextvars.clear_contextvars()
    try:
        claimed = client.claim_next_queued()
    except BullpenAdminError:
        log.exception("retraining: claim failed")
        return 2
    if claimed is None:
        log.info("retraining: queue empty, nothing to do")
        return 0

    structlog.contextvars.bind_contextvars(
        trigger_id=claimed.trigger_id,
        model_name=claimed.model_name,
        trigger_type=claimed.trigger_type,
    )
    log.info("retraining: claimed trigger")

    # --- run the training pipeline ----------------------------------------
    try:
        retrain_fn = dispatch_for(claimed.model_name)
        version_label = client.next_version_label(claimed.model_name)
        log.info("retraining: dispatching", next_version=version_label)
        output: RetrainOutput = retrain_fn(
            claimed.trigger_id, version_label, claimed.trigger_metadata
        )
    except UnsupportedModel as e:
        log.error("retraining: unsupported model_name", error=str(e))
        _report_failure(client, claimed, str(e))
        return 1
    except Exception as e:  # — surfacing every failure to the queue is the contract
        log.exception("retraining: training pipeline raised")
        _report_failure(client, claimed, f"{type(e).__name__}: {e}")
        return 1

    # --- register the candidate -------------------------------------------
    try:
        produced_version_id = client.register(
            model_name=claimed.model_name,
            version=version_label,
            artifact_path=output.artifact_path,
            metadata_path=output.metadata_path,
            feature_pipeline_path=output.feature_pipeline_path,
            training_data_hash=output.training_data_hash,
            training_data_window=output.training_data_window,
            eval_metrics_json=output.eval_metrics_json,
            trained_at=output.trained_at_iso,
            created_by="retrain-worker",
            notes=_notes_for(claimed, version_label),
        )
        log.info("retraining: registered candidate", produced_version_id=produced_version_id)
    except BullpenAdminError as e:
        log.exception("retraining: register failed")
        _report_failure(client, claimed, f"register call failed: {e}")
        return 1

    # --- report success ---------------------------------------------------
    try:
        client.mark_complete(
            claimed.trigger_id,
            succeeded=True,
            produced_version_id=produced_version_id,
            error_message=None,
        )
        log.info("retraining: marked complete (succeeded)")
    except BullpenAdminError:
        log.exception("retraining: mark_complete failed AFTER successful register")
        # Row is stuck in RUNNING but the candidate IS registered. The 3d.4 reaper will
        # re-queue it eventually; an operator would need to manually mark complete via
        # the admin endpoint to keep the audit trail clean.
        return 2
    return 0


def _report_failure(client: AdminClient, claimed: ClaimedTrigger, error_message: str) -> None:
    try:
        client.mark_complete(
            claimed.trigger_id,
            succeeded=False,
            produced_version_id=None,
            error_message=error_message,
        )
    except BullpenAdminError:
        log.exception("retraining: failed to report FAILED status — row may stay RUNNING")


def _notes_for(claimed: ClaimedTrigger, version_label: str) -> str:
    """Compact notes string for the audit trail — leaf 'Known edge cases' says trigger_metadata
    should be visible from the produced candidate row."""
    return (
        f"retrained from {claimed.trigger_type.lower()} trigger {claimed.trigger_id} "
        f"as {claimed.model_name}/{version_label}"
    )


if __name__ == "__main__":
    sys.exit(main())


# expose a logger-config helper so users / tests can attach a console renderer
def configure_logging(level: int = logging.INFO) -> None:
    """Idempotent structlog setup — JSON-rendered to stderr, all bound context included."""
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
    )
