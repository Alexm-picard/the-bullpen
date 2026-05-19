# ADR-0003: Split storage by workload — ClickHouse for analytics, SQLite for app state — not Postgres-only

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: alex
- **Related**: `decisions.md` entries [31] [32] [33], `plan.md` Phase 0 §3a, `design.md` §4 §10

## Context

The Bullpen has two distinct data workloads:

1. **Analytical / OLAP** — billions of historical pitches (2015–2025 Statcast),
   prediction logs (one row per served prediction × per shadow model), drift
   metrics (PSI per feature per day per model). Wide tables, columnar scans,
   long retention, time-series-shaped queries. Read patterns: aggregate over
   a window, group by segment, compute calibration on the tail.
2. **Transactional / OLTP** — model registry (a few hundred rows ever),
   experiment_results (one row per CV evaluation), A/B config, retraining
   queue. Small, mutable, frequently updated, transactional integrity matters,
   read-modify-write under the registry's promotion gate.

StudyForesight uses Postgres for everything (with pgvector for embeddings).
The "obvious" thing would be to copy that pattern and pick a single store.
The candidates were:

- **Postgres only** — TimescaleDB extension for the time-series side, plain
  tables for app state. One database, one driver.
- **ClickHouse + SQLite** — workload-matched stores, two drivers.
- **DuckDB + SQLite** — DuckDB as the analytical engine, SQLite for state.

The project is also explicitly positioned (decision [33]) as "different
paradigm from StudyForesight's Postgres+pgvector" — workload-appropriate
tooling is itself the resume signal here, not a defect.

## Decision

We use **ClickHouse** (Docker, single-node) for `pitches`, `prediction_log`,
and `drift_metrics`; we use **SQLite** (with Flyway migrations) for the
model registry, `experiment_results`, A/B config, and `retraining_queue`.

The two databases are accessed through separate repositories in `data/`
(decision: `ModelRegistryRepository` over SQLite via `JdbcTemplate`;
`PredictionLogRepository`, `DriftMetricsRepository` over ClickHouse via the
official `clickhouse-jdbc` driver — see `plan.md` Phase 3 hardening
additions for the repository pattern). No transactions span the two stores —
the app-state write happens first, and the analytical write is
fire-and-forget (decision [30]).

## Consequences

**Easier:**

- Analytical queries on `pitches` and `prediction_log` get ClickHouse's
  columnar scan and `MergeTree`/`ReplacingMergeTree` deduplication semantics
  for free. Dedup on pitch identity `(game_id, at_bat_index, pitch_number)`
  is decision [92] — native to ClickHouse, painful in Postgres.
- The registry stays on a single-file embedded database. No "database server
  for 200 rows" overhead. Backups for the registry are a file copy.
- Two database choices, each justified by its workload, reads as
  considered engineering rather than tool fetishism.
- Different paradigm from StudyForesight — the differentiation goal of the
  whole project.

**Harder:**

- Two JDBC drivers, two connection-pool configurations, two backup
  strategies. The ClickHouse backup story is the Layer 1 daily snapshot via
  `clickhouse-backup`; the SQLite registry is rsync-and-zip into the same
  backup window.
- No cross-store joins. Queries that want to correlate registry state
  (e.g., "which model version is currently champion") with prediction logs
  must do the join in Java, not SQL.
- Schema changes must be coordinated — the `schema-migration-author` agent
  exists specifically because changing one usually implies changing the
  other.

**New failure modes:**

- ClickHouse container memory limits in WSL2 can starve the database (called
  out in CLAUDE.md gotchas). Mitigated by setting a `.wslconfig` memory cap
  and Docker resource limits.
- A retraining queue write succeeding while the prediction log write to
  ClickHouse silently drops (per decision [30], async logging _is_
  drop-on-overflow). Acceptable — by design — but the drift detector must
  not assume log completeness on short windows.

**Locked into:**

- All operational tooling (backups, restore drills, monitoring) handles two
  stores. The restore drill (CLAUDE.md rule 8) explicitly exercises both.

## Alternatives Considered

### Alternative A: Postgres-only with TimescaleDB

- One database server, one driver, one backup story. TimescaleDB hypertables
  cover the time-series side.
- Rejected: Postgres column-store performance is still meaningfully worse
  than ClickHouse for the multi-billion-row aggregations the drift
  detector runs, and TimescaleDB adds an extension whose roadmap and
  licensing change frequently. More importantly: same-paradigm-as-
  StudyForesight is the wrong framing for this project.

### Alternative B: DuckDB + SQLite

- DuckDB as the analytical engine (embedded, no server), SQLite for state.
- Rejected: DuckDB is excellent for analytical _files_ and exploratory
  workloads, but is not designed as a persistent server-style analytical
  store for streaming inserts at the rate we need (per-pitch logging
  during a live game). The ClickHouse story is also more reviewer-legible
  as a "production OLAP" choice.

### Alternative C: ClickHouse for everything (including the registry)

- One store, no SQLite at all.
- Rejected: ClickHouse has no transactional guarantees in the OLTP sense.
  The promotion gate (CLAUDE.md rule 5) needs read-modify-write
  consistency on a single registry row; ClickHouse's eventual-consistency
  semantics around `ALTER TABLE … UPDATE` would make that gate
  unreliable.

## Revision History

(none)
