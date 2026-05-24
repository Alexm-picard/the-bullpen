-- V012 — Phase 3a.1
-- The experiment_results table holds the evidence row that gates
-- every champion-promotion event per rule 5 (promotion criteria must
-- be pre-declared + recorded). The 3a.4 promote-model endpoint
-- refuses to promote a challenger whose latest experiment_results row
-- is not status='passed'.
--
-- primary_metric + primary_threshold are declared at experiment START
-- (challenger registration), so the gate can't be moved post-hoc.
-- guardrails is JSON because guardrail sets vary by model — pitch
-- outcomes care about per-pitch-type ECE bounds, batted-ball cares
-- about per-park HR rate drift, etc.
--
-- status transitions: 'running' -> 'passed' | 'failed' | 'aborted'.
-- The metric columns + sample_size_observed get populated when
-- status moves off 'running' (the 3c daily job is what writes them).

CREATE TABLE experiment_results (
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name               TEXT      NOT NULL,
    champion_version_id      INTEGER   NOT NULL REFERENCES model_versions(id),
    challenger_version_id    INTEGER   NOT NULL REFERENCES model_versions(id),
    started_at               TIMESTAMP NOT NULL,
    ended_at                 TIMESTAMP,
    primary_metric           TEXT      NOT NULL,        -- 'brier' | 'log_loss' | 'ece'
    primary_threshold        REAL      NOT NULL,
    guardrails               TEXT      NOT NULL,        -- JSON
    sample_size_target       INTEGER   NOT NULL,
    sample_size_observed     INTEGER,
    champion_metric          REAL,
    challenger_metric        REAL,
    guardrails_observed      TEXT,                      -- JSON
    status                   TEXT      NOT NULL
        CHECK (status IN ('running','passed','failed','aborted')),
    notes                    TEXT,
    created_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Hot query: "give me the latest passing experiment for model X" —
-- both columns hit the index, then we ORDER BY created_at DESC LIMIT 1.
CREATE INDEX idx_er_model_status ON experiment_results(model_name, status);
