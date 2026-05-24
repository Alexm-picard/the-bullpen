"""Multi-output MLP for park-aware batted-ball outcomes (Phase 2c.5).

Decision [45]: a shared MLP backbone with 30 per-park output heads,
trained on the (BIP, park) -> 5-class probability vectors that 2c.4's
retrodiction pipeline emits.

Public surface:

- :class:`BattedBallMLP` — the model.
- :func:`build_model` — factory with the production defaults.
- :class:`BBIPDataset` — Torch Dataset that streams from
  ``bbip_retrodicted_labels`` + ``pitches`` (with weather defaulted).
- :func:`train_model` — KLDiv + cosine LR + ONNX export.

Park ordering: heads are indexed by the sorted ``park_id`` list at the
time the model was built. The order is captured in the saved metadata
JSON so inference (in Java via ONNX) knows which head goes with which
park.
"""

from __future__ import annotations

from bullpen_training.battedball.mlp.architecture import BattedBallMLP, build_model
from bullpen_training.battedball.mlp.dataset import (
    FEATURE_NAMES,
    BBIPDataset,
    FeatureScaler,
    base_state_one_hot,
    stand_one_hot,
)

__all__ = (
    "FEATURE_NAMES",
    "BBIPDataset",
    "BattedBallMLP",
    "FeatureScaler",
    "base_state_one_hot",
    "build_model",
    "stand_one_hot",
)
