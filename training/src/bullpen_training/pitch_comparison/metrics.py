"""Unified metrics for pitch model comparison."""

from __future__ import annotations

import itertools
import time
from collections.abc import Callable
from dataclasses import dataclass

import numpy as np
from sklearn.metrics import accuracy_score, confusion_matrix, log_loss

from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES


@dataclass
class PitchTypeMetrics:
    name: str
    accuracy: float
    top2_accuracy: float
    logloss: float
    calibration_ece: float
    confusion: list[list[int]]
    per_class_accuracy: dict[str, float]
    train_time_sec: float
    inference_latency_ms: float = 0.0


def top_k_accuracy(
    y_true: np.ndarray, y_proba: np.ndarray, k: int = 2,
) -> float:
    top_k_preds = np.argsort(y_proba, axis=1)[:, -k:]
    return float(np.mean([
        y_true[i] in top_k_preds[i] for i in range(len(y_true))
    ]))


def expected_calibration_error(
    y_true: np.ndarray,
    y_proba: np.ndarray,
    n_bins: int = 15,
) -> float:
    n_classes = y_proba.shape[1]
    ece_sum = 0.0
    total = 0
    for c in range(n_classes):
        confidences = y_proba[:, c]
        actuals = (y_true == c).astype(np.float64)
        bin_edges = np.linspace(0, 1, n_bins + 1)
        for lo, hi in itertools.pairwise(bin_edges):
            mask = (confidences >= lo) & (confidences < hi)
            n = int(mask.sum())
            if n == 0:
                continue
            avg_conf = float(confidences[mask].mean())
            avg_acc = float(actuals[mask].mean())
            ece_sum += n * abs(avg_conf - avg_acc)
            total += n
    return ece_sum / max(total, 1)


def compute_pitch_type_metrics(
    name: str,
    y_true: np.ndarray,
    y_proba: np.ndarray,
    train_time: float,
    class_names: tuple[str, ...] = PITCH_TYPE_CLASSES,
) -> PitchTypeMetrics:
    y_pred = y_proba.argmax(axis=1)
    labels = list(range(len(class_names)))

    per_class = {}
    for i, cn in enumerate(class_names):
        mask = y_true == i
        if mask.sum() > 0:
            per_class[cn] = float((y_pred[mask] == i).mean())
        else:
            per_class[cn] = 0.0

    cm = confusion_matrix(y_true, y_pred, labels=labels)

    return PitchTypeMetrics(
        name=name,
        accuracy=float(accuracy_score(y_true, y_pred)),
        top2_accuracy=top_k_accuracy(y_true, y_proba, k=2),
        logloss=float(log_loss(y_true, y_proba, labels=labels)),
        calibration_ece=expected_calibration_error(y_true, y_proba),
        confusion=cm.tolist(),
        per_class_accuracy=per_class,
        train_time_sec=train_time,
    )


def measure_inference_latency(
    predict_fn: Callable[[], np.ndarray],
    n_warmup: int = 3,
    n_runs: int = 10,
) -> float:
    for _ in range(n_warmup):
        predict_fn()
    times = []
    for _ in range(n_runs):
        t0 = time.perf_counter()
        predict_fn()
        times.append((time.perf_counter() - t0) * 1000)
    return float(np.median(times))
