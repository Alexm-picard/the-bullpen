-- V010 — Phase 3a.1
-- The model_versions table is the registry's source of truth for every
-- trained model that has ever been registered. Decision [65] locks the
-- 4-stage lifecycle (candidate -> shadow -> champion -> archived) and
-- decision [66] requires every registration to carry the feature schema
-- hash (rule 7 — refuse models whose schema hash doesn't match the
-- production feature pipeline).
--
-- The artifact_path + metadata_path point at S3-compatible storage
-- (ADR-0007) — the registry stores paths, not bytes. Decision [68]:
-- training_data_window + training_data_hash are persisted so we can
-- recover the exact slice a model was trained on.
--
-- (model_name, version) is the natural key; the AUTOINCREMENT id is
-- the foreign-key target for model_routing + experiment_results +
-- retraining_queue (decisions [71] [72]).

CREATE TABLE model_versions (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name          TEXT      NOT NULL,
    version             TEXT      NOT NULL,
    artifact_path       TEXT      NOT NULL,
    metadata_path       TEXT      NOT NULL,
    training_data_hash  TEXT      NOT NULL,
    training_data_window TEXT     NOT NULL,
    feature_schema_hash TEXT      NOT NULL,
    eval_metrics        TEXT      NOT NULL,        -- JSON
    trained_at          TIMESTAMP NOT NULL,
    promoted_at         TIMESTAMP,
    stage               TEXT      NOT NULL
        CHECK (stage IN ('candidate','shadow','champion','archived')),
    created_by          TEXT,
    notes               TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (model_name, version)
);

-- The hot query is "give me the active champion / shadow for model X" —
-- both columns appear in the WHERE so the composite index is the right
-- shape.
CREATE INDEX idx_mv_model_stage ON model_versions(model_name, stage);
