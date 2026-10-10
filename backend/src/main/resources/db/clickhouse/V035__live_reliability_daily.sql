-- V035 - live top-label reliability daily rollup (the /accuracy calibration chart, decision Q1 = R).
--
-- pitches_live keeps 14 days (V015 TTL), the /accuracy calibration chart wants 30. The historical
-- pitches table cannot stand in (different description vocabulary, no ingested_at for the temporal
-- guard). So a worker job (LiveReliabilityRollupJob, 06:30 ET) materialises the SAME truth join the
-- rolling-accuracy scorecard runs into per-(model, ET game day, bin) sums BEFORE pitches_live
-- expires them. The api reads only this small table.
--
-- Population: prediction_log role = champion rows with a live pitch key (served predictions per
-- [188] / ADR-0016), deduped to the latest re-log per pitch key. Nothing is written to
-- prediction_log by this table or its job.
--
-- bin is the top-label confidence decile: least(toUInt8(floor(c * 10)), 9), edges [b/10, (b+1)/10)
-- with the last bin closed at 1.0. n / hits / sum_confidence are SUMS so any window is a re-sum,
-- never an average of percentages.
--
-- ZERO-GRID CONTRACT: every (model, day) the job recomputes is written as the FULL 10-bin grid,
-- zero rows included. ReplacingMergeTree(computed_at) keeps the newest row per ORDER BY key, so a
-- feed correction that empties a bin overwrites the stale count with an explicit zero instead of
-- leaving it behind. Readers must apply FINAL first and filter n > 0 OUTSIDE the FINAL subquery.
--
-- TTL matches prediction_log retention (18 months), which is also what lets the offseason read
-- fall back to the last 30 days of play.
CREATE TABLE IF NOT EXISTS live_reliability_daily (
    model_name     LowCardinality(String),
    game_date      Date,
    bin            UInt8,
    n              UInt64,
    hits           UInt64,
    sum_confidence Float64,
    computed_at    DateTime64(3, 'UTC')
)
ENGINE = ReplacingMergeTree(computed_at)
PARTITION BY toYYYYMM(game_date)
ORDER BY (model_name, game_date, bin)
TTL game_date + INTERVAL 18 MONTH;
