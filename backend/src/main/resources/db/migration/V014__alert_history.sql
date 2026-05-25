-- V014 — Phase 3c.7
-- The alert_history table backs the DriftAlertEvaluator's 24h dedup
-- (Discord-spam prevention) + provides the audit trail of every drift
-- alert ever fired. Persists to the registry SQLite (same DB as
-- model_versions / model_routing / experiment_results) so the evaluator
-- and the registry stay co-resident.
--
-- Severity matches the leaf body: PAGE > NOTICE > LOGGED. LOGGED entries
-- never fire to Discord but appear on the Ops dashboard.

CREATE TABLE alert_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    alert_key       TEXT      NOT NULL,
    severity        TEXT      NOT NULL
        CHECK (severity IN ('PAGE', 'NOTICE', 'LOGGED')),
    fired_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    metric_value    REAL,
    metric_threshold REAL,
    details         TEXT
);

-- Hot query: "did this alert_key fire in the last 24h?" — index hits the
-- (key, time) prefix, ORDER BY fired_at DESC LIMIT 1 stops on first row.
CREATE INDEX idx_ah_key_time ON alert_history(alert_key, fired_at);
