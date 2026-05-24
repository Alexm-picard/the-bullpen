-- V007: pitcher_form_current — denormalised Tier 3 form snapshot for live serving (Phase 2a.8).
--
-- The /v1/predict/pitch endpoint needs Tier 3 (rolling form) values to build its feature
-- vector. Computing them inline per request would re-scan ~50M pitch rows; that obviously
-- doesn't fit a 100ms p95 latency budget.
--
-- The plan (Phase 3, plan.md §3) is a nightly worker job that materialises this table from
-- the `features` table for every active pitcher. Read path becomes a single point lookup by
-- pitcher_id. Form lag is at most 1 day — acceptable for v1 (documented edge case in the
-- leaf plan).
--
-- For 2a.8 the controller falls back to receiving Tier 3 values in the request body; this
-- table sits empty until the Phase 3 worker lands. Creating it now lets the Phase 3 job
-- author target a stable schema and avoids a coordinated migration later.

CREATE TABLE IF NOT EXISTS pitcher_form_current (
    pitcher_id                 UInt32,
    as_of_date                 Date,
    -- Counts (LightGBM-native NaN handling means defaults are merely cosmetic).
    pitches_in_game            UInt32 DEFAULT 0,
    pitches_last_28d           UInt32 DEFAULT 0,
    strike_rate_28d            Float32,
    swstrike_rate_28d          Float32,
    inplay_rate_28d            Float32,
    days_since_last_appearance Nullable(UInt16),
    -- ReplacingMergeTree dedups on (pitcher_id) keeping the latest as_of_date row.
    -- Worker job INSERTs a fresh row per pitcher per night; old rows compact away.
    ingested_at                DateTime64(3) DEFAULT now64()
) ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(as_of_date)
PRIMARY KEY (pitcher_id)
ORDER BY (pitcher_id);
