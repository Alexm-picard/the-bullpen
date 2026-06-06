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
    """Split a multi-statement SQL script on `;`, ignoring any `;` inside a single-quoted
    string literal, a `-- ...` line comment, or a `/* ... */` block comment.

    The previous implementation dropped only whole lines whose stripped text began with
    `--`, then split on `;`. That mis-handled a `;` inside an inline trailing comment
    (``col Int32, -- id; pk``), inside a block comment, or inside a string literal
    (``DEFAULT 'a;b'``) - each became a false statement boundary (DEF-L6). This is a small
    char-scanner instead: comments are stripped from the emitted statements and a `;` only
    splits when we're in plain SQL. Single quotes escape by doubling (`''`), matching
    ClickHouse/ANSI SQL.
    """
    statements: list[str] = []
    buf: list[str] = []
    i, n = 0, len(sql)
    in_string = in_line_comment = in_block_comment = False
    while i < n:
        c = sql[i]
        nxt = sql[i + 1] if i + 1 < n else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
                buf.append("\n")
            i += 1
        elif in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                buf.append(" ")  # avoid gluing tokens that hugged the comment
                i += 2
            else:
                i += 1
        elif in_string:
            buf.append(c)
            if c == "'":
                if nxt == "'":  # doubled quote = escaped literal quote, stay in string
                    buf.append(nxt)
                    i += 2
                    continue
                in_string = False
            i += 1
        elif c == "-" and nxt == "-":
            in_line_comment = True
            i += 2
        elif c == "/" and nxt == "*":
            in_block_comment = True
            i += 2
        elif c == "'":
            in_string = True
            buf.append(c)
            i += 1
        elif c == ";":
            stmt = "".join(buf).strip()
            if stmt:
                statements.append(stmt)
            buf = []
            i += 1
        else:
            buf.append(c)
            i += 1
    tail = "".join(buf).strip()
    if tail:
        statements.append(tail)
    return statements
