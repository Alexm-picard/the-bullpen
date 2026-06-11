# Migration numbering (L5 convention, 2026-06-11)

Two migration dirs live here, applied by different engines:

- `db/migration/` - SQLite (registry), applied by Flyway at boot.
- `db/clickhouse/` - ClickHouse DDL, applied by `ClickHouseMigrationRunner`
  at boot (filename order).

**The live ClickHouse ledger carries duplicate version numbers** from
parallel work landing in one window: `V012__prediction_log_model_version_id`

- `V012__raw_statcast_expanded`, and two V013s. They are applied and
  immutable - do NOT renumber them (the runner tracks applied filenames; a
  rename would re-apply). Consequence: applied order on the box differs from
  lexicographic order for those pairs.

## Picking the next number

1. Check BOTH dirs: `ls db/migration db/clickhouse | sort` - the next number
   is one past the HIGHEST used in the dir you are adding to, never a reuse.
2. The two dirs number independently (SQLite is at V015, ClickHouse at V018
   as of this note) - do not try to keep them in sync.
3. One change, one file; if a schema change spans both stores, use the
   add-schema-change skill, which coordinates the pair.
