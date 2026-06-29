"""Multi-output MLP architecture (Phase 2c.5; Phase 4 carry head).

Shared 2-layer backbone (Dense(hidden) -> ReLU -> Dense(hidden) -> ReLU)
followed by two parallel banks of N per-park heads:
  - ``heads``: each Dense(hidden -> n_outcomes), the per-park OUTCOME logits.
  - ``dist_heads``: each Dense(hidden -> 1), the per-park CARRY distance as a
    STANDARDISED scalar (Phase 4). The training loss standardises the feet
    target (centre ~0, unit scale) so the head converges from a zero-init bias;
    serving un-standardises back to feet (ft = raw*std + mean), see :mod:`train`.

The forward pass returns ``(logits, carry)``:
  - ``logits``: ``(batch, n_parks, n_outcomes)`` RAW outcome logits - softmax
    is applied at the loss step (in :mod:`train`) so the loss can use
    ``F.log_softmax`` directly.
  - ``carry``: ``(batch, n_parks, 1)`` standardised per-park carry (serving
    un-standardises to feet).

The SERVING ONNX export wraps this module (``train._ProbaExport``): the
outcome head gets a per-park softmax baked in so the exported graph emits
per-park probabilities (the Java serving layer calibrates them directly with
no Java-side softmax, matching the LGBM/LR exports); the carry head passes
through standardised. (PR-3 keeps the export probabilities-only; the second
carry output lands in PR-4 alongside the contract change.)

Why 30 separate heads vs one head with park-id as a feature:
  - Decision [50] (park geometry NOT a model feature) — the park
    behaves as the *label space*, not as a covariate. Heads let each
    park learn its own outcome distribution (and carry) given the same
    batted-ball feature vector.
  - Each head is tiny (Linear(64, 5) = 325 params; the carry head adds
    Linear(64, 1) = 65 params), so the 30+30 heads add ~12K params vs a
    1-head model — the backbone (4096 params) is the dominant cost.

Topology summary (defaults):
    n_features=15 → Dense(128) → ReLU → Dropout(0.1)
                  → Dense(128) → ReLU → Dropout(0.1)
                  ↓                               ↓
       (30 parallel Dense(5)) → stack    (30 parallel Dense(1)) → stack
                  ↓                               ↓
            logits (B, 30, 5)               carry  (B, 30, 1)
"""

from __future__ import annotations

import numpy as np
import torch
from torch import nn


class BattedBallMLP(nn.Module):
    """Shared-backbone, multi-head MLP for per-park batted-ball outcomes + carry.

    ``forward`` returns ``(logits, carry)``:
      - ``logits``: raw outcome logits, shape ``(B, n_parks, n_outcomes)``.
        Callers apply softmax along the last axis to get per-park outcome
        probability distributions.
      - ``carry``: standardised per-park carry (serving un-standardises to
        feet), shape ``(B, n_parks, 1)``.
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
        # Phase 4: a parallel bank of per-park carry-distance heads. One
        # standardised scalar per park, same shared backbone. Kept as a separate
        # ModuleList (not a wider outcome head) so the two targets stay
        # independently interpretable and the outcome export is unaffected when
        # carry is held.
        self.dist_heads = nn.ModuleList([nn.Linear(hidden, 1) for _ in range(n_parks)])

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        h = self.backbone(x)
        # Stack along park axis. The loops are fine: 2 * 30 small Linears is
        # well under any GPU overhead.
        logits = torch.stack([head(h) for head in self.heads], dim=1)  # (B, n_parks, n_outcomes)
        carry = torch.stack([dh(h) for dh in self.dist_heads], dim=1)  # (B, n_parks, 1)
        return logits, carry


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


def predict_park_probs(model: BattedBallMLP, xs: np.ndarray) -> np.ndarray:
    """Per-park OUTCOME probabilities from the 2-output (carry) model.

    Centralises the Phase-4 carry-aware forward: the model returns
    ``(logits, carry)``, so a caller that softmaxes the raw forward output
    crashes - exactly the bug that blocked the 2c.6 calibrator re-fit on the
    carry model. This unpacks the outcome logits and applies a per-park softmax
    over the outcome axis. The same idiom is used inline by ``sanity``,
    ``rolling_cv_eval``, and ``carry_promotion_eval``; routing the calibrator
    re-fit through here keeps all four consistent and regression-tested.

    Args:
        model: a :class:`BattedBallMLP`. The caller is responsible for putting
            it in ``eval()`` mode (dropout off) before calling.
        xs: the SCALED feature matrix, shape ``(B, n_features)`` float32.

    Returns:
        ``(B, n_parks, n_outcomes)`` float32 probabilities; each park's outcome
        distribution sums to 1.
    """
    with torch.no_grad():
        logits, _carry = model(torch.from_numpy(xs))
        return torch.softmax(logits, dim=-1).numpy()


__all__ = ("BattedBallMLP", "build_model", "predict_park_probs")
