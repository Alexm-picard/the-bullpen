"""Per-park reliability-diagram plotting (Phase 2c.6).

Emits one PNG per park to ``eval/reliability_diagrams_per_park/`` —
each plot overlays raw vs calibrated reliability curves for all 5
outcome classes against the y=x diagonal. The figures land in the
2c.9 eval artifact bundle.

matplotlib import is module-level; tests that don't need plots can
skip the module entirely without paying the import cost.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # — headless backend for CI / no-display
import matplotlib.pyplot as plt
import numpy as np

from bullpen_training.battedball.mlp.calibration import (
    DEFAULT_N_BINS_RELIABILITY,
    ParkCalibrators,
    reliability_curve,
    transform,
)


def plot_park_reliability(
    park_id: str,
    raw_probs: np.ndarray,
    calibrated_probs: np.ndarray,
    label_distributions: np.ndarray,
    outcome_order: tuple[str, ...],
    *,
    out_path: Path,
    n_bins: int = DEFAULT_N_BINS_RELIABILITY,
) -> None:
    """Plot raw vs calibrated reliability for one park.

    raw_probs / calibrated_probs / label_distributions all have shape
    (N, n_outcomes) — this function operates on a single park's slice.
    Saves the figure as a PNG at ``out_path``.
    """
    fig, axes = plt.subplots(
        nrows=1,
        ncols=len(outcome_order),
        figsize=(3.2 * len(outcome_order), 3.5),
        squeeze=False,
    )
    fig.suptitle(f"Park: {park_id} — Reliability (raw vs calibrated)")
    for c, outcome in enumerate(outcome_order):
        ax = axes[0, c]
        # Reliability curves expect 1-D inputs.
        labels_c = label_distributions[:, c]
        raw_c = raw_probs[:, c]
        cal_c = calibrated_probs[:, c]
        _, raw_pred, raw_obs = reliability_curve(raw_c, labels_c, n_bins=n_bins)
        _, cal_pred, cal_obs = reliability_curve(cal_c, labels_c, n_bins=n_bins)
        # y=x diagonal reference.
        ax.plot([0, 1], [0, 1], color="lightgray", linestyle="--", linewidth=1)
        ax.plot(raw_pred, raw_obs, marker="o", linestyle="-", label="raw", color="tab:red")
        ax.plot(cal_pred, cal_obs, marker="s", linestyle="-", label="cal", color="tab:blue")
        ax.set_title(outcome)
        ax.set_xlim(0.0, 1.0)
        ax.set_ylim(0.0, 1.0)
        ax.set_xlabel("predicted")
        if c == 0:
            ax.set_ylabel("observed")
            ax.legend(loc="upper left", fontsize=8)
    fig.tight_layout(rect=(0, 0, 1, 0.95))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=120)
    plt.close(fig)


def plot_all_parks(
    raw_probs: np.ndarray,
    label_distributions: np.ndarray,
    calibrators: ParkCalibrators,
    *,
    out_dir: Path,
    n_bins: int = DEFAULT_N_BINS_RELIABILITY,
) -> list[Path]:
    """Plot a reliability diagram per park and return the list of paths.

    Computes the calibrated tensor once via :func:`transform`, then
    fans out one figure per park.
    """
    calibrated = transform(calibrators, raw_probs)
    written: list[Path] = []
    for p, park_id in enumerate(calibrators.park_order):
        out_path = out_dir / f"{park_id}.png"
        plot_park_reliability(
            park_id=park_id,
            raw_probs=raw_probs[:, p, :],
            calibrated_probs=calibrated[:, p, :],
            label_distributions=label_distributions[:, p, :],
            outcome_order=calibrators.outcome_order,
            out_path=out_path,
            n_bins=n_bins,
        )
        written.append(out_path)
    return written


__all__ = ("plot_all_parks", "plot_park_reliability")
