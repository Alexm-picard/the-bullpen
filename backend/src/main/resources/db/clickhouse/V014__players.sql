-- V014 — Phase 4b.1
-- Player lookup dimension. Used by /v1/players/search to resolve Statcast IDs
-- (pitcher_id / batter_id columns in pitches) into human names. ReplacingMergeTree
-- on `updated_at` so the nightly MLB Stats API roster pull can re-write rows
-- without DELETE — most-recent wins on FINAL reads.
--
-- The set is small (≈4000 active + historical players) so we don't bother with
-- partitioning. ORDER BY id is the primary index (point lookups on id are common
-- via /v1/players/{id} — leaf 4b.2).
--
-- primary_position is FixedString(2) because MLB roster positions are at most 2 chars
-- ("1B", "SS", "CF", "RP", "DH") — keeps the row width down.

CREATE TABLE IF NOT EXISTS players (
    id                UInt32,
    name              String,
    primary_position  FixedString(2),
    bats              FixedString(1),   -- 'L' / 'R' / 'S'
    throws            FixedString(1),   -- 'L' / 'R'
    active            UInt8,            -- 0/1 — boolean
    updated_at        DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;
