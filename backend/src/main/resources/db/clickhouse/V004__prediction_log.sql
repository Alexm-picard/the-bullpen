-- V004 — Phase 1.7
-- Async write target for served predictions. Phase 3b.5 will add a role
-- column with real shadow/challenger semantics and convert `features` /
-- `prediction` from JSON blobs to structured columns. The DEFAULT on
-- `role` here is set to 'champion' so the additive change in 3b.5 is safe.
--
-- TTL set to 18 months at partition (month) granularity, so an entire
-- month's data rolls off at once — keeps disk usage predictable.

CREATE TABLE IF NOT EXISTS prediction_log (
    request_id        UUID,
    request_at        DateTime64(3, 'UTC'),
    model_name        LowCardinality(String),
    model_version     LowCardinality(String),
    role              Enum8('champion' = 1, 'challenger' = 2, 'shadow' = 3) DEFAULT 'champion',
    feature_hash      String,
    features          String,
    prediction        String,
    latency_ms        Float32,
    correlation_id    String
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(request_at)
ORDER BY (model_name, request_at)
TTL toDate(request_at) + INTERVAL 18 MONTH
SETTINGS index_granularity = 8192;
