-- V015 — Phase 3B (B3): ops-event log backing the live /ops "Ops Log" section.
-- A chronological audit feed of operationally-meaningful events — registrations,
-- promotions, deploys, drift alerts, retrain completions, restore drills — surfaced
-- read-only at GET /v1/ops/events and rendered by the Ops dashboard's OpsLogTable.
--
-- Lives in the registry SQLite alongside model_versions / model_routing /
-- experiment_results / alert_history so the operational state stays co-resident.
-- Writers are best-effort (an emit failure never breaks the operation that triggered
-- it). The frontend maps these underscore type names to its hyphenated display labels.

CREATE TABLE ops_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    type        TEXT      NOT NULL
        CHECK (type IN ('DEPLOY','REGISTER','PROMOTE','DRIFT_OK','ALERT','RETRAIN_OK','RESTORE_DRILL')),
    detail      TEXT      NOT NULL
);

-- Hot query: most-recent N events for the dashboard window — index serves the
-- ORDER BY occurred_at DESC LIMIT N.
CREATE INDEX idx_oe_time ON ops_events(occurred_at);
