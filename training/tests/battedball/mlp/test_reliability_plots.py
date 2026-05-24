"""Smoke tests for the reliability-diagram plotter (Phase 2c.6)."""

from __future__ import annotations

from pathlib import Path

import numpy as np

from bullpen_training.battedball.mlp.calibration import fit_per_park_calibrators
from bullpen_training.battedball.mlp.reliability_plots import (
    plot_all_parks,
    plot_park_reliability,
)

_OUTCOMES = ("out", "1b", "2b", "3b", "hr")


def _synthetic(n: int = 300, n_parks: int = 4, seed: int = 7):
    rng = np.random.default_rng(seed)
    raw = rng.dirichlet(np.ones(5), size=(n, n_parks)).astype(np.float32)
    labels = np.clip(raw + rng.normal(0.0, 0.04, raw.shape).astype(np.float32), 1e-6, None)
    labels /= labels.sum(axis=-1, keepdims=True)
    parks = tuple(f"P{i}" for i in range(n_parks))
    return raw, labels, parks


def test_plot_park_reliability_writes_a_png(tmp_path: Path) -> None:
    raw, labels, _ = _synthetic(n=300, n_parks=1)
    out = tmp_path / "park.png"
    plot_park_reliability(
        park_id="TEST",
        raw_probs=raw[:, 0, :],
        calibrated_probs=raw[:, 0, :],
        label_distributions=labels[:, 0, :],
        outcome_order=_OUTCOMES,
        out_path=out,
    )
    assert out.exists()
    assert out.stat().st_size > 1000  # not a stub PNG


def test_plot_all_parks_writes_one_png_per_park(tmp_path: Path) -> None:
    raw, labels, parks = _synthetic(n=300, n_parks=4)
    cals = fit_per_park_calibrators(raw, labels, park_order=parks, outcome_order=_OUTCOMES)
    written = plot_all_parks(raw, labels, cals, out_dir=tmp_path)
    assert len(written) == 4
    for p in written:
        assert p.exists()
        assert p.suffix == ".png"
