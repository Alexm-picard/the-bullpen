"""Per-stage SQL assertions baked into every load step.

Loud failures during ingestion are cheaper than silent leakage of bad data
into downstream features. See decisions.md [90].
"""

from __future__ import annotations

from typing import Any, cast

from clickhouse_driver import Client

from bullpen_training.logging_config import get_logger

log = get_logger(__name__)


class AssertionFailure(RuntimeError):
    """Raised when an SQL assertion fails."""


def assert_row_count_in_range(
    client: Client,
    *,
    table: str,
    where: str,
    expected: int,
    tol_pct: float = 5.0,
) -> int:
    """Verify a row count is within ±tol_pct of expected.

    Returns the actual count on success; raises AssertionFailure otherwise.
    """
    sql = f"SELECT count(*) FROM {table} WHERE {where}"
    result = cast(list[tuple[Any, ...]], client.execute(sql))
    actual = int(result[0][0]) if result else 0
    lower = expected * (1 - tol_pct / 100)
    upper = expected * (1 + tol_pct / 100)
    if not (lower <= actual <= upper):
        raise AssertionFailure(
            f"row count out of band: table={table} where={where!r} "
            f"actual={actual} expected~{expected} (±{tol_pct}%)"
        )
    log.info(
        "row count assertion passed",
        table=table,
        where=where,
        actual=actual,
        expected=expected,
        tol_pct=tol_pct,
    )
    return actual


def assert_no_null_pks(
    client: Client,
    *,
    table: str,
    pk_columns: list[str],
    where: str = "1=1",
) -> None:
    """Fail loud if any primary-key column is NULL within the filter."""
    null_predicates = " OR ".join(f"{c} IS NULL" for c in pk_columns)
    sql = f"SELECT count(*) FROM {table} WHERE ({where}) AND ({null_predicates})"
    result = cast(list[tuple[Any, ...]], client.execute(sql))
    null_count = int(result[0][0]) if result else 0
    if null_count != 0:
        raise AssertionFailure(
            f"NULL primary-key values found: table={table} pks={pk_columns} "
            f"where={where!r} count={null_count}"
        )
    log.info("null-PK assertion passed", table=table, pk_columns=pk_columns)
