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

# LR production-fit memory accommodation (Path 1). The full fold-4 train window (~20M
# rows) OOMs the live box during the LR fit: lbfgs upcasts the float32 design matrix to
# float64 internally (MEASURED - peak RSS ~13.7 GB at 20M, vs the ~3 GB target), so the
# float32 cast (DESIGN_MATRIX_DTYPE) only halved the preprocessing intermediates, not
# the dominant solver allocation. Cap the LR PRODUCTION fit at a fixed-seed row
# subsample (measured: 3M rows -> ~2.5 GB peak, comfortably under the ~4 GB post-CV
# headroom). 3M is far more than a 31-feature baseline needs, so the rule-[37] sanity
# (LightGBM brier <= LR brier) holds. This is NOT a re-split: the temporal fold and its
# window are unchanged, no random_state on any split - a row thin within the fixed
# window. CV + the test eval-metrics stay full-data; only the persisted LR model is on
# the subsample.
MAX_LR_TRAIN_ROWS = 3_000_000
LR_TRAIN_SUBSAMPLE_SEED = DEFAULT_SEED


def subsample_train_rows(
    train_df: pd.DataFrame,
    *,
    max_rows: int = MAX_LR_TRAIN_ROWS,
    seed: int = LR_TRAIN_SUBSAMPLE_SEED,
) -> pd.DataFrame:
    """Fixed-seed row sample within the fold's already-fixed train window, applied only
    when it exceeds ``max_rows`` (see the module note above on why this is a memory
    accommodation, not a re-split). Returns ``train_df`` unchanged when it is already
    within the cap (e.g. the CV folds, which are NOT subsampled). Uniform random is fine
    for a 31-feature baseline at multi-million rows - class balance holds statistically.
    """
    if len(train_df) <= max_rows:
        return train_df
    idx = np.random.default_rng(seed).choice(len(train_df), size=max_rows, replace=False)
    return cast(pd.DataFrame, train_df.iloc[idx])


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
    # Rows the model was actually fit on when a subsample was applied (Path 1); None =
    # full-data fit (e.g. the CV folds). Set by _train_production_lr, recorded in metadata.
    train_subsample_rows: int | None = None

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
# accommodation. NOTE (corrected from the f1d2b66 claim): float32 only halves the
# DATA + preprocessing intermediates; lbfgs upcasts the design matrix to float64
# internally for the solve (MEASURED - float32 peak RSS is ~0.70x float64, not ~0.5x),
# so it did NOT halve the dominant allocation and did NOT clear the fold-4 production
# fit. The headroom fix is the row subsample (MAX_LR_TRAIN_ROWS); float32 is kept
# because it still helps the preprocessing intermediates and the CV folds. Decision
# [37] gives the LR baseline no byte-identity gate, so the ~3e-6 precision delta on
# standardised features cannot make LR spuriously beat a real LightGBM (rule-[37]
# sanity preserved). CV and production share this dtype so the persisted model and the
# CV-reported metrics describe the same precision.
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
