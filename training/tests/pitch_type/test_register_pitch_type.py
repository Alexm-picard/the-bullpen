"""Tests for the pitch-type registration driver (decisions [183], [184]).

The driver is what makes the gate reachable, so the properties worth pinning are the ones that
decide whether a box run is safe: it must not touch the registry unless asked, it must gate the
rule-9 baseline before the primary, and a gate failure must abort before anything is registered.

Uses a real trained bundle rather than mocks wherever the assertion is about the gate, and a
recording fake for the HTTP client, which is a hard external boundary.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, ClassVar

import numpy as np
import pandas as pd
import pytest
from click.testing import CliRunner

from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.register_gate import BASELINE_MODEL, PRIMARY_MODEL
from bullpen_training.pitch_type.register_pitch_type import main


def _frame(n: int = 900, seed: int = 0) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    df = pd.DataFrame({c: rng.random(n) for c in PITCH_TYPE_FEATURE_COLUMNS})
    k = len(PITCH_TYPE_CLASSES)
    df["label"] = (df["ars_FF"] * k).astype("int64").clip(0, k - 1)
    return df


class _Loader:
    park_id_mapping: ClassVar[dict[str, int]] = {"PARK00": 0}

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        return _frame(n=900, seed=start_year)


@pytest.fixture(scope="module")
def artifacts(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Both rule-9 bundles, produced exactly as the box produces them."""
    from bullpen_training.pitch_type.export_lr_onnx import export as export_lr
    from bullpen_training.pitch_type.export_onnx import export as export_primary
    from bullpen_training.pitch_type.production import train_and_persist, train_and_persist_lr

    root = tmp_path_factory.mktemp("artifacts")
    train_and_persist(
        _Loader(),
        version="v1",
        artifacts_dir=root,
        skip_cv=True,
        num_boost_round=25,
        early_stopping_rounds=5,
    )
    export_primary(version="v1", artifacts_dir=root)
    train_and_persist_lr(_Loader(), version="v1", artifacts_dir=root, skip_cv=True)
    export_lr(model_name=BASELINE_MODEL, version="v1", artifacts_dir=root)
    return root


class _RecordingClient:
    """Records registrations instead of making them. The HTTP boundary is the one place this
    suite mocks - everything else runs the real gate against a real bundle."""

    instances: ClassVar[list[_RecordingClient]] = []

    def __init__(self, base_url: str, user: str, password: str) -> None:
        self.base_url = base_url
        self.registered: list[dict[str, Any]] = []
        self.listed: list[str] = []
        _RecordingClient.instances.append(self)

    def register(self, **kwargs: Any) -> int:
        self.registered.append(kwargs)
        return 100 + len(self.registered)

    def list_versions(self, model_name: str) -> list[dict[str, Any]]:
        self.listed.append(model_name)
        return []


@pytest.fixture(autouse=True)
def _reset_clients() -> None:
    _RecordingClient.instances.clear()


def _run(artifacts: Path, *args: str) -> Any:
    return CliRunner().invoke(main, ["--artifacts-dir", str(artifacts), *args])


