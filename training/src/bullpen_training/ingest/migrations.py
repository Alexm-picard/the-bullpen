"""Apply ClickHouse DDL migrations.

Flyway in the Spring backend only manages the SQLite registry. ClickHouse
DDL lives under `backend/src/main/resources/db/migration/clickhouse/` and
is applied by this helper. A tiny `_schema_migrations` table tracks which
files have run so re-runs are no-ops.

A real Flyway-style ClickHouse runner can land later (Phase 3 worker boot
seems the natural home); for now this keeps Phase 1 unblocked without
introducing a new dependency.
"""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any, cast

from clickhouse_driver import Client

from bullpen_training.logging_config import get_logger

log = get_logger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[4]
# Note: deliberately a sibling of `db/migration/` (Spring Flyway scans the
# `migration` subtree recursively and would try to apply CH SQL against
# SQLite if these landed under it).
DEFAULT_MIGRATIONS_DIR = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "clickhouse"


def _ensure_tracking_table(client: Client) -> None:
    client.execute(
        """
        CREATE TABLE IF NOT EXISTS _schema_migrations (
            version    String,
            checksum   String,
            applied_at DateTime DEFAULT now()
        ) ENGINE = ReplacingMergeTree(applied_at) ORDER BY version
        """
    )


def _applied_versions(client: Client) -> set[str]:
    rows = cast(
        list[tuple[Any, ...]],
        client.execute("SELECT version FROM _schema_migrations FINAL"),
    )
    return {str(r[0]) for r in rows}


def apply_migrations(client: Client, migrations_dir: Path | None = None) -> list[str]:
    """Apply any new V*.sql files in lexical order. Returns applied versions."""
    src = migrations_dir or DEFAULT_MIGRATIONS_DIR
    _ensure_tracking_table(client)
    applied = _applied_versions(client)
    newly_applied: list[str] = []
    for path in sorted(src.glob("V*.sql")):
        version = path.stem  # e.g. "V002__raw_statcast"
        if version in applied:
            log.debug("migration already applied", version=version)
            continue
        sql = path.read_text(encoding="utf-8")
        checksum = hashlib.sha256(sql.encode("utf-8")).hexdigest()
        for statement in _split_statements(sql):
            client.execute(statement)
        client.execute(
            "INSERT INTO _schema_migrations (version, checksum) VALUES",
            [(version, checksum)],
        )
        newly_applied.append(version)
        log.info("migration applied", version=version, checksum=checksum[:12])
    return newly_applied


def _split_statements(sql: str) -> list[str]:
    """Strip `--` comment lines first, then split the remainder on `;`.

    Comment-first order matters: a `;` inside a `-- ...` comment must not be
    treated as a statement boundary.
    """
    decommented = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))
    return [chunk.strip() for chunk in decommented.split(";") if chunk.strip()]
