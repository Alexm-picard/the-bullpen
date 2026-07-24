"""Single-scalar temperature calibration for the pitch-type multinomial head.

Decision [183] / report section 4: pitch_type_pre is a well-calibrated pitch-TYPE
PRIOR, not a top-1 predictor. Temperature scaling (Guo et al. 2017) is the
calibration lever chosen precisely because it is ORDER-PRESERVING - a single
positive scalar ``T`` divides the logits and re-softmaxes, which cannot change the
argmax or any pairwise ranking. It moves ECE (the gating metric under [183]) WITHOUT
touching top-1 / top-3 accuracy, so the honest-framing constraint holds by
construction: calibration improves and the "we don't predict the next pitch" story
is not quietly undermined by a sharpening step.

This is deliberately NOT the per-class isotonic used by the pitch-OUTCOME heads
(``pitch.isotonic``). Isotonic is per-class and piecewise-linear and can (subtly)
reorder classes; temperature is one global scalar and cannot. For a 7-class prior
whose value is the whole distribution, the report picks temperature: it is
ONNX-foldable at serving time (one divide + softmax) and a fitted ``T ~= 1.0`` is a
strong signal that the raw LightGBM multinomial is already close to calibrated.

Serving-side equivalence (Phase 4, Java): with the ONNX graph emitting the softmax
probabilities ``p``, ``softmax(log(p) / T)_i == p_i**(1/T) / sum_j p_j**(1/T)`` -
the numerically-stable power-normalisation form the Java consumer applies directly
to the 7-vector, no logit reconstruction needed.

Serialisation: ``{kind, class_labels, temperature}`` in ``calibrator.json`` - the
shape the Java serving consumer reads. No pickle, no sklearn.
"""

from __future__ import annotations

import json
import logging
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Self, cast

import numpy as np
from scipy.optimize import (  # pyright: ignore[reportMissingTypeStubs]
    minimize_scalar,  # pyright: ignore[reportUnknownVariableType]
)

log = logging.getLogger(__name__)

# Clamp probabilities away from 0 before log() so pseudo-logits stay finite. Same
# floor is applied to the calibrated truth-class prob inside the NLL objective.
_LOG_FLOOR = 1e-12
# Temperature search window. T < 1 sharpens, T > 1 flattens; 1.0 is the identity.
# The report expects T ~= 1.0, so [0.05, 10.0] is generous headroom on both sides.
_T_LOWER = 0.05
_T_UPPER = 10.0


def _softmax(z: np.ndarray) -> np.ndarray:
    """Row-wise numerically-stable softmax over a 2-D (N, K) logit array."""
    z = z - z.max(axis=1, keepdims=True)
    e = np.exp(z)
    return cast(np.ndarray, e / e.sum(axis=1, keepdims=True))


@dataclass(frozen=True)
class TemperatureCalibrator:
    """A single-scalar temperature calibrator for a K-class multinomial output.

    ``transform`` computes ``softmax(log(p) / T)``. Because ``T > 0`` and softmax is
    monotone in its input, the per-row class ordering (and the argmax) is invariant -
    this calibrator changes confidence, never the ranking.
    """

    class_labels: tuple[str, ...]
    temperature: float

    def __post_init__(self) -> None:
        # T > 0 is the order-preservation invariant: transform divides logits by T,
        # so T <= 0 would divide-by-zero or invert the ranking (turning the calibrated
        # prior into an anti-predictor). Guard every construction path, including
        # from_json reading a hand-edited cross-language calibrator.json.
        if self.temperature <= 0.0:
            raise ValueError(
                f"temperature must be > 0 (the order-preservation invariant); "
                f"got {self.temperature}"
            )

    @classmethod
    def fit(
        cls,
        y_true: np.ndarray,
        y_pred_proba: np.ndarray,
        *,
        class_labels: Iterable[str],
    ) -> Self:
        """Fit ``T`` by minimising multiclass NLL of ``softmax(log(p) / T)`` on a
        held-out window.

        ``y_pred_proba`` are the raw LightGBM multinomial probabilities. We recover
        pseudo-logits as ``log(p)``; softmax is shift-invariant, so the per-row
        normaliser dropped by the log is irrelevant to the result.
        """
        labels = tuple(class_labels)
        n_classes = len(labels)
        proba = np.asarray(y_pred_proba, dtype=np.float64)
        if proba.ndim != 2 or proba.shape[1] != n_classes:
            raise ValueError(
                f"y_pred_proba must be 2-D with {n_classes} classes; got shape {proba.shape}"
            )
        y_int = np.asarray(y_true, dtype=np.int64)
        if y_int.shape[0] != proba.shape[0]:
            raise ValueError(f"y_true has {y_int.shape[0]} rows; y_pred_proba has {proba.shape[0]}")

        logits = np.log(np.clip(proba, _LOG_FLOOR, None))
        # int() pins the row count: ndarray.shape[0] types as Any, and np.arange(Any)
        # degrades to Unknown under strict pyright.
        rows = np.arange(int(y_int.shape[0]))

        def nll(t: float) -> float:
            if t <= 0.0:
                return float("inf")
            calibrated = _softmax(logits / t)
            truth = np.clip(calibrated[rows, y_int], _LOG_FLOOR, None)
            return float(-np.mean(np.log(truth)))

        # scipy is stub-less here (reportMissingTypeStubs=false); isolate the Unknown
        # at this single boundary so the rest of the module stays strictly typed.
        result = cast(Any, minimize_scalar(nll, bounds=(_T_LOWER, _T_UPPER), method="bounded"))
        temperature = float(result.x)
        # A fit that pins to a search bound is a "your calibration data is degenerate" smell
        # (e.g. near-random labels vs confident probs). The report expects T ~= 1.0, so a
        # bound-hit means the val slice is not a normal calibration target - surface it loudly
        # rather than silently persisting a T=10 (maximally-flattened) calibrator.
        if temperature <= _T_LOWER * 1.01 or temperature >= _T_UPPER * 0.99:
            log.warning(
                "temperature fit saturated its search bound (T=%.4f in [%.2f, %.2f]); the "
                "calibration (validation) data may be degenerate - expected T near 1.0",
                temperature,
                _T_LOWER,
                _T_UPPER,
            )
        return cls(class_labels=labels, temperature=temperature)

    def transform(self, y_pred_proba: np.ndarray) -> np.ndarray:
        """Apply temperature scaling: ``softmax(log(p) / T)``. Order-preserving."""
        proba = np.asarray(y_pred_proba, dtype=np.float64)
        if proba.ndim != 2 or proba.shape[1] != len(self.class_labels):
            raise ValueError(
                f"y_pred_proba must be 2-D with {len(self.class_labels)} classes; "
                f"got shape {proba.shape}"
            )
        logits = np.log(np.clip(proba, _LOG_FLOOR, None))
        return _softmax(logits / self.temperature)

    def to_json(self, path: Path) -> None:
        payload = {
            "kind": "temperature",
            "class_labels": list(self.class_labels),
            "temperature": self.temperature,
        }
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n")

    @classmethod
    def from_json(cls, path: Path) -> Self:
        raw = json.loads(path.read_text())
        kind = raw.get("kind")
        if kind != "temperature":
            raise ValueError(f"expected calibrator kind 'temperature'; got {kind!r}")
        try:
            class_labels = tuple(raw["class_labels"])
            temperature = float(raw["temperature"])
        except KeyError as exc:
            raise ValueError(f"calibrator.json missing required field: {exc}") from exc
        # temperature > 0 is enforced by __post_init__ (the order-preservation invariant).
        return cls(class_labels=class_labels, temperature=temperature)
