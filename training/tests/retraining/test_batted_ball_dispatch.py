"""Integration test for the REAL batted_ball dispatch entry (M1 task 3).

Replaces the mock-callable proof with the real thing: ``run_once`` claims a trigger whose
``model_name`` hits the real adapter, which drives the ACTUAL ``train_all_parks`` loop
(scaler fit -> train -> ONNX export -> metadata) on a synthetic miniature, and the produced
``RetrainOutput`` flows through register -> mark_complete. Only the ClickHouse loader and
the HTTP admin client (hard external boundaries) are faked.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest

from bullpen_training.retraining._api_client import ClaimedTrigger
from bullpen_training.retraining.run import run_once
from tests.retraining.test_run import FakeAdminClient

N_FEATURES = 15
N_OUTCOMES = 5


def _fake_loader(
    *, park_id: str, season_from: int, season_to: int, limit: int | None = None
) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(sum(ord(c) for c in park_id))
    feat = rng.standard_normal((48, N_FEATURES)).astype(np.float32)
    logits = rng.standard_normal((48, N_OUTCOMES)).astype(np.float32)
    probs = np.exp(logits - logits.max(axis=-1, keepdims=True))
    probs /= probs.sum(axis=-1, keepdims=True)
    return feat, probs.astype(np.float32)


def _claim(trigger_id: str, metadata: dict) -> ClaimedTrigger:
    return ClaimedTrigger(
        id=1,
        trigger_id=trigger_id,
        model_name="batted_ball",
        trigger_type="DRIFT",
        trigger_metadata=metadata,
        status="RUNNING",
    )


def test_real_batted_ball_dispatch_end_to_end(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(
        "bullpen_training.battedball.mlp_per_park.train.load_park_arrays", _fake_loader
    )
    monkeypatch.setenv("BULLPEN_RETRAIN_ARTIFACT_DIR", str(tmp_path))
    client = FakeAdminClient(
        next_claim=_claim(
            "drift-2026-07-02-batted_ball",
            {"park_ids": ["BOS", "NYY"], "n_epochs": 1},
        ),
        next_version_value="v9",
        register_returns=321,
    )

    exit_code = run_once(client)

    assert exit_code == 0
    # Register got the REAL adapter's output, not a mock.
    reg = client.register_calls[0]
    assert reg["model_name"] == "batted_ball"
    assert reg["version"] == "v9"
    assert reg["training_data_window"] == "[2015,2025]"
    assert len(reg["training_data_hash"]) == 64  # sha256 provenance token
    eval_metrics = json.loads(reg["eval_metrics_json"])
    assert eval_metrics["kind"] == "training_diagnostics"
    assert eval_metrics["n_parks_trained"] == 2

    # The artifacts really exist on disk where the RetrainOutput points.
    out_dir = tmp_path / "batted_ball" / "v9"
    assert reg["artifact_path"] == str(out_dir)
    for park in ("BOS", "NYY"):
        assert (out_dir / park / "model.onnx").is_file()
        assert (out_dir / park / "model.pt").is_file()

    # The _dispatch contract: trigger_id is persisted into the produced metadata.json.
    top_meta = json.loads((out_dir / "metadata.json").read_text())
    assert top_meta["trigger_id"] == "drift-2026-07-02-batted_ball"
    assert top_meta["model_version"] == "v9"
    assert top_meta["training_seasons"] == [2015, 2025]

    # And the queue row completed as succeeded with the registered version id.
    assert client.complete_calls == [
        {
            "trigger_id": "drift-2026-07-02-batted_ball",
            "succeeded": True,
            "produced_version_id": 321,
            "error_message": None,
        }
    ]


@pytest.mark.parametrize(
    "override",
    [{"season_to": 2026}, {"season_from": 2026}, {"val_season": 2026}],
    ids=("season_to", "season_from", "val_season"),
)
def test_dispatch_holdout_override_is_fenced_and_fails_the_trigger(
    override: dict, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A trigger_metadata season override cannot smuggle 2026 past the [170] fence on ANY of
    the three fenced params: the LeakageError propagates as a training failure, the row is
    marked FAILED, exit code 1."""
    monkeypatch.setattr(
        "bullpen_training.battedball.mlp_per_park.train.load_park_arrays", _fake_loader
    )
    monkeypatch.setenv("BULLPEN_RETRAIN_ARTIFACT_DIR", str(tmp_path))
    client = FakeAdminClient(
        next_claim=_claim(
            "drift-2026-07-02-batted_ball",
            {"park_ids": ["BOS"], "n_epochs": 1, **override},
        ),
        next_version_value="v9",
    )

    exit_code = run_once(client)

    assert exit_code == 1
    assert client.register_calls == []
    assert len(client.complete_calls) == 1
    complete = client.complete_calls[0]
    assert complete["succeeded"] is False
    assert "rule 13" in complete["error_message"]
