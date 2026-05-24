"""LightGBM Option-A baseline for batted-ball outcomes (Phase 2c.8).

Decision [46]: keep a single-output LGBM with ``park_id`` as a
categorical feature alongside the multi-output MLP as the bake-off
baseline. This baseline validates whether the MLP's complexity is
worth it; if LGBM beats it on aggregate Brier, surface loudly in
2c.9's eval artifact — that's a signal the multi-output story
doesn't pay off.

This baseline is trained on the SAME (BIP, park) retrodicted-label
table the MLP uses (V011 ``bbip_retrodicted_labels``), but with the
park axis flattened: each (BIP, park) row becomes one training
example, label = argmax of the 5-class probability vector. ``park_id``
joins the feature set as a categorical column LightGBM handles
natively.

Public surface:

- :func:`load_lgbm_dataset` — flattens BIP x park into a pandas
  DataFrame with the feature + label columns the trainer expects.
- :func:`train_lgbm` — fits the booster + a single isotonic calibrator
  (NOT per-park — the LGBM model handles park signal via the feature).
- :func:`predict_proba` / :func:`predict_proba_calibrated` — inference.
- :func:`save_baseline` / :func:`load_baseline` — persistence wrappers.
"""

from __future__ import annotations

from bullpen_training.battedball.lgbm_baseline.dataset import (
    FEATURE_COLUMNS,
    LABEL_COLUMN,
    PARK_FEATURE,
    load_lgbm_dataset,
)
from bullpen_training.battedball.lgbm_baseline.train import (
    LgbmBaselineBundle,
    load_baseline,
    predict_proba,
    predict_proba_calibrated,
    save_baseline,
    train_lgbm,
)

__all__ = (
    "FEATURE_COLUMNS",
    "LABEL_COLUMN",
    "PARK_FEATURE",
    "LgbmBaselineBundle",
    "load_baseline",
    "load_lgbm_dataset",
    "predict_proba",
    "predict_proba_calibrated",
    "save_baseline",
    "train_lgbm",
)
