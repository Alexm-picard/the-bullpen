"""Per-class isotonic calibration for the multinomial pre-pitch head.

Decision [38] picks one-vs-rest isotonic over a single multi-class
calibrator. For each class c we fit `p_c -> P(y = c | p_c)` against a
held-out window, then re-normalise across classes so the calibrated
predictions still form a valid distribution.

The Phase 2 exit criterion is ECE < 0.02 per model. Isotonic post-fit
is the standard lever to hit that target.

Serialisation: each class's isotonic regressor is a piecewise-linear
fit, so we persist as `{x: [...], y: [...]}` per class — the same form
the Java side will use at serving time (Phase 2a.8). No pickle, no
sklearn version pinning.
"""

from __future__ import annotations

import json
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Self, cast

import numpy as np

from bullpen_training.eval.calibration import fit_isotonic


@dataclass(frozen=True)
class IsotonicCalibrator:
    """One-vs-rest isotonic calibrators for a K-class multinomial output."""

    class_labels: tuple[str, ...]
    breakpoints: tuple[tuple[tuple[float, ...], tuple[float, ...]], ...]
    """For each class c: (x_thresholds, y_predictions). Both monotone non-decreasing."""

    @classmethod
    def fit(
        cls,
        y_true: np.ndarray,
        y_pred_proba: np.ndarray,
        *,
        class_labels: Iterable[str],
    ) -> Self:
        labels = tuple(class_labels)
        n_classes = len(labels)
        proba = np.asarray(y_pred_proba, dtype=np.float64)
        if proba.shape[1] != n_classes:
            raise ValueError(f"y_pred_proba has {proba.shape[1]} classes; expected {n_classes}")
        y_int = np.asarray(y_true, dtype=np.int64)

        breakpoints: list[tuple[tuple[float, ...], tuple[float, ...]]] = []
        for c in range(n_classes):
            y_one_vs_rest = (y_int == c).astype(np.float64)
            ir = fit_isotonic(proba[:, c], y_one_vs_rest, y_min=0.0, y_max=1.0, increasing=True)
            xs = cast(np.ndarray, ir.X_thresholds_)
            ys = cast(np.ndarray, ir.y_thresholds_)
            breakpoints.append((tuple(float(v) for v in xs), tuple(float(v) for v in ys)))

        return cls(class_labels=labels, breakpoints=tuple(breakpoints))

    def transform(self, y_pred_proba: np.ndarray) -> np.ndarray:
        """Apply per-class isotonic then re-normalise rows to sum to 1."""
        proba = np.asarray(y_pred_proba, dtype=np.float64)
        if proba.shape[1] != len(self.class_labels):
            raise ValueError(
                f"y_pred_proba has {proba.shape[1]} classes; "
                f"calibrator was fit for {len(self.class_labels)}"
            )
        calibrated = np.empty_like(proba)
        for c, (xs, ys) in enumerate(self.breakpoints):
            calibrated[:, c] = np.interp(
                proba[:, c], np.asarray(xs), np.asarray(ys), left=ys[0], right=ys[-1]
            )
        # Re-normalise rows; if a row sums to 0 (every class clipped to 0),
        # fall back to a uniform distribution over the K classes.
        sums = calibrated.sum(axis=1, keepdims=True)
        zeros = sums.flatten() == 0.0
        if zeros.any():
            calibrated[zeros] = 1.0 / len(self.class_labels)
            sums = calibrated.sum(axis=1, keepdims=True)
        return calibrated / sums

    def to_json(self, path: Path) -> None:
        payload = {
            "class_labels": list(self.class_labels),
            "breakpoints": [
                {"class": cls, "x_thresholds": list(xs), "y_thresholds": list(ys)}
                for cls, (xs, ys) in zip(self.class_labels, self.breakpoints, strict=True)
            ],
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n")

    @classmethod
    def from_json(cls, path: Path) -> Self:
        raw = json.loads(path.read_text())
        breakpoints = tuple(
            (tuple(entry["x_thresholds"]), tuple(entry["y_thresholds"]))
            for entry in raw["breakpoints"]
        )
        return cls(class_labels=tuple(raw["class_labels"]), breakpoints=breakpoints)
