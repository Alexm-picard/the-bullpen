"""Architecture tests for the multi-output MLP (Phase 2c.5)."""

from __future__ import annotations

import pytest
import torch
import torch.nn.functional as F

from bullpen_training.battedball.mlp.architecture import BattedBallMLP, build_model


def test_forward_shape_matches_spec() -> None:
    """Forward pass on (B=8, 15) -> logits (8, 30, 5) + carry (8, 30, 1)."""
    model = build_model()
    x = torch.zeros((8, 15), dtype=torch.float32)
    logits, carry = model(x)
    assert logits.shape == (8, 30, 5)
    assert carry.shape == (8, 30, 1)


def test_forward_returns_raw_logits_not_probabilities() -> None:
    """The model returns RAW logits — softmax happens in the loss step
    so ONNX export keeps the multi-head topology without a baked-in
    softmax. Sanity: logits should NOT already sum to 1 per park."""
    model = build_model()
    x = torch.randn((4, 15), dtype=torch.float32)
    logits, _carry = model(x)
    sums = logits.sum(dim=-1)
    assert (sums.abs() > 0.01).any(), "logits should not be already-softmaxed"


def test_carry_head_is_unconstrained_real_valued() -> None:
    """The carry head emits one raw scalar (feet) per park — no softmax, no
    activation. Just pin the shape and that it is finite real-valued."""
    model = build_model()
    x = torch.randn((4, 15), dtype=torch.float32)
    _logits, carry = model(x)
    assert carry.shape == (4, 30, 1)
    assert torch.isfinite(carry).all()


def test_softmax_per_park_sums_to_one() -> None:
    """After softmax along last axis, each (sample, park) row sums to 1."""
    model = build_model()
    x = torch.randn((4, 15), dtype=torch.float32)
    logits, _carry = model(x)
    probs = F.softmax(logits, dim=-1)
    sums = probs.sum(dim=-1)
    assert torch.allclose(sums, torch.ones_like(sums), atol=1e-6)


def test_build_model_is_deterministic_under_fixed_seed() -> None:
    a = build_model(seed=123)
    b = build_model(seed=123)
    # Compare a parameter that depends on the seeded RNG.
    p_a = next(a.parameters())
    p_b = next(b.parameters())
    assert torch.equal(p_a, p_b)


def test_build_model_different_seed_yields_different_init() -> None:
    a = build_model(seed=1)
    b = build_model(seed=2)
    p_a = next(a.parameters())
    p_b = next(b.parameters())
    assert not torch.equal(p_a, p_b)


def test_param_count_is_in_expected_range() -> None:
    """The leaf headline figure is ~50K params; at hidden=64 / 30 heads
    we land near 15K (backbone 4K + 30 heads * 325 = ~14K). Pin the
    order of magnitude so accidental head-size blowups trip a test."""
    model = build_model()
    n = sum(p.numel() for p in model.parameters())
    assert 10_000 < n < 60_000, f"unexpected param count {n}"


@pytest.mark.parametrize("n_parks", [1, 5, 30])
def test_forward_scales_with_n_parks(n_parks: int) -> None:
    model = build_model(n_parks=n_parks)
    logits, carry = model(torch.zeros((2, 15)))
    assert logits.shape == (2, n_parks, 5)
    assert carry.shape == (2, n_parks, 1)


def test_n_features_dim_change_propagates() -> None:
    model = BattedBallMLP(n_features=8, n_parks=4, n_outcomes=3, hidden=16)
    logits, carry = model(torch.zeros((3, 8)))
    assert logits.shape == (3, 4, 3)
    assert carry.shape == (3, 4, 1)
