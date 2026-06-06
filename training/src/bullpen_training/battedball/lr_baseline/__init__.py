"""Pooled, park-agnostic logistic-regression baseline for batted-ball outcomes (rule 9 /
decision [142] purist path). One Pipeline(StandardScaler, LogisticRegression) on the 15
features -> [5]; tiled to [30, 5] at export so it serves through the shared batted-ball
contract and answers the baseline question: do the per-park MLP/LGBM heads beat ignoring park?
"""

from bullpen_training.battedball.lr_baseline.train import (
    LrBaselineBundle,
    load_lr_baseline_bundle,
    save_lr_baseline_bundle,
    train_lr_baseline,
)

__all__ = (
    "LrBaselineBundle",
    "load_lr_baseline_bundle",
    "save_lr_baseline_bundle",
    "train_lr_baseline",
)
