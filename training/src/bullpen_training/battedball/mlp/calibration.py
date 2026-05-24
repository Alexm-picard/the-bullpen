"""30-park x 5-class isotonic calibration for the batted-ball MLP (Phase 2c.6).

The MLP from 2c.5 outputs raw softmax probabilities per park. Decision
[51] requires a separate calibration step per park — fitting one
isotonic regressor per (park, outcome) cell — so the model's predicted
probabilities match observed frequencies on the val holdout.

Public surface:

- :class:`ParkCalibrators` — frozen 30x5 grid of fitted
  :class:`IsotonicRegression` models with `to_json` / `from_json`.
- :func:`fit_per_park_calibrators` — fit from an array of model probs
  + the corresponding retrodicted-label distributions.
- :func:`transform` — apply per-park, per-class isotonic + renormalise.
- :func:`expected_calibration_error` / :func:`reliability_curve` —
  the metrics the eval artifact in 2c.9 will display.

Discipline:
  - The Java port (`IsotonicCalibratorJava` in the backend) consumes
    ``calibrator.json`` directly — to_json's schema is the contract
    boundary; tests pin it (see `test_calibration.py`).
  - Per-class monotonicity may be slightly violated by the
    post-transform renormalisation (~1e-3 in practice); the leaf's
    "Known edge cases" call this acceptable.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Final

import numpy as np
from sklearn.isotonic import IsotonicRegression

DEFAULT_N_BINS_RELIABILITY: Final[int] = 15


# --- core data structure --------------------------------------------------


@dataclass(frozen=True)
class ParkCalibrators:
    """30 parks x 5 outcomes = 150 fitted IsotonicRegression models.

    The internal storage is a flat list-of-lists keyed first by park
    index, then by outcome index, mirroring the (B, 30, 5) tensor
    layout the MLP produces. ``park_order`` carries the park-id strings
    in the same order as the heads — written into the JSON so the Java
    side can dispatch by park_id rather than by index.
    """

    park_order: tuple[str, ...]
    outcome_order: tuple[str, ...]
    # nested[i][c] = IsotonicRegression for park i / outcome c
    _nested: list[list[IsotonicRegression]]

    def n_parks(self) -> int:
        return len(self._nested)

    def n_outcomes(self) -> int:
        return len(self._nested[0]) if self._nested else 0


# --- fitting --------------------------------------------------------------


def fit_per_park_calibrators(
    raw_probs: np.ndarray,
    label_distributions: np.ndarray,
    *,
    park_order: tuple[str, ...],
    outcome_order: tuple[str, ...],
) -> ParkCalibrators:
    """Fit one isotonic regressor per (park, outcome) cell.

    Args:
      raw_probs: (N, n_parks, n_outcomes) — softmax outputs of the MLP.
      label_distributions: (N, n_parks, n_outcomes) — retrodicted
        probability vectors (the 2c.4 labels). Each fits the isotonic
        on the *probability* vs *retrodicted-probability* relationship,
        which is the cleanest calibration target for a multi-class
        head whose labels are themselves distributions.
      park_order: park-id strings in the same order as the head axis.
      outcome_order: outcome strings, e.g. ('out', '1b', '2b', '3b', 'hr').

    Returns the fitted :class:`ParkCalibrators`.
    """
    if raw_probs.shape != label_distributions.shape:
        raise ValueError(
            f"shape mismatch: raw_probs {raw_probs.shape} vs labels {label_distributions.shape}"
        )
    n, n_parks, n_outcomes = raw_probs.shape
    if n_parks != len(park_order):
        raise ValueError(f"park_order length {len(park_order)} != n_parks axis {n_parks}")
    if n_outcomes != len(outcome_order):
        raise ValueError(
            f"outcome_order length {len(outcome_order)} != n_outcomes axis {n_outcomes}"
        )
    if n < 2:
        raise ValueError(f"need at least 2 samples to fit; got {n}")

    nested: list[list[IsotonicRegression]] = []
    for p in range(n_parks):
        per_class: list[IsotonicRegression] = []
        for c in range(n_outcomes):
            iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
            iso.fit(raw_probs[:, p, c], label_distributions[:, p, c])
            per_class.append(iso)
        nested.append(per_class)
    return ParkCalibrators(
        park_order=tuple(park_order),
        outcome_order=tuple(outcome_order),
        _nested=nested,
    )


# --- transform ------------------------------------------------------------


def transform(calibrators: ParkCalibrators, raw_probs: np.ndarray) -> np.ndarray:
    """Apply per-park, per-class isotonic + renormalise to a valid distribution.

    raw_probs: (N, n_parks, n_outcomes). Returns the same shape with
    each (sample, park) row re-normalised to sum to 1 after the
    monotonic transform.
    """
    _n, n_parks, n_outcomes = raw_probs.shape
    if (n_parks, n_outcomes) != (calibrators.n_parks(), calibrators.n_outcomes()):
        raise ValueError(
            f"raw_probs shape {raw_probs.shape} doesn't match calibrators "
            f"({calibrators.n_parks()}, {calibrators.n_outcomes()})"
        )
    calibrated = np.empty_like(raw_probs, dtype=np.float64)
    for p in range(n_parks):
        for c in range(n_outcomes):
            calibrated[:, p, c] = calibrators._nested[p][c].transform(raw_probs[:, p, c])
    # Floor at a tiny eps before renormalising — isotonic can map to
    # exactly 0 on the low end which would produce NaN if a whole row
    # zeros out.
    calibrated = np.maximum(calibrated, 1e-9)
    row_sums = calibrated.sum(axis=-1, keepdims=True)
    calibrated = calibrated / row_sums
    return calibrated.astype(np.float32)


# --- metrics ---------------------------------------------------------------


def reliability_curve(
    pred_probs: np.ndarray,
    binary_labels: np.ndarray,
    *,
    n_bins: int = DEFAULT_N_BINS_RELIABILITY,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Compute the (bin_centers, mean_pred, mean_obs) reliability curve.

    pred_probs: 1-D array of predicted probabilities for one class.
    binary_labels: 1-D 0/1 array of whether the class was observed.
    Returns three (n_bins,) arrays — used by both ECE computation and
    the per-park reliability-diagram plots in 2c.6's eval artifact.
    Empty bins return (center, NaN, NaN) so plots can skip them.
    """
    if pred_probs.ndim != 1 or binary_labels.ndim != 1:
        raise ValueError("reliability_curve expects 1-D inputs")
    if pred_probs.shape != binary_labels.shape:
        raise ValueError("pred_probs and binary_labels shape mismatch")
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    centers = 0.5 * (bins[:-1] + bins[1:])
    mean_pred = np.full(n_bins, np.nan, dtype=np.float64)
    mean_obs = np.full(n_bins, np.nan, dtype=np.float64)
    for i in range(n_bins):
        lo, hi = bins[i], bins[i + 1]
        # Inclusive lower edge, exclusive upper — except the last bin which
        # absorbs the 1.0 endpoint.
        if i == n_bins - 1:
            mask = (pred_probs >= lo) & (pred_probs <= hi)
        else:
            mask = (pred_probs >= lo) & (pred_probs < hi)
        if mask.any():
            mean_pred[i] = float(pred_probs[mask].mean())
            mean_obs[i] = float(binary_labels[mask].mean())
    return centers, mean_pred, mean_obs


