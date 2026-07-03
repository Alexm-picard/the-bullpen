"""Integration test for the SERVABLE-family ``battedball_outcome`` dispatch entry (M2-A3).

Mirrors ``test_batted_ball_dispatch.py`` for the SERVED family: ``run_once`` claims a
trigger whose ``model_name`` is the registry name ``battedball_outcome``, which drives the
ACTUAL production path (FeatureScaler.fit -> train_model on the shared-backbone
BattedBallMLP incl. the carry head -> export_onnx single-file serving graph ->
write_metadata -> per-park isotonic fit on the held-out val season) on a synthetic
miniature, and the produced ``RetrainOutput`` flows through register -> mark_complete.
Only the ClickHouse loader and the HTTP admin client (hard external boundaries) are faked.

Carry fidelity: the synthetic loader emits FINITE feet-scale carry targets for ~75% of
(BIP, park) cells and NaN for the rest - exercising the PRODUCTION v2-champion path (the
masked smooth-L1 carry loss gets a real gradient) AND the NaN mask path (un-backfilled
rows), rather than the degenerate all-NaN outcome-only fallback.
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
N_PARKS = 30  # the served family is one graph over all 30 parks (no subsetting)


def _fake_load_arrays(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Synthetic stand-in for ``mlp.dataset.load_arrays`` (the ClickHouse boundary).

    Deterministic per season window so the train slice and the val slice differ. Shapes
    match the real loader: features (N, 15), labels (N, n_parks, 5) row-normalised,
    carry (N, n_parks) feet with NaN holes.
    """
    rng = np.random.default_rng(10_000 * season_from + season_to)
    n = 48
    if limit is not None:
        n = min(n, limit)
    feat = rng.standard_normal((n, N_FEATURES)).astype(np.float32)
    logits = rng.standard_normal((n, len(park_order), N_OUTCOMES)).astype(np.float32)
    probs = np.exp(logits - logits.max(axis=-1, keepdims=True))
    probs /= probs.sum(axis=-1, keepdims=True)
    carry = rng.normal(225.0, 80.0, (n, len(park_order))).astype(np.float32)
    carry[rng.random((n, len(park_order))) < 0.25] = np.nan
    return feat, probs.astype(np.float32), carry


def _claim(trigger_id: str, metadata: dict) -> ClaimedTrigger:
    # Real queue rows carry champ.modelName() (M2 ruling C1) - the registry name reaches
    # the servable-family adapter directly.
    return ClaimedTrigger(
        id=1,
        trigger_id=trigger_id,
        model_name="battedball_outcome",
        trigger_type="DRIFT",
        trigger_metadata=metadata,
        status="RUNNING",
    )


def test_real_battedball_outcome_dispatch_end_to_end(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    monkeypatch.setattr(
        "bullpen_training.retraining.battedball_outcome.load_arrays", _fake_load_arrays
    )
    monkeypatch.setenv("BULLPEN_RETRAIN_ARTIFACT_DIR", str(tmp_path))
    client = FakeAdminClient(
        next_claim=_claim(
            "drift-2026-07-02-battedball_outcome",
            {"n_epochs": 1, "device": "cpu"},
        ),
        next_version_value="v9",
        register_returns=654,
    )

    exit_code = run_once(client)

    assert exit_code == 0
    # Register got the REAL adapter's output under the registry model name.
    reg = client.register_calls[0]
    assert reg["model_name"] == "battedball_outcome"
    assert reg["version"] == "v9"
    assert reg["training_data_window"] == "[2015,2025]"
    assert len(reg["training_data_hash"]) == 64  # sha256 provenance token
    eval_metrics = json.loads(reg["eval_metrics_json"])
    assert eval_metrics["kind"] == "training_diagnostics"
    assert eval_metrics["calibration"]["val_season"] == 2025
    assert eval_metrics["calibration"]["n_parks"] == N_PARKS

    # SINGLE-FILE family: artifact_path is the model.onnx FILE (the registry copy-list
    # stages calibrator.json from its parent dir), and the artifact dir carries the exact
    # file set LoadedAllParksModel.load expects by their served names.
    out_dir = tmp_path / "battedball_outcome" / "v9"
    assert reg["artifact_path"] == str(out_dir / "model.onnx")
    assert reg["metadata_path"] == str(out_dir / "metadata.json")
    assert (out_dir / "model.onnx").is_file()  # SnapshotStorage.ARTIFACT_FILE
    assert (out_dir / "metadata.json").is_file()  # SnapshotStorage.METADATA_FILE
    assert (out_dir / "calibrator.json").is_file()  # SnapshotStorage.CALIBRATOR_FILE
    assert (out_dir / "model.pt").is_file()  # checkpoint (provenance, not served)
    # export_onnx must have inlined + removed the dynamo external-data sidecar - a
    # stray .data file would silently break the single-file snapshot on the box.
    assert not (out_dir / "model.onnx.data").exists()

    # metadata.json: the _dispatch trigger_id contract + the serving-required blocks
    # (feature_scaler / park_order / carry_target - FeaturePipelineBattedBall reads them).
    top_meta = json.loads((out_dir / "metadata.json").read_text())
    assert top_meta["trigger_id"] == "drift-2026-07-02-battedball_outcome"
    assert top_meta["model_version"] == "v9"
    assert top_meta["training_seasons"] == [2015, 2025]
    assert top_meta["train_split_seasons"] == [2015, 2024]
    assert top_meta["calibration_val_season"] == 2025
    assert len(top_meta["park_order"]) == N_PARKS
    assert len(top_meta["feature_scaler"]["means"]) == N_FEATURES
    assert top_meta["carry_target"]["units"] == "feet"

    # calibrator.json: the schema_version-2 park-keyed map BattedBallCalibrators.load
    # consumes, one entry per park, 5 per-outcome cells each.
    calibrator = json.loads((out_dir / "calibrator.json").read_text())
    assert calibrator["schema_version"] == 2
    assert calibrator["park_order"] == top_meta["park_order"]
    assert set(calibrator["parks"].keys()) == set(top_meta["park_order"])
    assert all(len(cells) == N_OUTCOMES for cells in calibrator["parks"].values())

    # And the queue row completed as succeeded with the registered version id.
    assert client.complete_calls == [
        {
            "trigger_id": "drift-2026-07-02-battedball_outcome",
            "succeeded": True,
            "produced_version_id": 654,
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
    """A trigger_metadata season override cannot smuggle 2026 past the rule-13/[170] fence
    on ANY of the three fenced params: the LeakageError propagates as a training failure,
    the row is marked FAILED, exit code 1."""
    monkeypatch.setattr(
        "bullpen_training.retraining.battedball_outcome.load_arrays", _fake_load_arrays
    )
    monkeypatch.setenv("BULLPEN_RETRAIN_ARTIFACT_DIR", str(tmp_path))
    client = FakeAdminClient(
        next_claim=_claim(
            "drift-2026-07-02-battedball_outcome",
            {"n_epochs": 1, "device": "cpu", **override},
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
