-- V029 - Phase 1a (pitch-TYPE head; decision [183], spec:
-- docs/research/2026-07-23_pitch_type_architecture_bakeoff.md section 2, candidate A).
--
-- A SEPARATE feature store for the pre-pitch pitch-TYPE model - NOT new columns on
-- `features` (which serves the pitch-OUTCOME head). Two heads = two schemas, kept
-- apart so a pitch-type rebuild never churns the outcome feature store. Mirrors the
-- V005 `features` engine/partition/order exactly: ReplacingMergeTree(ingested_at),
-- PARTITION BY (toYYYYMM(game_date), fold), ORDER BY (fold, game_date, game_id,
-- at_bat_index, pitch_number). One row per labeled pitch per fold.
--
-- Label: y7 taxonomy (report section 3), FF | SI | FC | SL(=SL+ST+SV) |
-- CU(=CU+KC+CS) | CH | OFF(everything else). Stored as Enum8 like `features.label`;
-- the integer codes are the FIXED fold-independent SEQ vocab (FF=0..OFF=6) so
-- prev1_pt_i / prev2_pt_i share the label's encoding. Rows whose pitch_type is
-- '' / PO / IN are excluded from the labeled set (the window SQL filters them),
-- mirroring how select_labeled_pitches.sql drops non-5-class descriptions.
--
-- Feature tiers (24 model features; the raw categoricals stand/p_throws/park_id are
-- stored raw and integer-encoded downstream via the contract, exactly as `features`
-- stores pitcher_throws/batter_stand/park_id raw for pitch_outcome_pre):
--   Tier S   (11): balls, strikes, outs, inning, base_state (V003, non-null),
--                  stand, p_throws, park_id (V003, raw categoricals),
--                  times_through_order, at_bat_number_in_game, times_faced_today
--                  (V013, Nullable - kept Nullable, NOT coalesced to 0: an honest
--                  "no data" beats a silent 0, matching V006's design note, and
--                  times_faced_today == n_priorpa_thisgame is 0-indexed so 0 is a
--                  real value with no clean out-of-band sentinel).
--   Tier ARS (9):  ars_{FF,SI,FC,SL,CU,CH,OFF} - per-pitcher CAREER-EXPANDING y7
--                  frequency, strictly pre-pitch, NULL at the pitcher's first career
--                  pitch (prior_n = 0). ars_FF_by_count - same but conditioned on
--                  (pitcher, balls, strikes). pitcher_prior_n - prior pitch count
--                  (cold-start indicator; 0 at first career pitch, non-null).
--   Tier SEQ (4):  prev1_pt_i / prev2_pt_i - previous / two-ago pitch's y7 int in the
--                  outing (game_id, pitcher_id), sentinel -1 at outing start;
--                  prev1_missing - 1 at outing start; pitches_into_outing - 0-based
--                  count of the pitcher's prior pitches this game.
--
-- `as_of_date` mirrors `features`: the fold's train cutoff, always < game_date so the
-- CI leakage tests can prove non-future contamination. Streaming temporal cutoff for
-- every derived (ARS/SEQ) column lives in the window SQL (features/sql/
-- compute_pitch_type_{state,arsenal}.sql) - the frame excludes the current pitch.
--
-- SNAPSHOT PRECONDITION (CLAUDE.md hard rule): any DROP/ALTER against prod ClickHouse
-- must be preceded by a snapshot. This migration is CREATE TABLE IF NOT EXISTS only
-- (no DROP/ALTER on existing data), but the precondition still applies before it first
-- runs against the prod box. Additive and idempotent.
--
-- ClickHouseMigrationRunner + training/ingest/migrations.py both apply this by filename
-- and checksum it; this is a NEW V*.sql file, never an edit to an applied one.

CREATE TABLE IF NOT EXISTS pitch_type_features (
    game_id              UInt64,
    at_bat_index         UInt16,
    pitch_number         UInt8,
    game_date            Date,
    as_of_date           Date,
    fold                 UInt8,

    pitcher_id           UInt32,
    batter_id            UInt32,

    -- y7 label (fixed vocab FF=0..OFF=6, shared with the SEQ integer encoding)
    label_pitch_type     Enum8(
        'FF'  = 0,
        'SI'  = 1,
        'FC'  = 2,
        'SL'  = 3,
        'CU'  = 4,
        'CH'  = 5,
        'OFF' = 6
    ),

    -- Tier S: state (5 non-null numerics + 3 raw categoricals + 3 V013 Nullables)
    balls                UInt8,
    strikes              UInt8,
    outs                 UInt8,
    inning               UInt8,
    base_state           UInt8,
    stand                FixedString(1),
    p_throws             FixedString(1),
    park_id              LowCardinality(String),
    times_through_order  Nullable(UInt8),
    at_bat_number_in_game Nullable(UInt16),
    times_faced_today    Nullable(UInt8),

    -- Tier ARS: pitcher career-expanding arsenal frequency (NULL at cold start)
    ars_FF               Nullable(Float32),
    ars_SI               Nullable(Float32),
    ars_FC               Nullable(Float32),
    ars_SL               Nullable(Float32),
    ars_CU               Nullable(Float32),
    ars_CH               Nullable(Float32),
    ars_OFF              Nullable(Float32),
    ars_FF_by_count      Nullable(Float32),
    pitcher_prior_n      UInt32,

    -- Tier SEQ: in-outing sequencing (sentinel -1 / 0 at outing start)
    prev1_pt_i           Int8,
    prev2_pt_i           Int8,
    prev1_missing        UInt8,
    pitches_into_outing  UInt16,

    ingested_at          DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY (toYYYYMM(game_date), fold)
ORDER BY (fold, game_date, game_id, at_bat_index, pitch_number)
SETTINGS index_granularity = 8192;