def expected_calibration_error(
    pred_probs: np.ndarray,
    binary_labels: np.ndarray,
    *,
    n_bins: int = DEFAULT_N_BINS_RELIABILITY,
) -> float:
    """Weighted-mean |mean_obs - mean_pred| across non-empty bins."""
    if pred_probs.size == 0:
        return float("nan")
    bins = np.linspace(0.0, 1.0, n_bins + 1)
    ece = 0.0
    total = pred_probs.size
    for i in range(n_bins):
        lo, hi = bins[i], bins[i + 1]
        if i == n_bins - 1:
            mask = (pred_probs >= lo) & (pred_probs <= hi)
        else:
            mask = (pred_probs >= lo) & (pred_probs < hi)
        n_in_bin = int(mask.sum())
        if n_in_bin == 0:
            continue
        gap = abs(float(binary_labels[mask].mean()) - float(pred_probs[mask].mean()))
        ece += (n_in_bin / total) * gap
    return float(ece)


def per_park_ece(
    raw_probs: np.ndarray,
    label_distributions: np.ndarray,
    *,
    n_bins: int = DEFAULT_N_BINS_RELIABILITY,
) -> np.ndarray:
    """Mean ECE across the 5 outcome classes for each of the n_parks.

    Both arrays have shape (N, n_parks, n_outcomes). We compute ECE per
    (park, class) and average within park. Returns an (n_parks,) array.
    """
    _n, n_parks, n_outcomes = raw_probs.shape
    out = np.zeros(n_parks, dtype=np.float64)
    for p in range(n_parks):
        per_class = np.zeros(n_outcomes)
        for c in range(n_outcomes):
            preds = raw_probs[:, p, c].ravel()
            obs = label_distributions[:, p, c].ravel()
            per_class[c] = expected_calibration_error(preds, obs, n_bins=n_bins)
        out[p] = float(per_class.mean())
    return out


