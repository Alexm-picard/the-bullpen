"""Single-park MLP architecture for the per-park comparison experiment.

Same 2-layer backbone as the multi-output MLP (Phase 2c.5) but with a
single output head producing (B, 5) logits for 5 outcomes at one park.
30 of these are trained independently, one per MLB park.
"""

from __future__ import annotations

import torch
from torch import nn


class PerParkMLP(nn.Module):
    """Single-park MLP: shared-backbone topology with one output head.

    Returns raw logits with shape ``(B, n_outcomes)``.
    """

    def __init__(
        self,
        *,
        n_features: int = 15,
        n_outcomes: int = 5,
        hidden: int = 128,
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.n_features = n_features
        self.n_outcomes = n_outcomes
        self.backbone = nn.Sequential(
            nn.Linear(n_features, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
        )
        self.head = nn.Linear(hidden, n_outcomes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.head(self.backbone(x))


def build_per_park_model(
    *,
    n_features: int = 15,
    n_outcomes: int = 5,
    hidden: int = 128,
    dropout: float = 0.1,
    seed: int = 42,
) -> PerParkMLP:
    torch.manual_seed(seed)
    return PerParkMLP(
        n_features=n_features,
        n_outcomes=n_outcomes,
        hidden=hidden,
        dropout=dropout,
    )


__all__ = ("PerParkMLP", "build_per_park_model")
