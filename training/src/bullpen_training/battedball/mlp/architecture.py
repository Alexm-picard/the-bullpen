"""Multi-output MLP architecture (Phase 2c.5).

Shared 2-layer backbone (Dense(hidden) -> ReLU -> Dense(hidden) -> ReLU)
followed by N parallel output heads, each Dense(hidden -> n_outcomes).
The forward pass returns a ``(batch, n_parks, n_outcomes)`` tensor of
RAW LOGITS — softmax is applied at the loss step (in :mod:`train`) so
the loss can use ``F.log_softmax`` directly and so the ONNX export
preserves the multi-head topology without a baked-in softmax (the
Java-side inference layer in 2c.7 will softmax per head).

Why 30 separate heads vs one head with park-id as a feature:
  - Decision [50] (park geometry NOT a model feature) — the park
    behaves as the *label space*, not as a covariate. Heads let each
    park learn its own outcome distribution given the same batted-ball
    feature vector.
  - Each head is tiny (Linear(64, 5) = 325 params), so 30 heads add
    ~10K params vs a 1-head model — the backbone (4096 params) is the
    dominant cost. Total ~14-20K params at the default hidden=64.

Topology summary (defaults):
    n_features=15 → Dense(128) → ReLU → Dropout(0.1)
                  → Dense(128) → ReLU → Dropout(0.1)
                  ↓
       (30 parallel Dense(5)) → stack → (B, 30, 5)
"""

from __future__ import annotations

import torch
from torch import nn


class BattedBallMLP(nn.Module):
    """Shared-backbone, multi-head MLP for per-park batted-ball outcomes.

    Returns raw logits with shape ``(B, n_parks, n_outcomes)``. Callers
    apply softmax along the last axis to get per-park outcome
    probability distributions.
    """

    def __init__(
        self,
        *,
        n_features: int = 15,
        n_parks: int = 30,
        n_outcomes: int = 5,
        hidden: int = 128,
        dropout: float = 0.1,
    ) -> None:
        super().__init__()
        self.n_features = n_features
        self.n_parks = n_parks
        self.n_outcomes = n_outcomes
        self.hidden = hidden

        self.backbone = nn.Sequential(
            nn.Linear(n_features, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(hidden, hidden),
            nn.ReLU(),
            nn.Dropout(dropout),
        )
        # nn.ModuleList preserves the head ordering on save/load and on
        # ONNX export; using a Python list would lose the registration.
        self.heads = nn.ModuleList([nn.Linear(hidden, n_outcomes) for _ in range(n_parks)])

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = self.backbone(x)
        # Stack along park axis to get (B, n_parks, n_outcomes). The
        # loop is fine: 30 small Linears is well under any GPU overhead.
        per_park = [head(h) for head in self.heads]
        return torch.stack(per_park, dim=1)


def build_model(
    *,
    n_features: int = 15,
    n_parks: int = 30,
    n_outcomes: int = 5,
    hidden: int = 128,
    dropout: float = 0.1,
    seed: int = 42,
) -> BattedBallMLP:
    """Construct a :class:`BattedBallMLP` with deterministic init.

    Seeds the global Torch RNG before constructing the model so the
    layer initialisations are identical across runs — important for
    fold-level reproducibility under the rolling-origin CV harness.
    """
    torch.manual_seed(seed)
    return BattedBallMLP(
        n_features=n_features,
        n_parks=n_parks,
        n_outcomes=n_outcomes,
        hidden=hidden,
        dropout=dropout,
    )


__all__ = ("BattedBallMLP", "build_model")