def test_dry_run_is_the_default_and_never_touches_the_registry(
    artifacts: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """THE SAFETY PROPERTY. The gate must be runnable against a fresh box bundle with no risk of
    writing a row - so the client is not merely unused, it is never constructed."""
    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    result = _run(artifacts)
    assert result.exit_code == 0, result.output
    assert _RecordingClient.instances == []
    assert "DRY RUN" in result.output
    assert PRIMARY_MODEL in result.output and BASELINE_MODEL in result.output


def test_the_baseline_is_registered_before_the_primary(
    artifacts: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Rule 9 ordering. [183]'s guardrail binds the primary to the baseline and the gate refuses a
    primary whose baseline is not registered, so the reverse order fails on step two having
    already written a row on step one."""
    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    monkeypatch.setenv("BULLPEN_ADMIN_USER", "u")
    monkeypatch.setenv("BULLPEN_ADMIN_PASSWORD", "p")
    result = _run(artifacts, "--register")
    assert result.exit_code == 0, result.output
    client = _RecordingClient.instances[0]
    assert [r["model_name"] for r in client.registered] == [BASELINE_MODEL, PRIMARY_MODEL]


def test_a_gate_failure_aborts_before_anything_is_registered(
    artifacts: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A broken BASELINE must stop the run before the primary is registered. Registering a primary
    whose baseline was refused would leave [183]'s guardrail bound to a row that does not exist."""
    import shutil

    broken = tmp_path / "broken"
    shutil.copytree(artifacts, broken)
    meta = broken / BASELINE_MODEL / "v1" / "metadata.json"
    payload = json.loads(meta.read_text())
    payload.pop("model_kind")  # the [184] clause
    meta.write_text(json.dumps(payload, indent=2))

    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    monkeypatch.setenv("BULLPEN_ADMIN_USER", "u")
    monkeypatch.setenv("BULLPEN_ADMIN_PASSWORD", "p")
    result = _run(broken, "--register")

    assert result.exit_code != 0
    assert _RecordingClient.instances[0].registered == []


def test_a_remote_register_without_server_artifacts_dir_is_refused(artifacts: Path) -> None:
    """THE PATH TRAP. The registry resolves the payload's three paths on ITS filesystem, so a
    remote --register with local paths 422s naming a path only this machine has. Failing here
    beats an error that points at the wrong thing."""
    result = _run(artifacts, "--register", "--base-url", "https://box.internal:8080")
    assert result.exit_code != 0
    assert "server-artifacts-dir" in result.output


def test_payload_paths_are_the_servers_when_registering_remotely(
    artifacts: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The gate reads the LOCAL bundle; the payload must describe the SERVER's."""
    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    monkeypatch.setenv("BULLPEN_ADMIN_USER", "u")
    monkeypatch.setenv("BULLPEN_ADMIN_PASSWORD", "p")
    result = _run(
        artifacts,
        "--register",
        "--base-url",
        "https://box.internal:8080",
        "--server-artifacts-dir",
        "/opt/bullpen/artifacts",
    )
    assert result.exit_code == 0, result.output
    first = _RecordingClient.instances[0].registered[0]
    assert first["artifact_path"] == f"/opt/bullpen/artifacts/{BASELINE_MODEL}/v1/model.onnx"
    assert first["metadata_path"] == f"/opt/bullpen/artifacts/{BASELINE_MODEL}/v1/metadata.json"
    assert str(artifacts) not in first["artifact_path"]


def test_payload_fields_come_from_the_bundles_metadata(
    artifacts: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    monkeypatch.setenv("BULLPEN_ADMIN_USER", "u")
    monkeypatch.setenv("BULLPEN_ADMIN_PASSWORD", "p")
    _run(artifacts, "--register", "--models", "baseline")
    sent = _RecordingClient.instances[0].registered[0]
    meta = json.loads((artifacts / BASELINE_MODEL / "v1" / "metadata.json").read_text())
    assert sent["training_data_hash"] == meta["training_data_hash"]
    assert sent["training_data_window"] == meta["training_data_window"]
    assert json.loads(sent["eval_metrics_json"]) == meta["eval_metrics_summary"]
    assert sent["trained_at"] == meta["trained_at"]


def test_registering_the_primary_alone_asks_the_registry_about_the_baseline(
    artifacts: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Rule 9's flag must reflect a real row, not "the gate passed on my laptop". With the fake
    returning no versions, the primary-only run is refused."""
    monkeypatch.setattr(
        "bullpen_training.pitch_type.register_pitch_type.BullpenAdminClient", _RecordingClient
    )
    monkeypatch.setenv("BULLPEN_ADMIN_USER", "u")
    monkeypatch.setenv("BULLPEN_ADMIN_PASSWORD", "p")
    result = _run(artifacts, "--register", "--models", "primary")
    assert _RecordingClient.instances[0].listed == [BASELINE_MODEL]
    assert result.exit_code != 0  # gate refuses: rule 9 partner not registered
    assert _RecordingClient.instances[0].registered == []
