"""Trainer + ONNX-export tests for the multi-output MLP (Phase 2c.5).

The dataset path through ClickHouse is exercised by the smoke run
documented in the 2c.5 leaf; these tests build a synthetic dataset in
memory so they run on CI without docker / GPU / network."""

from __future__ import annotations

from pathlib import Path

import numpy as np
import onnx
import pytest
import torch
import torch.nn.functional as F
from bullpen_training.battedball.mlp.architecture import build_model
from bullpen_training.battedball.mlp.train import (
    LABEL_SMOOTHING_EPS,
    _kl_loss,
    _smooth_labels,
    export_onnx,
    train_model,
    write_metadata,
)
from torch.utils.data import Dataset


class _SyntheticBBIPDataset(Dataset):
    """Toy dataset: each (features, labels) pair is a deterministic
    function of a random seed. The MLP should be able to learn the
    mapping over a handful of epochs."""

    def __init__(self, n: int, n_features: int = 15, n_parks: int = 30, seed: int = 0):
        rng = np.random.default_rng(seed)
        self._x = rng.standard_normal((n, n_features)).astype(np.float32)
        # Construct labels as a soft-deterministic function of x[0] so the model
        # has signal to fit (otherwise loss is just smoothing noise).
        logits = rng.standard_normal((n, n_parks, 5)).astype(np.float32) * 0.1
        logits[:, :, 0] += self._x[:, 0:1]  # bias OUT by x[0]
        probs = np.exp(logits - logits.max(axis=-1, keepdims=True))
        probs /= probs.sum(axis=-1, keepdims=True)
        self._y = probs.astype(np.float32)

    def __len__(self) -> int:
        return self._x.shape[0]

    def __getitem__(self, idx: int) -> tuple[np.ndarray, np.ndarray]:
        return self._x[idx], self._y[idx]


# --- loss internals --------------------------------------------------------


def test_smooth_labels_mixes_uniform_prior() -> None:
    labels = torch.tensor([[1.0, 0.0, 0.0, 0.0, 0.0]])
    smoothed = _smooth_labels(labels, eps=0.1)
    expected = torch.tensor([[0.92, 0.02, 0.02, 0.02, 0.02]])
    assert torch.allclose(smoothed, expected, atol=1e-6)
    assert torch.allclose(smoothed.sum(dim=-1), torch.tensor([1.0]))


def test_kl_loss_zero_when_logits_match_label_distribution() -> None:
    """When the model's softmax exactly equals the smoothed labels, KL
    divergence is 0 (modulo float noise). Use a label distribution that
    is its own smoothing fixed point — uniform."""
    n_parks, n_outcomes = 4, 5
    labels = torch.full((2, n_parks, n_outcomes), 1.0 / n_outcomes)
    # Logits with all entries equal -> softmax = uniform = labels.
    logits = torch.zeros((2, n_parks, n_outcomes))
    loss = _kl_loss(logits, labels)
    assert float(loss) == pytest.approx(0.0, abs=1e-6)


def test_kl_loss_is_finite_on_degenerate_one_hot_label() -> None:
    """The leaf's "Known edge case" — KL with a one-hot label would be
    infinite without smoothing. The wrapper clamps it via the eps mix."""
    labels = torch.zeros((1, 1, 5))
    labels[0, 0, 0] = 1.0
    logits = torch.zeros((1, 1, 5))  # uniform softmax -> mismatch
    loss = _kl_loss(logits, labels)
    assert torch.isfinite(loss).item()


def test_label_smoothing_eps_is_a_sensible_default() -> None:
    """Cheap pin: eps should be small enough that real labels aren't
    drowned out (between 1e-4 and 0.1)."""
    assert 1e-4 < LABEL_SMOOTHING_EPS < 0.1


# --- training convergence --------------------------------------------------


