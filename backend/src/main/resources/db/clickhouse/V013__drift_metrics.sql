-- V013 — Phase 3c.1
-- The drift_metrics table holds every computed drift signal across every
-- (model, feature/segment, metric_type) tuple. 3c.2–3c.5 batch jobs
-- (PSI-feature, PSI-prediction, calibration, segment-Brier) write here;
-- the Ops dashboard reads from here; 3c.7's alerting compares the latest
-- value against pre-declared thresholds.
--
-- Decisions:
--   [74] three drift types tracked separately (PSI / Brier / calibration)
--   — encoded as the metric_type Enum8.
--
-- TTL = 36 months (Risk Register G8) at month-partition granularity so an
-- entire month's worth of metrics rolls off at once.
--
-- ORDER BY chosen so the hot query "give me the last 30 days for model X
-- of metric_type Y" hits the index prefix. feature_or_segment is NOT in
-- the sort key — it's secondary; queries that pivot by it scan the
-- per-(model, type) granule, which is cheap at the volumes we expect
-- (≤500 distinct feature/segment values × a handful of models).

CREATE TABLE IF NOT EXISTS drift_metrics (
    computed_at        DateTime,
    model_name         LowCardinality(String),
    model_version_id   UInt32,
    metric_type        Enum8('psi_feature' = 1, 'psi_prediction' = 2, 'brier' = 3,
                             'calibration_error' = 4, 'segment_brier' = 5),
    feature_or_segment LowCardinality(String),
    metric_value       Float64,
    sample_size        UInt32,
    window_start       DateTime,
    window_end         DateTime
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(computed_at)
ORDER BY (model_name, model_version_id, metric_type, computed_at)
TTL toDate(computed_at) + INTERVAL 36 MONTH
SETTINGS index_granularity = 8192;
