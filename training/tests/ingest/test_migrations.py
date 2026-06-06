"""Unit tests for the SQL statement splitter (DEF-L6).

`_split_statements` must treat `;` as a statement boundary ONLY in plain SQL - never
inside a `-- ...` line comment, a `/* ... */` block comment, or a single-quoted string
literal. The pre-fix splitter dropped only whole `--`-prefixed lines then split on `;`,
which shredded any statement carrying an inline trailing comment or a string literal that
contained a semicolon. A real committed migration (V016__weather_observed.sql) tripped
exactly this - its inline column comments contain "; NULL when absent" - so the splitter
broke one CREATE TABLE into three invalid fragments.
"""

from __future__ import annotations

from pathlib import Path

from bullpen_training.ingest.migrations import DEFAULT_MIGRATIONS_DIR, _split_statements


def test_basic_two_statements_split_on_semicolon() -> None:
    sql = "CREATE TABLE a (x Int32) ENGINE=Memory;\nCREATE TABLE b (y Int32) ENGINE=Memory;"
    stmts = _split_statements(sql)
    assert len(stmts) == 2
    assert stmts[0].startswith("CREATE TABLE a")
    assert stmts[1].startswith("CREATE TABLE b")


def test_full_line_comments_are_dropped() -> None:
    sql = "-- header\n-- more\nCREATE TABLE a (x Int32) ENGINE=Memory;"
    stmts = _split_statements(sql)
    assert len(stmts) == 1
    assert stmts[0].startswith("CREATE TABLE a")
    assert "header" not in stmts[0]


def test_semicolon_inside_inline_comment_is_not_a_boundary() -> None:
    sql = "CREATE TABLE t (\n  a Int32, -- the id; primary-ish\n  b Int32\n) ENGINE=Memory;"
    assert len(_split_statements(sql)) == 1


def test_semicolon_inside_string_literal_is_not_a_boundary() -> None:
    sql = "CREATE TABLE t (a String DEFAULT 'x;y') ENGINE=Memory;\nINSERT INTO t VALUES ('p;q');"
    stmts = _split_statements(sql)
    assert len(stmts) == 2
    assert "'x;y'" in stmts[0]
    assert "'p;q'" in stmts[1]


def test_doubled_quote_escape_keeps_one_string() -> None:
    # 'O''Brien;Jr' is ONE literal (the '' is an escaped quote); the ; inside must not split.
    sql = "INSERT INTO t VALUES ('O''Brien;Jr');\nINSERT INTO t VALUES ('z');"
    stmts = _split_statements(sql)
    assert len(stmts) == 2
    assert "O''Brien;Jr" in stmts[0]


def test_semicolon_inside_block_comment_is_not_a_boundary() -> None:
    sql = (
        "CREATE TABLE t (a Int32) /* note; still one */ ENGINE=Memory;\n"
        "CREATE TABLE u (b Int32) ENGINE=Memory;"
    )
    assert len(_split_statements(sql)) == 2


def test_comment_only_input_yields_no_statements() -> None:
    assert _split_statements("-- just a note\n/* and a block */\n") == []


def test_real_v016_migration_is_a_single_statement() -> None:
    # Regression for the file that exposed DEF-L6: its inline column comments contain
    # semicolons, which the old splitter wrongly treated as boundaries.
    v016 = DEFAULT_MIGRATIONS_DIR / "V016__weather_observed.sql"
    if not v016.exists():
        return  # migration set moved; the synthetic cases above still cover the fix
    stmts = _split_statements(Path(v016).read_text(encoding="utf-8"))
    assert len(stmts) == 1
    assert stmts[0].startswith("CREATE TABLE IF NOT EXISTS weather_observed")


def test_all_real_migrations_split_without_empty_or_comment_only_fragments() -> None:
    for p in sorted(DEFAULT_MIGRATIONS_DIR.glob("V*.sql")):
        for stmt in _split_statements(p.read_text(encoding="utf-8")):
            assert stmt.strip(), f"empty statement from {p.name}"
            assert not stmt.lstrip().startswith("--"), f"comment-only statement from {p.name}"
