"""DP1 regression guards: days_since_last_appearance must never carry the
lagInFrame epoch sentinel (1970-01-01 -> ~20,000 'days')."""

from __future__ import annotations

import os
from pathlib import Path

import pytest

SQL = (
    Path(__file__).parents[2] / "src/bullpen_training/features/sql/compute_tier3.sql"
).read_text()


def test_sql_guards_the_epoch_sentinel() -> None:
    assert "toDate(0)" in SQL, (
        "compute_tier3.sql lost the epoch-sentinel guard: lagInFrame returns 1970-01-01, "
        "not NULL, for a first appearance; without the guard ~4.5% of rows get ~20,000-day garbage"
    )


@pytest.mark.skipif(
    os.environ.get("BULLPEN_REQUIRE_CH") != "1", reason="needs the box/CI ClickHouse"
)
def test_features_table_dsla_bounded() -> None:
    from bullpen_training.ingest.clickhouse_client import make_client

    client = make_client()
    bad = client.query(
        "SELECT countIf(days_since_last_appearance > 400) FROM features"
    ).result_rows[0][0]
    assert bad == 0, f"{bad} rows carry days_since_last_appearance > 400 (epoch garbage)"
