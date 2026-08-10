-- V030 - pitcher_pitchtype_prior_current: a per-pitcher CAREER-EXPANDING pitch-TYPE COUNT
-- snapshot, feeding the Tier-ARS features of the pre-pitch pitch-TYPE head (decision [183];
-- companion of V029's `pitch_type_features` training store). PR 2a of pitch_type serving.
--
-- One row per pitcher. A refresh job (NOT in this migration) recomputes every pitcher's
-- career-to-date y7 counts from `pitches` in one pass; the serving path reads a single row by
-- point lookup and adds an in-game DELTA from `pitches_live` to reconstruct exactly the
-- strictly-before-this-pitch counts the model trained on.
--
-- Engine/layout mirrors V007 pitcher_form_current, for the same reason V007 exists:
-- ReplacingMergeTree(ingested_at), PARTITION BY toYYYYMM(as_of_date), ORDER BY (pitcher_id).
--
-- ===========================================================================================
-- (a) WHY THIS TABLE EXISTS. Do NOT "simplify" it back into a per-request live query.
-- ===========================================================================================
-- Measured against real ClickHouse (local corpus, 7.4M rows): the per-request
-- career-expanding query reads 7,442,393 rows - 100% of the table - for ONE pitcher. It does
-- that because `pitches` is ORDER BY (game_date, game_id, at_bat_index, pitch_number) and
-- pitcher_id sits in NEITHER the sort key NOR any skip index, so the primary index prunes
-- ZERO granules: every granule must be opened to find out whether it contains that pitcher.
-- Cost: 22-90ms locally, extrapolating to roughly 150-600ms on prod's ~50M rows, against a
-- connection pool of 8 and with no max_execution_time to bound the tail. A handful of
-- concurrent live games would saturate the pool with full-table scans.
--
-- The snapshot inverts the cost: ONE pass over the same data computes the counts for ALL
-- pitchers at once - measured at 38ms producing ~2,602 rows - and the serving path then does a
-- point lookup on a ~2.6k-row table keyed by pitcher_id. This is precisely the trade V007
-- already recorded for Tier-3 rolling form; the arithmetic is if anything more lopsided here,
-- because the ARS window reaches back to the corpus floor rather than 28 days.
--
-- The base scan MUST cover the whole corpus (all of `pitches`, i.e. the same 2015-01-01 floor
-- compute_pitch_type_arsenal.sql passes as :corpus_start), not a recent window: ARS is a
-- CAREER-expanding frequency, and a windowed base would silently change the feature's meaning
-- relative to training.
--
-- ===========================================================================================
-- (b) COUNTS, NEVER RATIOS. This is the single most important property of this schema.
-- ===========================================================================================
-- The snapshot means "career through as_of_date". Training (compute_pitch_type_arsenal.sql)
-- means "strictly before THIS pitch". Those two differ by exactly the pitches the pitcher has
-- already thrown in the current game, which the serving path supplies as a delta.
--
-- Because ARS is a RATIO OF COUNTS, it decomposes EXACTLY:
--
--     ars_c = (base_n_c + delta_n_c) / (base_prior_n + delta_prior_n)
--
-- and is NULL (NaN at serve time) if and only if the denominator is 0 - which is the same
-- cold-start rule the training SQL applies via if(prior_n = 0, NULL, ...). Reconstruction is
-- exact, not approximate.
--
-- Store RATIOS instead and that property is destroyed: a ratio cannot absorb the delta (you
-- cannot recover the denominator it was divided by), so the serving path would be forced into
-- a weighted-average fudge - an approximation of a quantity that is exactly computable, with a
-- train/serve skew that no metric on the dashboard would ever show. Keep the counts. Any
-- future column added here is a COUNT; ratios are derived at read time and never persisted.
--
-- ===========================================================================================
-- (c) THE as_of_date TRAP. as_of_date is an OBSERVED FACT, not today().
-- ===========================================================================================
-- as_of_date MUST be set to max(game_date) actually present in `pitches` at snapshot time:
--
--     SELECT max(game_date) FROM pitches   -- the value to write into as_of_date
--
-- NOT today(), and not "the date the job ran". Reason: last night's games only reach `pitches`
-- via the overnight pitches_live -> pitches handoff. If the snapshot claimed as_of_date =
-- today() while the handoff had not yet run, then last night's pitches would be in NEITHER
-- side of the decomposition - not in the snapshot (they are not in `pitches` yet) and not in
-- the delta (the delta is lower-bounded by game_date > as_of_date, which excludes them). The
-- result is a silent undercount of prior_n and of every class count: no error, no null, no
-- drift alarm, just a subtly wrong feature vector on the first pitches of the evening.
--
-- Anchoring as_of_date to what is DEMONSTRABLY in the table makes the delta exact regardless
-- of job ordering: the snapshot covers (-inf, as_of_date], the delta covers (as_of_date,
-- current pitch). No gap, no double count, and the two jobs never need to be sequenced.
--
-- Corollary for readers of the delta: the composition is only valid for a pitch whose
-- game_date is strictly AFTER as_of_date. For a pitch on or before as_of_date the snapshot
-- already contains it (and every later pitch of that game), so there is nothing to add and the
-- decomposition does not apply.
--
-- ===========================================================================================
-- BY-COUNT ARRAYS (0..11) AND CLICKHOUSE'S 1-BASED INDEXING
-- ===========================================================================================
-- prior_n_by_count / n_ff_by_count carry the same counts conditioned on the ball-strike count,
-- matching training's w_ars_count window (PARTITION BY pitcher_id, balls, strikes). The slot
-- is indexed
--
--     slot = balls * 3 + strikes            (balls 0..3, strikes 0..2  =>  slot 0..11)
--
-- ClickHouse arrays are ONE-INDEXED, so a caller reads the element at balls * 3 + strikes + 1.
-- Off-by-one here is silent, not loud: ClickHouse returns the type default (0) for an
-- out-of-range subscript rather than raising, so a wrong index degrades into
-- prior_n_by_count = 0 -> a NULL ars_FF_by_count. Both the writer and the reader must agree on
-- the +1; the repository centralises it in one helper.
--
-- The arrays are FIXED LENGTH 12 and the CHECK constraints below enforce it on INSERT: a
-- refresh job that builds them with groupArray-style aggregation would otherwise emit SHORT
-- arrays for pitchers who never faced some count, and every missing slot would read back as 0
-- (i.e. NULL ratio) instead of failing. Build them densely, e.g. over range(12).
--
-- ===========================================================================================
-- y7 MAPPING AND LABELED-SET FILTER - COPY, DO NOT RE-DERIVE
-- ===========================================================================================
-- The refresh job and the serving-side delta MUST classify with the exact multiIf from
-- training/src/bullpen_training/features/sql/compute_pitch_type_arsenal.sql (which is the
-- source of truth; it in turn matches compute_pitch_type_state.sql and V029's Enum8 vocab):
--
--     multiIf(
--         pitch_type = 'FF', 'FF',
--         pitch_type = 'SI', 'SI',
--         pitch_type = 'FC', 'FC',
--         pitch_type IN ('SL', 'ST', 'SV'), 'SL',
--         pitch_type IN ('CU', 'KC', 'CS'), 'CU',
--         pitch_type = 'CH', 'CH',
--         'OFF'
--     ) AS y7
--
-- and MUST apply the same labeled-set filter, so prior_n counts prior LABELED pitches only:
--
--     AND pitch_type NOT IN ('', 'PO', 'IN')
--
-- Note that the OFF bucket is the multiIf's fallthrough, so anything that survives the filter
-- and is not one of the named types lands in n_off. Changing either the mapping or the filter
-- on one side only produces a train/serve skew that is invisible in every served probability.
--
-- Read `pitches FINAL` in the refresh, exactly as the training SQL does: `pitches` is a
-- ReplacingMergeTree and a re-ingested pitch would otherwise be counted twice (DEF-H3).
--
-- Do NOT carry compute_pitch_type_arsenal.sql's trailing `SETTINGS max_memory_usage = 0` onto
-- anything on the request path. It is an offline-batch escape hatch; an unbounded query on the
-- serving pool is how one bad request takes the box down.
--
-- ===========================================================================================
-- ENGINE / DEDUP NOTES
-- ===========================================================================================
-- ReplacingMergeTree(ingested_at) keyed on (pitcher_id): each refresh INSERTs a fresh row per
-- pitcher and the previous one compacts away. Reads MUST use FINAL. That is load-bearing
-- across a month boundary: background merges only collapse duplicates WITHIN a partition, and
-- the partition key is toYYYYMM(as_of_date), so the previous month's row survives on disk
-- indefinitely. SELECT ... FINAL performs a merging read over the parts it reads (ClickHouse
-- default do_not_merge_across_partitions_select_final = 0), so the point lookup still returns
-- one row - but a non-FINAL read would return two, one of them stale. Dropping partitions
-- older than the current month is optional hygiene, not a correctness requirement.
--
-- ingested_at is DateTime64(3), matching V007. Millisecond resolution removes a version tie
-- between two refresh passes inside one wall-clock second: at nightly cadence that is
-- unreachable, but a ReplacingMergeTree resolves a tie arbitrarily, and "arbitrarily" on the
-- table a served prior reads from is not a property worth economising on.
-- A single INSERT ... SELECT evaluates now64(3)
-- once, so one refresh pass is internally consistent; two refresh passes landing within the
-- same wall-clock second would tie on the version column and dedup would be arbitrary between
-- them. Nightly cadence makes that unreachable in practice.
--
-- SNAPSHOT PRECONDITION (CLAUDE.md hard rule): any DROP/ALTER against prod ClickHouse must be
-- preceded by a snapshot. This migration is CREATE TABLE IF NOT EXISTS only - additive,
-- idempotent, no DROP/ALTER and no DML against existing data - but the precondition still
-- applies before it first runs against the prod box.
--
-- ClickHouseMigrationRunner + training/ingest/migrations.py both apply this by filename and
-- checksum it; this is a NEW V*.sql file, never an edit to an applied one.

CREATE TABLE IF NOT EXISTS pitcher_pitchtype_prior_current (
    pitcher_id           UInt32,
    -- max(game_date) OBSERVED in `pitches` when the snapshot ran - see (c). Never today().
    as_of_date           Date,

    -- Career-through-as_of_date LABELED pitch count (the ARS denominator, and itself the
    -- `pitcher_prior_n` model feature / cold-start indicator).
    prior_n              UInt32,

    -- Per-y7-class career counts (the ARS numerators). Sum of the seven == prior_n.
    n_ff                 UInt32,
    n_si                 UInt32,
    n_fc                 UInt32,
    n_sl                 UInt32,
    n_cu                 UInt32,
    n_ch                 UInt32,
    n_off                UInt32,

    -- Same counts conditioned on the ball-strike count; slot = balls * 3 + strikes (0..11),
    -- read one-indexed as [balls * 3 + strikes + 1]. Only the FF numerator is materialised
    -- because ars_FF_by_count is the only by-count ARS feature in the V029 contract.
    prior_n_by_count     Array(UInt32),
    n_ff_by_count        Array(UInt32),

    ingested_at          DateTime64(3) DEFAULT now64(3),

    -- Fail loud on a short/absent by-count array rather than reading missing slots back as 0.
    CONSTRAINT prior_n_by_count_len CHECK length(prior_n_by_count) = 12,
    CONSTRAINT n_ff_by_count_len CHECK length(n_ff_by_count) = 12
) ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(as_of_date)
PRIMARY KEY (pitcher_id)
ORDER BY (pitcher_id);
