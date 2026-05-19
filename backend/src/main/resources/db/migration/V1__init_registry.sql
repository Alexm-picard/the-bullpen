-- Initial registry schema placeholder.
-- The real registry tables (model, experiment_results, retraining_queue, ab_config)
-- land in Phase 3a. This migration only proves Flyway + SQLite are wired correctly.

CREATE TABLE IF NOT EXISTS schema_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

INSERT OR IGNORE INTO schema_meta (key, value) VALUES ('initialized_phase', '0');
