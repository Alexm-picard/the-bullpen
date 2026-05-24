-- V013 — Phase 3a.1
-- The retraining_queue table is the inbox for the Python retraining
-- worker (3d). The worker polls for status='queued' rows, claims one
-- by setting status='running', invokes the appropriate trainer, and
-- writes the produced model_versions row back via produced_version_id.
-- Per rule 6 (no auto-promotion), even a successful retrain leaves
-- the new version at stage='candidate' — human approval via the 3a.4
-- promotion API is the only path to 'shadow' / 'champion'.
--
-- trigger_id is a UUID propagated from the trigger source so we can
-- correlate a queue row with the upstream signal (cron tick, drift
-- detector event, manual API call). UNIQUE so re-firing the same
-- trigger is a no-op insert error rather than a duplicate run.
--
-- trigger_type set: 'scheduled' (monthly floor), 'drift' (3c daily
-- detector), 'manual' (admin endpoint). Same decision-[72] taxonomy
-- used by the trigger producers.

CREATE TABLE retraining_queue (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    trigger_id           TEXT      NOT NULL UNIQUE,
    model_name           TEXT      NOT NULL,
    trigger_type         TEXT      NOT NULL
        CHECK (trigger_type IN ('scheduled','drift','manual')),
    trigger_metadata     TEXT,                              -- JSON
    status               TEXT      NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued','running','succeeded','failed','cancelled')),
    enqueued_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP,
    produced_version_id  INTEGER   REFERENCES model_versions(id),
    error_message        TEXT
);

-- Hot queries: "what's queued for model X" + "what's currently running"
-- both hit (model_name, status).
CREATE INDEX idx_rq_model_status ON retraining_queue(model_name, status);
