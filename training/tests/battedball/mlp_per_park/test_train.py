"""Trainer + ONNX-export smoke tests for the per-park MLP (the SERVING battedball model).

Mirrors ``tests/battedball/mlp/test_train.py``: a synthetic in-memory dataset so the tests
run on CI without docker / GPU / network. ``load_park_arrays`` (the ClickHouse loader) is
monkeypatched at the trainer's namespace, so ``train_all_parks`` runs its REAL loop:
scaler fit -> train -> torch.save -> export_per_park_onnx (onnx.checker inside) ->
per-park + top-level metadata.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import onnxruntime as ort
import pytest
import torch

from bullpen_training.battedball.features_shared import FEATURE_NAMES, OUTCOME_NAMES
from bullpen_training.battedball.mlp.dataset import FeatureScaler
from bullpen_training.battedball.mlp_per_park.dataset import PerParkDataset
from bullpen_training.battedball.mlp_per_park.train import (
    export_per_park_onnx,
    train_all_parks,
    train_single_park,
)

N_FEATURES = len(FEATURE_NAMES)
N_OUTCOMES = len(OUTCOME_NAMES)


def _synthetic_arrays(n: int, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """Features + soft labels with learnable signal (labels lean on x[0])."""
    rng = np.random.default_rng(seed)
    feat = rng.standard_normal((n, N_FEATURES)).astype(np.float32)
    logits = rng.standard_normal((n, N_OUTCOMES)).astype(np.float32) * 0.1
    logits[:, 0] += feat[:, 0]
    probs = np.exp(logits - logits.max(axis=-1, keepdims=True))
    probs /= probs.sum(axis=-1, keepdims=True)
    return feat, probs.astype(np.float32)


def test_export_matches_torch_forward(tmp_path: Path) -> None:
    """ONNX graph parity: the exported model reproduces the torch forward pass."""
    feat, lab = _synthetic_arrays(64, seed=1)
    scaler = FeatureScaler.fit(feat)
    ds = PerParkDataset(feat, lab, scaler=scaler)
    model, summary, _dev = train_single_park(
        ds, None, n_epochs=2, batch_size=32, lr=1e-3, seed=42, device="cpu"
    )
    assert summary.n_train == 64

    out_path = tmp_path / "model.onnx"
    export_per_park_onnx(model, out_path)  # onnx.checker runs inside

    probe = scaler.transform(feat[:8])
    model.cpu().eval()
    with torch.no_grad():
        torch_logits = model(torch.from_numpy(probe)).numpy()
    sess = ort.InferenceSession(str(out_path), providers=["CPUExecutionProvider"])
    (ort_logits,) = sess.run(["logits"], {"features": probe})

    assert ort_logits.shape == (8, N_OUTCOMES)
    np.testing.assert_allclose(ort_logits, torch_logits, rtol=1e-4, atol=1e-5)


def test_train_all_parks_writes_full_artifact_set(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The real train_all_parks loop end-to-end on two synthetic parks."""

    def fake_loader(
        *, park_id: str, season_from: int, season_to: int, limit: int | None = None
    ) -> tuple[np.ndarray, np.ndarray]:
        # Deterministic per-park seed (str hash() is randomized per process).
        return _synthetic_arrays(48, seed=sum(ord(c) for c in park_id))

    monkeypatch.setattr(
        "bullpen_training.battedball.mlp_per_park.train.load_park_arrays", fake_loader
    )

    summaries = train_all_parks(
        park_ids=("BOS", "NYY"),
        season_from=2024,
        season_to=2024,
        n_epochs=1,
        batch_size=32,
        lr=1e-3,
        seed=42,
        device="cpu",
        out_dir=tmp_path,
    )

    assert [s.park_id for s in summaries] == ["BOS", "NYY"]
    for park in ("BOS", "NYY"):
        park_dir = tmp_path / park
        assert (park_dir / "model.pt").is_file()
        assert (park_dir / "model.onnx").is_file()
        metadata = (park_dir / "metadata.json").read_text()
        assert '"feature_scaler"' in metadata
        assert '"park_id"' in metadata
        # The exported graph loads under ORT and produces one row of outcome logits.
        sess = ort.InferenceSession(
            str(park_dir / "model.onnx"), providers=["CPUExecutionProvider"]
        )
        (logits,) = sess.run(["logits"], {"features": np.zeros((1, N_FEATURES), dtype=np.float32)})
        assert logits.shape == (1, N_OUTCOMES)
        assert np.isfinite(logits).all()
    top_meta = (tmp_path / "metadata.json").read_text()
    assert '"n_parks_trained": 2' in top_meta


def test_train_all_parks_skips_a_park_with_no_data(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def fake_loader(
        *, park_id: str, season_from: int, season_to: int, limit: int | None = None
    ) -> tuple[np.ndarray, np.ndarray]:
        if park_id == "SEA":
            return (
                np.zeros((0, N_FEATURES), dtype=np.float32),
                np.zeros((0, N_OUTCOMES), dtype=np.float32),
            )
        return _synthetic_arrays(48, seed=7)

    monkeypatch.setattr(
        "bullpen_training.battedball.mlp_per_park.train.load_park_arrays", fake_loader
    )

    summaries = train_all_parks(
        park_ids=("SEA", "BOS"),
        season_from=2024,
        season_to=2024,
        n_epochs=1,
        batch_size=32,
        lr=1e-3,
        seed=42,
        device="cpu",
        out_dir=tmp_path,
    )

    assert [s.park_id for s in summaries] == ["BOS"]
    assert not (tmp_path / "SEA").exists()
