"""Fit the 30 per-park isotonic calibrators on top of a trained MLP (2c.6).

Standalone CLI for the production pipeline: loads
``artifacts/battedball_mlp_v1/{model.pt,metadata.json}``, runs the val
fold through the model, fits :class:`ParkCalibrators` against the
retrodicted label distributions, and writes
``artifacts/battedball_mlp_v1/calibrator.json``. Also (optionally)
emits the per-park reliability-diagram PNGs the eval bundle ships with.

Lives in `scripts/` because it's a one-off CLI that depends on
artifacts on disk, not on a stable in-process API. Same shape as
`scripts/run_2c9_comparison.py`.

Usage:

  uv run python scripts/fit_calibrators.py \\
      --mlp-dir artifacts/battedball_mlp_v1 \\
      --val-season 2025 \\
      --plots-out-dir data/eval/reliability_diagrams_per_park

The CLI is idempotent — re-running overwrites calibrator.json + the
PNG directory.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

from bullpen_training.battedball.mlp import (
    FeatureScaler,
)
from bullpen_training.battedball.mlp.architecture import build_model, predict_park_probs
from bullpen_training.battedball.mlp.calibration import (
    expected_calibration_error,
    fit_per_park_calibrators,
    per_park_ece,
    save_calibrator,
    transform,
)
from bullpen_training.battedball.mlp.dataset import OUTCOME_NAMES, load_arrays


def _load_scaler(metadata: dict) -> FeatureScaler:
    fs = metadata["feature_scaler"]
    return FeatureScaler(
        means=np.array(fs["means"], dtype=np.float32),
        stds=np.array(fs["stds"], dtype=np.float32),
        is_continuous=np.array(fs["is_continuous"], dtype=bool),
    )


def _forward_all(
    model_dir: Path,
    *,
    season_from: int,
    season_to: int,
) -> tuple[np.ndarray, np.ndarray, tuple[str, ...]]:
    """Return (raw_probs (N,30,5), labels (N,30,5), park_order)."""
    md = json.loads((model_dir / "metadata.json").read_text())
    park_order = tuple(md["park_order"])
    scaler = _load_scaler(md)
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(model_dir / "model.pt", weights_only=True))
    model.eval()

    # load_arrays returns (features, labels, carry) since the Phase-4 carry head;
    # the calibrator fits on the OUTCOME distribution only, so carry is unused here.
    feat, lab, _carry = load_arrays(
        season_from=season_from, season_to=season_to, park_order=park_order
    )
    xs = scaler.transform(feat)
    ys = lab
    # predict_park_probs unpacks the 2-output (logits, carry) forward and softmaxes
    # the logits (a bare softmax(model(xs)) crashes on the carry model).
    probs = predict_park_probs(model, xs)
    return probs, ys, park_order


def main() -> None:
    parser = argparse.ArgumentParser(description="Fit per-park isotonic calibrators (2c.6).")
    parser.add_argument(
        "--mlp-dir",
        type=Path,
        default=Path("artifacts/battedball_mlp_v1"),
        help="Directory containing model.pt + metadata.json (with feature_scaler).",
    )
    parser.add_argument(
        "--val-season-from",
        type=int,
        default=2024,
        help="First season to fit calibrators on. The decision-[51] norm is "
        "fit on val (NOT train) — the desktop overnight pipeline passes "
        "the held-out 2025 season here.",
    )
    parser.add_argument("--val-season-to", type=int, default=None)
    parser.add_argument(
        "--plots-out-dir",
        type=Path,
        default=None,
        help="Optional: write per-park reliability-diagram PNGs to this directory.",
    )
    args = parser.parse_args()

    season_to = args.val_season_to if args.val_season_to is not None else args.val_season_from
    print(f"loading model from {args.mlp_dir}")
    raw_probs, labels, park_order = _forward_all(
        args.mlp_dir,
        season_from=args.val_season_from,
        season_to=season_to,
    )
    print(f"  val rows: {raw_probs.shape[0]} BIPs across {raw_probs.shape[1]} parks")

    print("fitting per-park calibrators...")
    cals = fit_per_park_calibrators(
        raw_probs,
        labels,
        park_order=park_order,
        outcome_order=OUTCOME_NAMES,
    )
    calibrated = transform(cals, raw_probs)
    pre = per_park_ece(raw_probs, labels)
    post = per_park_ece(calibrated, labels)
    n_improved = int((post < pre).sum())
    print(f"  per-park ECE pre  : mean={pre.mean():.4f}  max={pre.max():.4f}")
    print(f"  per-park ECE post : mean={post.mean():.4f}  max={post.max():.4f}")
    print(f"  parks improved    : {n_improved}/{len(park_order)}")

    calibrator_path = args.mlp_dir / "calibrator.json"
    save_calibrator(cals, calibrator_path)
    print(f"wrote -> {calibrator_path}")

    # Persist the calibrated outcome-calibration metrics (decision [141]): these
    # back the BLOCKING registration gate (per-park ECE post-cal < 0.05 AND
    # aggregate test ECE < 0.02). The 2c.9 comparison evaluates the MLP on raw
    # softmax, so its ECE is pre-cal — this file is the authoritative post-cal
    # source. `aggregate_ece_post` pools all rows x parks per class on the
    # calibrated probs and averages across classes.
    n_outcomes = calibrated.shape[2]
    aggregate_ece_post = float(
        np.mean(
            [
                expected_calibration_error(calibrated[:, :, c].ravel(), labels[:, :, c].ravel())
                for c in range(n_outcomes)
            ]
        )
    )
    metrics = {
        "schema_version": 1,
        "per_park_ece_post_mean": float(post.mean()),
        "per_park_ece_post_max": float(post.max()),
        "per_park_ece_pre_mean": float(pre.mean()),
        "aggregate_ece_post": aggregate_ece_post,
        "parks_improved": n_improved,
        "n_parks": len(park_order),
        "n_val_rows": int(calibrated.shape[0]),
    }
    metrics_path = args.mlp_dir / "calibration_metrics.json"
    metrics_path.write_text(json.dumps(metrics, indent=2) + "\n")
    print(f"wrote -> {metrics_path}  (aggregate ECE post = {aggregate_ece_post:.4f})")

    if args.plots_out_dir is not None:
        # Local import — matplotlib pulls in a non-trivial dependency tree
        # that callers don't need if they're only fitting the calibrators.
        from bullpen_training.battedball.mlp.reliability_plots import plot_all_parks

        print(f"writing {len(park_order)} reliability-diagram PNGs to {args.plots_out_dir}")
        written = plot_all_parks(raw_probs, labels, cals, out_dir=args.plots_out_dir)
        print(f"  wrote {len(written)} PNGs")


if __name__ == "__main__":
    main()
