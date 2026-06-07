"""Tests for ClickHouseSettings.from_env, incl. the DEV-3 HTTP-port guard."""

from __future__ import annotations

import pytest

from bullpen_training.ingest.clickhouse_client import ClickHouseSettings


def test_from_env_defaults_to_native_port(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("CLICKHOUSE_PORT", raising=False)
    assert ClickHouseSettings.from_env().port == 9000


def test_from_env_honours_a_valid_native_port(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CLICKHOUSE_PORT", "9001")
    assert ClickHouseSettings.from_env().port == 9001


@pytest.mark.parametrize("http_port", ["8123", "8443"])
def test_from_env_rejects_http_ports_loudly(
    http_port: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """DEV-3: a native client pointed at an HTTP port must fail loud, not skip.

    The cryptic 'Unknown packet' a native driver gives on the HTTP port reads as an
    unreachable CH in the leakage fixture and silently skips the SQL-path gate (the
    2026-06-07 build). from_env turns that into a clear, actionable error.
    """
    monkeypatch.setenv("CLICKHOUSE_PORT", http_port)
    with pytest.raises(ValueError, match="HTTP port"):
        ClickHouseSettings.from_env()
