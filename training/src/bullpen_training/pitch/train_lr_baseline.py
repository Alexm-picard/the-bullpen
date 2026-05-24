"""Multinomial logistic regression baseline (Phase 2a.6).

Decision [37] requires every primary model to have a registered LR
baseline that NEVER ages out. If the LightGBM model ever underperforms
the LR baseline on a fold's Brier, something is wrong with the
LightGBM training — not with LR.

The LR pipeline is intentionally simple: StandardScaler + multinomial
LogisticRegression with L2 regularisation. No hyperparameter search.
Same isotonic calibrator on the val fold as the primary model, so the
comparison is apples-to-apples.

Persisted as model_name='pitch_outcome_lr_baseline' (NOT
'pitch_outcome_pre') in 2a.7. The two share the same feature pipeline
(Tier 1+2+3 columns) but live in separate registry rows.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import cast

import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.isotonic import IsotonicCalibrator

log = logging.getLogger(__name__)

DEFAULT_SEED = 42


def _build_pipeline(seed: int) -> Pipeline:
    """Pipeline: median-impute Tier 3 NULLs → StandardScaler → multinomial LR.

    LightGBM handles missing values natively; LR cannot. The imputer
    fills NaN with each column's median computed from the train window.
    """
    return Pipeline(
        steps=[
            ("imputer", SimpleImputer(strategy="median")),
            ("scaler", StandardScaler()),
            (
                "lr",
                LogisticRegression(
                    solver="lbfgs",
                    max_iter=2000,
                    C=1.0,
                    random_state=seed,
                ),
            ),
        ]
    )


@dataclass
class LRModelBundle:
    pipeline: Pipeline
    calibrator: IsotonicCalibrator
    feature_cols: tuple[str, ...]
    fitted_label_classes: tuple[int, ...]

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = cast(np.ndarray, self.pipeline.predict_proba(X[list(self.feature_cols)]))
        # `predict_proba` returns columns in `pipeline.named_steps["lr"].classes_`
        # order. Reorder to match LABEL_CLASSES (0..4) so the calibrator sees
        # the canonical layout.
        canonical = np.zeros((raw.shape[0], len(LABEL_CLASSES)), dtype=np.float64)
        for j, cls_int in enumerate(self.fitted_label_classes):
            canonical[:, cls_int] = raw[:, j]
        return self.calibrator.transform(canonical)


def model_factory(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = DEFAULT_SEED,
) -> LRModelBundle:
    """LR pipeline + isotonic calibrator on val_df."""
    feat_cols = list(PITCH_FEATURE_COLUMNS)
    pipe = _build_pipeline(seed)
    pipe.fit(train_df[feat_cols], np.asarray(train_df["label"], dtype=np.int64))

    fitted_classes = tuple(int(c) for c in pipe.named_steps["lr"].classes_)

    val_pred = cast(np.ndarray, pipe.predict_proba(val_df[feat_cols]))
    canonical_val_pred = np.zeros((val_pred.shape[0], len(LABEL_CLASSES)), dtype=np.float64)
    for j, cls_int in enumerate(fitted_classes):
        canonical_val_pred[:, cls_int] = val_pred[:, j]

    calibrator = IsotonicCalibrator.fit(
        np.asarray(val_df["label"], dtype=np.int64),
        canonical_val_pred,
        class_labels=LABEL_CLASSES,
    )

    return LRModelBundle(
        pipeline=pipe,
        calibrator=calibrator,
        feature_cols=tuple(feat_cols),
        fitted_label_classes=fitted_classes,
    )
