---
name: add-schema-change
description: Coordinated procedure for adding a column or table to SQLite (registry) or ClickHouse (analytics). Trigger when the user says "add a column", "add a table", "schema change", "alter the registry", or wants to evolve either database.
---

# add-schema-change

The two databases serve different purposes and have different change protocols. This skill keeps them in lockstep with the Java repository layer and `/contracts/`.

## Database split (from CLAUDE.md)

| DB | Purpose | Migration tool | File location |
|---|---|---|---|
| **SQLite** | Model registry, A/B config, retraining queue | Flyway | `/backend/src/main/resources/db/migration/V{NNN}__*.sql` |
| **ClickHouse** | Pitches, drift metrics, prediction logs | Manual DDL files run via init script | `/backend/src/main/resources/clickhouse/ddl/{NNN}_*.sql` |

## Hard rules

- **Forward-only migrations.** SQLite Flyway versioning is sequential and never reused.
- **No ClickHouse change on prod without a snapshot first.** A snapshot file newer than 1 hour must exist before any DROP/ALTER touches prod (the `block-destructive-ch` hook enforces this).
- **No JPA entities anywhere.** Repository changes use JdbcTemplate.
- **Update `/contracts/`** when the change affects the Python↔Java file contract (feature columns, prediction log schema seen by analyses).

## Procedure

1. **Clarify the change** — confirm with user:
   - Which DB?
   - Nullable / default / backfill required?
   - Read path: which `Repository` classes need updates?
   - Does `/contracts/feature_pipeline.json` change?
   - Is this a one-off or recurring pattern?
2. **Hand off to the `schema-migration-author` agent** with the clarified scope. It will produce the files.
3. **Review the agent's output** with the user before committing.
4. **Verify locally**:
   - SQLite: `./gradlew flywayMigrate` against a scratch db
   - ClickHouse: run the DDL in a scratch container, verify with `DESCRIBE TABLE`
   - Integration test: `./gradlew test --tests *RepositoryTest`
5. **If prod ClickHouse is affected**:
   - Run a snapshot first: `clickhouse-backup create <name>` (or your snapshot command)
   - Verify snapshot file exists and is recent
   - Apply DDL via the prod runner
6. **Commit** with Conventional Commits:
   - `feat(db): add <column> to <table> for <reason>` — new feature
   - `fix(db): correct <column> dtype in <table>` — bug fix
   - `refactor(db): rename <old> -> <new>` — refactor (requires reversal-path discussion)

## When to involve decision-recorder

- New table or column that changes a contract → yes, draft `decisions.md` entry
- Routine bug fix or typo → no, commit log is enough

## Anti-patterns to refuse

- Editing or renumbering an existing Flyway migration — break the world for everyone with an existing db
- DROP on prod ClickHouse without snapshot — the hook will block; never bypass
- Adding a column to `prediction_logs` without thinking about partition impact (existing partitions don't get backfilled; document this)
