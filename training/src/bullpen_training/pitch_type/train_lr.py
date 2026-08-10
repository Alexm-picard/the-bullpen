"""Multinomial logistic-regression baseline for the pitch-TYPE head (Phase 2b, decision [183]).

Rule 9 / decision [37]: every primary model carries a co-registered LR baseline that never
ages out. For pitch-type it is load-bearing twice over:

  * decision [183]'s promotion GUARDRAIL is log-loss vs this baseline (max_delta 0.0, i.e.
    the LightGBM head must be at least as good), and
  * because pitch_type_pre has no champion yet, the [182]/#334 first-champion offline-gate
    path binds the gate row's championVersionId to a registered version of THIS model
    (RegistryBaselines: pitch_type_pre -> pitch_type_lr_baseline).

It calibrates with the SAME single-scalar TEMPERATURE the primary head uses, not isotonic.
That is deliberate and mirrors the pitch-outcome baseline's rationale ("same calibrator as
the primary model, so the comparison is apples-to-apples"): the [183] guardrail compares
log-loss between the two, and a different calibration family on each side would make that
comparison partly a measurement of the calibrators rather than of the models.

The pipeline is intentionally simple - median impute, standardise, multinomial LR, no
hyperparameter search. LightGBM handles NaN natively; LR cannot, hence the imputer for the
NULL-bearing ARS / Tier-S columns.

Persisted as model_name='pitch_type_lr_baseline' (a separate registry row from
'pitch_type_pre', rule 9), sharing the same feature contract.
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

from bullpen_training.pitch.train_lr_baseline import (
    DESIGN_MATRIX_DTYPE,
    LR_TRAIN_SUBSAMPLE_SEED,
    MAX_LR_TRAIN_ROWS,
    assert_float32_preserved,
    subsample_train_rows,
)
from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.temperature import TemperatureCalibrator

log = logging.getLogger(__name__)

DEFAULT_SEED = 42

# Re-exported so the pitch-type persist/production path records the same provenance the
# pitch head does without reaching across heads for it.
__all__ = (
    "DEFAULT_SEED",
    "DESIGN_MATRIX_DTYPE",
    "LR_TRAIN_SUBSAMPLE_SEED",
    "MAX_LR_TRAIN_ROWS",
    "LRModelBundle",
    "fit_lr_from_arrays",
    "model_factory",
    "subsample_train_rows",
)


def _build_pipeline(seed: int) -> Pipeline:
    """Median-impute the NULL-bearing columns -> StandardScaler -> multinomial LR.

    NOTE on ``random_state`` (CLAUDE.md forbids it on DATA SPLITS): it is not a split -
    nothing here partitions data, the rolling-origin split is the caller's date window. It is
    also INERT for this solver: sklearn only consults it for ``sag``/``saga``/``liblinear``
    (to shuffle), and lbfgs is deterministic without it. It is carried purely so a future
    solver swap cannot silently introduce unseeded shuffling.
    """
    return Pipeline(
        steps=[
            ("imputer", SimpleImputer(strategy="median")),
            # copy=False scales the imputer's fresh array in place, removing one full
            # design-matrix copy (the pitch baseline's CV-MEM lever 2).
            ("scaler", StandardScaler(copy=False)),
            (
                "lr",
                LogisticRegression(solver="lbfgs", max_iter=2000, C=1.0, random_state=seed),
            ),
        ]
    )


@dataclass
class LRModelBundle:
    pipeline: Pipeline
    calibrator: TemperatureCalibrator
    feature_cols: tuple[str, ...]
    fitted_label_classes: tuple[int, ...]
    # Rows actually fit on when the memory subsample applied; None = full-data fit (the CV
    # folds). Recorded in metadata so the persisted model's basis is never overstated.
    train_subsample_rows: int | None = None
    # The pre-subsample row count, so metadata can state the RETAINED FRACTION rather than
    # leaving a reader to infer it. Matters here more than for the pitch head: this bundle is
    # the registry row the [182] first-champion offline gate binds against.
    train_rows_before_subsample: int | None = None

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        # Feed a nameless array (column order = feature_cols) to match the array fit in
        # fit_lr_from_arrays; a named frame would trigger an sklearn name-mismatch warning.
        raw = cast(np.ndarray, self.pipeline.predict_proba(X[list(self.feature_cols)].to_numpy()))
        return self.calibrator.transform(_to_canonical_columns(raw, self.fitted_label_classes))


def _to_canonical_columns(raw: np.ndarray, fitted_classes: tuple[int, ...]) -> np.ndarray:
    """Reorder predict_proba's columns from sklearn's ``classes_`` order into the canonical
    y7 layout (FF=0..OFF=6).

    sklearn emits one column per class it SAW, in sorted order. If a fold's training slice
    were missing a rare class entirely, ``classes_`` would be shorter than 7 and the missing
    class must read as probability 0 rather than silently shifting every later column left -
    which is exactly the kind of off-by-one that would quietly corrupt the guardrail
    comparison.
    """
    n_classes = len(PITCH_TYPE_CLASSES)
    canonical = np.zeros((raw.shape[0], n_classes), dtype=np.float64)
    for j, cls_int in enumerate(fitted_classes):
        # Guard the scatter: numpy would WRAP a negative index (class -1 would land in the
        # last column) and mis-attribute that mass silently. Unreachable today - the loader's
        # label map raises on an unmapped y7 code and V029's Enum8 pins the vocab - but a
        # silent mis-attribution here would corrupt the [183] guardrail rather than crash.
        if not 0 <= cls_int < n_classes:
            raise ValueError(
                f"sklearn reported class {cls_int}, outside the y7 vocabulary 0..{n_classes - 1}"
            )
        canonical[:, cls_int] = raw[:, j]
    return canonical


def fit_lr_from_arrays(
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_val: np.ndarray,
    y_val: np.ndarray,
    *,
    seed: int = DEFAULT_SEED,
) -> LRModelBundle:
    """Fit the LR pipeline + temperature calibrator from numpy arrays.

    The array entry point (vs the frame entry point :func:`model_factory`) lets the
    production path free the multi-GB source frames BEFORE the fit allocates the
    imputer/scaler/solver copies. Design matrices are expected float32
    (:data:`DESIGN_MATRIX_DTYPE`), guarded against a silent sklearn upcast.
    """
    pipe = _build_pipeline(seed)
    pipe.fit(X_train, y_train)
    assert_float32_preserved(pipe, X_train)

    fitted_classes = tuple(int(c) for c in pipe.named_steps["lr"].classes_)

    val_pred = cast(np.ndarray, pipe.predict_proba(X_val))
    calibrator = TemperatureCalibrator.fit(
        np.asarray(y_val, dtype=np.int64),
        _to_canonical_columns(val_pred, fitted_classes),
        class_labels=PITCH_TYPE_CLASSES,
    )
    log.info("pitch-type LR temperature fit: T=%.4f", calibrator.temperature)

    return LRModelBundle(
        pipeline=pipe,
        calibrator=calibrator,
        feature_cols=tuple(PITCH_TYPE_FEATURE_COLUMNS),
        fitted_label_classes=fitted_classes,
    )


def model_factory(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = DEFAULT_SEED,
) -> LRModelBundle:
    """Frame entry point driven by the CV harness: extract float32 design matrices, then
    delegate to :func:`fit_lr_from_arrays`. CV folds are small enough that the float32 win is
    pure upside and keeps CV's precision matching the persisted production model's."""
    feat_cols = list(PITCH_TYPE_FEATURE_COLUMNS)
    return fit_lr_from_arrays(
        train_df[feat_cols].to_numpy(dtype=DESIGN_MATRIX_DTYPE),
        np.asarray(train_df["label"], dtype=np.int64),
        val_df[feat_cols].to_numpy(dtype=DESIGN_MATRIX_DTYPE),
        np.asarray(val_df["label"], dtype=np.int64),
        seed=seed,
    )
