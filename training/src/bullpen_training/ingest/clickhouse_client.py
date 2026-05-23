"""Thin clickhouse-driver wrapper.

Connection settings are env-driven so the same code runs against the local
docker compose ClickHouse (dev) and the WSL2 prod instance (deploy).

Env vars (all optional; defaults match infra/docker-compose.yml):
    CLICKHOUSE_HOST       default "localhost"
    CLICKHOUSE_PORT       default 9000 (native protocol)
    CLICKHOUSE_USER       default "default"
    CLICKHOUSE_PASSWORD   default "thebullpen"
    CLICKHOUSE_DATABASE   default "default"
"""

from __future__ import annotations

import os
from collections.abc import Iterable, Iterator
from dataclasses import dataclass

import pandas as pd
from clickhouse_driver import Client


@dataclass(frozen=True)
class ClickHouseSettings:
    host: str = "localhost"
    port: int = 9000
    user: str = "default"
    password: str = "thebullpen"
    database: str = "default"

    @classmethod
    def from_env(cls) -> ClickHouseSettings:
        return cls(
            host=os.environ.get("CLICKHOUSE_HOST", cls.host),
            port=int(os.environ.get("CLICKHOUSE_PORT", cls.port)),
            user=os.environ.get("CLICKHOUSE_USER", cls.user),
            password=os.environ.get("CLICKHOUSE_PASSWORD", cls.password),
            database=os.environ.get("CLICKHOUSE_DATABASE", cls.database),
        )


def make_client(settings: ClickHouseSettings | None = None) -> Client:
    s = settings or ClickHouseSettings.from_env()
    return Client(
        host=s.host,
        port=s.port,
        user=s.user,
        password=s.password,
        database=s.database,
        settings={"use_numpy": False},
    )


def _row_tuples(df: pd.DataFrame, columns: Iterable[str]) -> Iterator[tuple[object, ...]]:
    """Yield row tuples in column order with NaN → None coercion.

    clickhouse-driver's native protocol wants Python None for Nullable cols,
    not numpy.nan. itertuples is ~3x faster than iterrows on wide frames.
    """
    cols = list(columns)
    for row in df[cols].itertuples(index=False, name=None):
        yield tuple(None if _is_null(v) else v for v in row)


def _is_null(value: object) -> bool:
    if value is None:
        return True
    try:
        return bool(pd.isna(value))
    except (TypeError, ValueError):
        return False


def insert_dataframe(
    client: Client,
    table: str,
    df: pd.DataFrame,
    *,
    columns: Iterable[str] | None = None,
    chunk_size: int = 50_000,
) -> int:
    """Insert a DataFrame via the native protocol in chunks.

    Returns the number of rows written. Chunking keeps the per-INSERT block
    bounded so we never balloon memory on a 700K-row monthly pull.
    """
    cols = list(columns) if columns is not None else list(df.columns)
    if df.empty:
        return 0
    rows_written = 0
    insert_sql = f"INSERT INTO {table} ({', '.join(cols)}) VALUES"
    buffer: list[tuple[object, ...]] = []
    for row in _row_tuples(df, cols):
        buffer.append(row)
        if len(buffer) >= chunk_size:
            client.execute(insert_sql, buffer, types_check=False)
            rows_written += len(buffer)
            buffer.clear()
    if buffer:
        client.execute(insert_sql, buffer, types_check=False)
        rows_written += len(buffer)
    return rows_written
