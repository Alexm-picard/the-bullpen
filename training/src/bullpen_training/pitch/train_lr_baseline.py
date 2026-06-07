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
            # copy=False scales the imputer's output in place (the imputer already
            # made a fresh array), removing one full design-matrix copy. CV-MEM lever 2.
            ("scaler", StandardScaler(copy=False)),
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
        # Feed a nameless array (column order = feature_cols) to match the array fit
        # in fit_lr_from_arrays - the pipeline was fit without feature names, so a
        # named DataFrame here triggers a sklearn name-mismatch warning. Order is
        # preserved by selecting feature_cols first.
        raw = cast(
            np.ndarray,
            self.pipeline.predict_proba(X[list(self.feature_cols)].to_numpy()),
        )
        # `predict_proba` returns columns in `pipeline.named_steps["lr"].classes_`
        # order. Reorder to match LABEL_CLASSES (0..4) so the calibrator sees
        # the canonical layout.
        canonical = np.zeros((raw.shape[0], len(LABEL_CLASSES)), dtype=np.float64)
        for j, cls_int in enumerate(self.fitted_label_classes):
            canonical[:, cls_int] = raw[:, j]
        return self.calibrator.transform(canonical)


# The LR design matrix is built as float32 (NOT float64) - a baseline-only memory
# accommodation (CV-MEM lever 2): the fold-4 train window (~20M x ~31) is a ~5 GB
# float64 array that must coexist with the source frame during extraction, which
# OOMs the serving box; float32 halves it. Decision [37] gives the LR baseline no
# byte-identity gate, so the ~1e-5 precision delta on standardised features is
# acceptable (it cannot make LR spuriously beat a real LightGBM, so the rule-[37]
# sanity signal is preserved). CV and production share this dtype so the persisted
# model and the CV-reported metrics describe the same precision.
DESIGN_MATRIX_DTYPE = np.float32


def _assert_float32_preserved(pipe: Pipeline, X_train: np.ndarray) -> None:
    """Guard the float32 memory win against a silent sklearn upcast.

    float32 is worthless if the sklearn chain upcasts it back to float64 internally
    (a hidden full-size copy that re-OOMs the box). On the resolved scikit-learn
    (currently 1.8.x; the project floor is ``>=1.5``, NOT a pin) the lbfgs
    ``LogisticRegression`` + ``SimpleImputer`` + ``StandardScaler`` preserve float32,
    so a float32 matrix stays float32 through the solve - but that is version-
    dependent and not contractual. Rather than trust a specific version's internals,
    probe the FITTED preprocessing on a 2-row slice: if a future scikit-learn reverts
    to float64-forcing, this fails LOUD here instead of silently doubling memory on
    the next box run. The empirical probe is the guarantee, not the version.
    """
    if X_train.dtype != np.float32:
        return
    probe = pipe.named_steps["scaler"].transform(pipe.named_steps["imputer"].transform(X_train[:2]))
    if probe.dtype != np.float32:
        raise RuntimeError(
            f"float32 LR design matrix was upcast to {probe.dtype} by the sklearn "
            "preprocessing - the CV-MEM lever-2 memory accommodation is void. The "
            "resolved scikit-learn (floor >=1.5) no longer preserves float32 through "
            "SimpleImputer/StandardScaler/LogisticRegression(lbfgs); pin a version "
            "that does, or accept the float64 memory cost."
        )


def fit_lr_from_arrays(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_val: np.ndarray,
    y_val: np.ndarray,
    *,
    seed: int = DEFAULT_SEED,
) -> LRModelBundle:
    """Fit the LR pipeline + isotonic calibrator from numpy arrays.

    The array entry point (vs the frame entry point ``model_factory``) lets the
    production path free the multi-GB source frames BEFORE the fit allocates the
    imputer/scaler/solver copies (CV-MEM lever 2). Mathematically identical to
    ``model_factory``; the design matrices are expected float32 (see
    ``DESIGN_MATRIX_DTYPE``), guarded against a silent sklearn upcast.
    """
    pipe = _build_pipeline(seed)
    pipe.fit(X_train, y_train)
    _assert_float32_preserved(pipe, X_train)

    fitted_classes = tuple(int(c) for c in pipe.named_steps["lr"].classes_)

    val_pred = cast(np.ndarray, pipe.predict_proba(X_val))
    canonical_val_pred = np.zeros((val_pred.shape[0], len(LABEL_CLASSES)), dtype=np.float64)
    for j, cls_int in enumerate(fitted_classes):
        canonical_val_pred[:, cls_int] = val_pred[:, j]

    calibrator = IsotonicCalibrator.fit(
        np.asarray(y_val, dtype=np.int64),
        canonical_val_pred,
        class_labels=LABEL_CLASSES,
    )

    return LRModelBundle(
        pipeline=pipe,
        calibrator=calibrator,
        feature_cols=tuple(PITCH_FEATURE_COLUMNS),
        fitted_label_classes=fitted_classes,
    )


def model_factory(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = DEFAULT_SEED,
) -> LRModelBundle:
    """Frame entry point (used by the CV harness): extract float32 design matrices
    then delegate to :func:`fit_lr_from_arrays`. The CV folds are small, so this gets
    the float32 memory win as pure upside and keeps CV's precision matching the
    persisted production model's."""
    feat_cols = list(PITCH_FEATURE_COLUMNS)
    return fit_lr_from_arrays(
        train_df[feat_cols].to_numpy(dtype=DESIGN_MATRIX_DTYPE),
        np.asarray(train_df["label"], dtype=np.int64),
        val_df[feat_cols].to_numpy(dtype=DESIGN_MATRIX_DTYPE),
        np.asarray(val_df["label"], dtype=np.int64),
        seed=seed,
    )
