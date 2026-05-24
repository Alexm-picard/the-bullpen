"""Matplotlib renderers for the eval artifact (Phase 2a.7).

Forces the headless Agg backend so the same code runs in CI and on a
server without a DISPLAY.
"""

from __future__ import annotations

from typing import cast

import matplotlib

matplotlib.use("Agg")  # MUST happen before pyplot import — locks the backend
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.figure import Figure as Figure  # re-export so formatter doesn't drop it


def reliability_diagram(
    y_true: np.ndarray,
    y_pred_proba: np.ndarray,
    class_labels: list[str],
    *,
    n_bins: int = 10,
) -> Figure:
    """Per-class reliability plot. K subplots, one per class, showing
    bin-mean predicted-probability vs bin-mean observed-frequency.
    Perfectly calibrated = points on the y=x diagonal.
    """
    n_classes = y_pred_proba.shape[1]
    y_onehot = np.zeros_like(y_pred_proba)
    y_onehot[np.arange(len(y_true)), np.asarray(y_true, dtype=np.int64)] = 1.0

    fig, axes_raw = plt.subplots(
        nrows=1, ncols=n_classes, figsize=(3.0 * n_classes, 3.0), sharey=True
    )
    # plt.subplots returns Axes for ncols=1, ndarray of Axes for ncols>1.
    # Normalise to a list either way.
    axes_list: list = [axes_raw] if n_classes == 1 else list(cast(np.ndarray, axes_raw))
    bin_edges = np.linspace(0.0, 1.0, n_bins + 1)
    for c in range(n_classes):
        ax = axes_list[c]
        proba_c = y_pred_proba[:, c]
        bin_means_proba: list[float] = []
        bin_means_obs: list[float] = []
        for i in range(n_bins):
            lo, hi = bin_edges[i], bin_edges[i + 1]
            mask = (
                (proba_c >= lo) & (proba_c < hi)
                if i < n_bins - 1
                else (proba_c >= lo) & (proba_c <= hi)
            )
            if mask.sum() == 0:
                continue
            bin_means_proba.append(float(proba_c[mask].mean()))
            bin_means_obs.append(float(y_onehot[mask, c].mean()))
        ax.plot([0, 1], [0, 1], color="gray", linestyle="--", linewidth=0.8)
        ax.plot(bin_means_proba, bin_means_obs, marker="o", linewidth=1.0)
        ax.set_xlim(0, 1)
        ax.set_ylim(0, 1)
        ax.set_title(class_labels[c], fontsize=10)
        ax.set_xlabel("predicted prob")
        if c == 0:
            ax.set_ylabel("observed freq")
        ax.tick_params(labelsize=8)
    fig.suptitle("Reliability diagram (post-calibration)", fontsize=11)
    fig.tight_layout(rect=(0, 0, 1, 0.93))
    return cast(Figure, fig)


def confusion_matrix_plot(
    y_true: np.ndarray,
    y_pred_argmax: np.ndarray,
    class_labels: list[str],
) -> Figure:
    """Row-normalised confusion matrix heatmap."""
    n_classes = len(class_labels)
    y_t = np.asarray(y_true, dtype=np.int64)
    y_p = np.asarray(y_pred_argmax, dtype=np.int64)
    matrix = np.zeros((n_classes, n_classes), dtype=np.float64)
    for t, p in zip(y_t, y_p, strict=True):
        matrix[t, p] += 1
    row_sums = matrix.sum(axis=1, keepdims=True)
    normalised = np.zeros_like(matrix)
    np.divide(matrix, row_sums, where=row_sums != 0, out=normalised)

    fig, ax = plt.subplots(figsize=(5.5, 5.0))
    im = ax.imshow(normalised, cmap="Blues", vmin=0.0, vmax=1.0)
    ax.set_xticks(range(n_classes))
    ax.set_yticks(range(n_classes))
    ax.set_xticklabels(class_labels, rotation=45, ha="right", fontsize=8)
    ax.set_yticklabels(class_labels, fontsize=8)
    ax.set_xlabel("predicted")
    ax.set_ylabel("true")
    for i in range(n_classes):
        for j in range(n_classes):
            ax.text(
                j,
                i,
                f"{normalised[i, j]:.2f}",
                ha="center",
                va="center",
                color="black" if normalised[i, j] < 0.5 else "white",
                fontsize=8,
            )
    fig.colorbar(im, ax=ax, fraction=0.046, pad=0.04)
    ax.set_title("Confusion matrix (row-normalised)", fontsize=11)
    fig.tight_layout()
    return cast(Figure, fig)