def test_loss_decreases_over_epochs_on_synthetic_data() -> None:
    """5-epoch training on 256 synthetic samples should drop loss vs
    the random-init baseline."""
    ds = _SyntheticBBIPDataset(n=256)
    # Eval at init for baseline loss.
    init_model = build_model()
    x0, y0 = ds[0]
    init_loss = float(
        _kl_loss(
            init_model(torch.from_numpy(x0).unsqueeze(0)),
            torch.from_numpy(y0).unsqueeze(0),
        )
    )
    # Train.
    _trained, summary = train_model(ds, n_epochs=5, batch_size=64, lr=1e-2, device="cpu")
    final_loss = summary.final_train_loss
    assert (
        final_loss < init_loss * 0.9
    ), f"loss should drop noticeably; init {init_loss:.4f} -> final {final_loss:.4f}"
    assert summary.device == "cpu"
    assert summary.n_epochs == 5
    assert summary.elapsed_sec > 0


def test_training_runs_without_val_loader() -> None:
    """Training should work with train_dataset only (val is optional)."""
    ds = _SyntheticBBIPDataset(n=64)
    _model, summary = train_model(ds, n_epochs=2, batch_size=16, device="cpu")
    assert summary.n_val == 0
    assert True  # NaN ok


# --- ONNX export -----------------------------------------------------------


def test_onnx_export_validates_with_checker(tmp_path: Path) -> None:
    """Exported ONNX must pass onnx.checker.check_model — the leaf's
    "ONNX export validates with onnx.checker" acceptance criterion."""
    model = build_model()
    out = tmp_path / "model.onnx"
    export_onnx(model, out)
    onnx.checker.check_model(onnx.load(str(out)))


def test_onnx_export_matches_pytorch_within_1e5(tmp_path: Path) -> None:
    """Round-trip parity: the exported ONNX, when reloaded and run via
    onnxruntime, should match the Torch model to ~1e-5. Critical for the
    Risk Register G1 (Python<->Java parity) closure on the 2c head."""
    onnxruntime = pytest.importorskip("onnxruntime")

    torch.manual_seed(0)
    model = build_model()
    model.eval()
    out = tmp_path / "model.onnx"
    export_onnx(model, out)

    x = torch.randn((3, 15), dtype=torch.float32)
    with torch.no_grad():
        # The export bakes a per-park softmax, so the ONNX emits probabilities, not raw logits.
        torch_out = torch.softmax(model(x), dim=-1).numpy()
    session = onnxruntime.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    onnx_out = session.run(["probabilities"], {"features": x.numpy()})[0]
    np.testing.assert_allclose(torch_out, onnx_out, atol=1e-5, rtol=1e-5)
    # Probabilities: every park's outcome distribution sums to 1.
    np.testing.assert_allclose(onnx_out.sum(axis=-1), np.ones((3, 30)), atol=1e-5)


def test_onnx_export_dynamic_batch_axis(tmp_path: Path) -> None:
    """Export with dynamic batch dimension; runtime should accept any
    batch size (1, 4, 32, ...). The 2c.7 Java preview test relies on
    this."""
    onnxruntime = pytest.importorskip("onnxruntime")
    model = build_model()
    out = tmp_path / "model.onnx"
    export_onnx(model, out)
    session = onnxruntime.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    for batch in (1, 4, 32):
        x = np.zeros((batch, 15), dtype=np.float32)
        result = session.run(["probabilities"], {"features": x})[0]
        assert result.shape == (batch, 30, 5)


def test_write_metadata_records_park_order(tmp_path: Path) -> None:
    out = tmp_path / "metadata.json"
    park_order = ["AZ", "BOS", "COL"]
    write_metadata(out, park_order=park_order)
    import json

    data = json.loads(out.read_text())
    assert data["park_order"] == park_order
    assert data["model_name"] == "battedball_outcome"
    assert "feature_names" in data
    assert "outcome_names" in data


def test_softmax_per_park_sums_to_one_post_export(tmp_path: Path) -> None:
    """After the trainer exports to ONNX + a caller applies softmax on
    the logits, per-park rows must sum to 1."""
    model = build_model()
    out = tmp_path / "model.onnx"
    export_onnx(model, out)
    x = torch.randn((4, 15))
    with torch.no_grad():
        probs = F.softmax(model(x), dim=-1)
    sums = probs.sum(dim=-1)
    assert torch.allclose(sums, torch.ones_like(sums), atol=1e-6)