# --- JSON I/O -------------------------------------------------------------


def to_json(calibrators: ParkCalibrators) -> dict:
    """Schema mirrored by the Java IsotonicCalibrator port (decision [51])."""
    parks_payload: list[dict] = []
    for p_idx, park_id in enumerate(calibrators.park_order):
        classes: list[dict] = []
        for c_idx, outcome in enumerate(calibrators.outcome_order):
            iso = calibrators._nested[p_idx][c_idx]
            classes.append(
                {
                    "outcome": outcome,
                    # IsotonicRegression stores its breakpoints as float arrays;
                    # we serialise the X+Y arrays + the out-of-bounds policy
                    # so the Java side reproduces the same monotone interp.
                    "x_thresholds": iso.X_thresholds_.astype(float).tolist(),
                    "y_thresholds": iso.y_thresholds_.astype(float).tolist(),
                    "y_min": float(iso.y_min) if iso.y_min is not None else None,
                    "y_max": float(iso.y_max) if iso.y_max is not None else None,
                    "out_of_bounds": iso.out_of_bounds,
                }
            )
        parks_payload.append({"park_id": park_id, "classes": classes})
    return {
        "schema_version": 1,
        "calibrator_name": "battedball_outcome_calibrator",
        "calibrator_version": "v1",
        "park_order": list(calibrators.park_order),
        "outcome_order": list(calibrators.outcome_order),
        "parks": parks_payload,
    }


def from_json(payload: dict) -> ParkCalibrators:
    """Rebuild a ParkCalibrators from a to_json() payload."""
    if payload.get("schema_version") != 1:
        raise ValueError(f"unknown calibrator schema_version: {payload.get('schema_version')}")
    park_order = tuple(payload["park_order"])
    outcome_order = tuple(payload["outcome_order"])
    nested: list[list[IsotonicRegression]] = []
    for park_block in payload["parks"]:
        per_class: list[IsotonicRegression] = []
        for cls in park_block["classes"]:
            iso = IsotonicRegression(
                out_of_bounds=cls.get("out_of_bounds", "clip"),
                y_min=cls.get("y_min"),
                y_max=cls.get("y_max"),
            )
            # Recreate the fitted state by stuffing the breakpoint arrays
            # into the attributes sklearn looks at on transform.
            iso.X_thresholds_ = np.asarray(cls["x_thresholds"], dtype=np.float64)
            iso.y_thresholds_ = np.asarray(cls["y_thresholds"], dtype=np.float64)
            iso.X_min_ = float(iso.X_thresholds_.min()) if iso.X_thresholds_.size else 0.0
            iso.X_max_ = float(iso.X_thresholds_.max()) if iso.X_thresholds_.size else 1.0
            iso.increasing_ = True
            # Rebuild the interpolator from the breakpoint arrays — sklearn
            # builds this lazily inside fit() but not on attribute restore,
            # so we call the private builder explicitly.
            iso._build_f(iso.X_thresholds_, iso.y_thresholds_)
            per_class.append(iso)
        nested.append(per_class)
    return ParkCalibrators(park_order=park_order, outcome_order=outcome_order, _nested=nested)


def save_calibrator(calibrators: ParkCalibrators, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(to_json(calibrators), indent=2))


def load_calibrator(path: Path) -> ParkCalibrators:
    return from_json(json.loads(path.read_text()))


__all__ = (
    "DEFAULT_N_BINS_RELIABILITY",
    "ParkCalibrators",
    "expected_calibration_error",
    "fit_per_park_calibrators",
    "from_json",
    "load_calibrator",
    "per_park_ece",
    "reliability_curve",
    "save_calibrator",
    "to_json",
    "transform",
)
