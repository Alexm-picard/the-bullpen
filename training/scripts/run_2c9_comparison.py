"""Run the 2c.9 MLP-vs-LGBM comparison against whatever model artifacts
live under ``artifacts/``.

Reads:
  - artifacts/battedball_mlp_v1/{model.pt,metadata.json,calibrator.json?}
  - artifacts/batted_ball_lgbm_baseline/v1/{model.txt,calibrator.json,metadata.json}

Writes:
  - data/eval/batted_ball_comparison_v1.json
  - data/eval/batted_ball_comparison_v1.html

Same data flow the production overnight run will use; usable today on
the 987-BIP smoke models (the result is noisy but exercises the
end-to-end pipeline).
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

# Importing lightgbm BEFORE torch dodges the macOS dual-libomp segfault
# that hits if torch's native libs grab libomp first. The CI workflow
# avoids this entirely by splitting the two test suites into separate
# pytest processes; for this script (which legitimately needs both
# models in one process) the import order is the workaround.
import lightgbm  # noqa: F401  -- import-order guard, must come first
import numpy as np
import torch
import torch.nn.functional as F

from bullpen_training.battedball.eval import compare_models, save_report
from bullpen_training.battedball.lgbm_baseline import load_baseline, predict_proba_calibrated
from bullpen_training.battedball.lgbm_baseline.dataset import (
    load_lgbm_dataset,
)
from bullpen_training.battedball.mlp import FeatureScaler
from bullpen_training.battedball.mlp.architecture import build_model
from bullpen_training.battedball.mlp.dataset import OUTCOME_NAMES, load_arrays


def _load_mlp_predictions(
    mlp_dir: Path, park_order: tuple[str, ...], season_from: int, season_to: int
) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """Return (flat_pred, flat_labels, flat_park_ids) from the MLP.

    The MLP emits (N, n_parks, n_outcomes); we flatten to (N*n_parks, n_outcomes)
    and emit park_ids per row so the comparison module can group.
    """
    md = json.loads((mlp_dir / "metadata.json").read_text())
    scaler = FeatureScaler(
        means=np.array(md["feature_scaler"]["means"], dtype=np.float32),
        stds=np.array(md["feature_scaler"]["stds"], dtype=np.float32),
        is_continuous=np.array(md["feature_scaler"]["is_continuous"], dtype=bool),
    )
    model = build_model(n_parks=len(park_order))
    model.load_state_dict(torch.load(mlp_dir / "model.pt", weights_only=True))
    model.eval()

    feat, lab = load_arrays(season_from=season_from, season_to=season_to, park_order=park_order)
    xs = scaler.transform(feat)
    ys = lab
    with torch.no_grad():
        probs = F.softmax(model(torch.from_numpy(xs)), dim=-1).numpy()
    # Flatten (N, n_parks, 5) -> (N*n_parks, 5) row-major
    flat_pred = probs.reshape(-1, probs.shape[-1])
    flat_labels = ys.reshape(-1, ys.shape[-1])
    flat_park_ids: list[str] = []
    for _ in range(probs.shape[0]):
        flat_park_ids.extend(park_order)
    return flat_pred.astype(np.float64), flat_labels.astype(np.float64), flat_park_ids


def _load_lgbm_predictions(
    lgbm_dir: Path, season_from: int, season_to: int, park_order: tuple[str, ...]
) -> tuple[np.ndarray, list[str]]:
    """Return (pred, park_ids) from the trained LGBM baseline, row-aligned to the
    MLP loader's order.

    The LGBM loader pages per-park (PARK-major); the MLP loader is BIP-major in
    park_order. We re-sort the LGBM rows into the SAME (game_date, game_id,
    at_bat_index, pitch_number, park_order-index) enumeration the MLP loader's
    ClickHouse ORDER BY produces, so mlp_pred[i] / lgbm_pred[i] / labels[i] are the
    same (BIP, park) row. game_id/at_bat_index/pitch_number arrive as ints (parsed
    in the loader) so the numeric sort matches ClickHouse's UInt ORDER BY.
    """
    bundle = load_baseline(lgbm_dir)
    df = load_lgbm_dataset(season_from=season_from, season_to=season_to, include_keys=True)
    park_index = {p: i for i, p in enumerate(park_order)}
    df = df.assign(_park_idx=df["park_id"].astype(str).map(park_index.get))
    df = df.sort_values(
        ["game_date", "game_id", "at_bat_index", "pitch_number", "_park_idx"],
        kind="mergesort",
    ).reset_index(drop=True)
    park_ids = df["park_id"].astype(str).tolist()
    pred = predict_proba_calibrated(bundle, df).astype(np.float64)
    return pred, park_ids


def main() -> None:
    parser = argparse.ArgumentParser(description="Run 2c.9 MLP vs LGBM comparison.")
    parser.add_argument("--mlp-dir", type=Path, default=Path("artifacts/battedball_mlp_v1"))
    parser.add_argument(
        "--lgbm-dir", type=Path, default=Path("artifacts/batted_ball_lgbm_baseline/v1")
    )
    parser.add_argument("--season-from", type=int, default=2024)
    parser.add_argument("--season-to", type=int, default=2024)
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("data/eval"),
        help="Output directory for the JSON + HTML artefacts.",
    )
    args = parser.parse_args()

    mlp_md = json.loads((args.mlp_dir / "metadata.json").read_text())
    park_order = tuple(mlp_md["park_order"])
    print(f"comparing {args.mlp_dir} vs {args.lgbm_dir} on {args.season_from}-{args.season_to}")
    print(f"  park_order: {len(park_order)} parks")

    print("  loading MLP predictions...")
    mlp_pred, labels, mlp_park_ids = _load_mlp_predictions(
        args.mlp_dir, park_order, args.season_from, args.season_to
    )
    print(f"    flat shape: {mlp_pred.shape}")

    print("  loading LGBM predictions...")
    lgbm_pred, lgbm_park_ids = _load_lgbm_predictions(
        args.lgbm_dir, args.season_from, args.season_to, park_order
    )
    print(f"    flat shape: {lgbm_pred.shape}")

    # Sanity: row counts + per-row park_id must match. Both feeds cover the same
    # (BIP, park) set; the MLP loader is BIP-major and _load_lgbm_predictions
    # re-sorts the per-park LGBM rows into that same order, so the guard below
    # should hold. If it ever fails, the two enumerations diverged - do NOT
    # weaken the assert, fix the alignment (it would silently mis-pair rows).
    assert mlp_pred.shape[0] == lgbm_pred.shape[0], (
        f"MLP rows {mlp_pred.shape[0]} != LGBM rows {lgbm_pred.shape[0]} - "
        "the two loaders disagree on the (BIP, park) row set."
    )
    assert mlp_park_ids == lgbm_park_ids, "park_id row order differs between loaders"

    report = compare_models(
        mlp_pred_probs=mlp_pred,
        lgbm_pred_probs=lgbm_pred,
        label_distributions=labels,
        park_ids=mlp_park_ids,
        park_order=park_order,
        outcome_order=OUTCOME_NAMES,
    )
    save_report(
        report,
        args.out_dir / "batted_ball_comparison_v1.json",
        args.out_dir / "batted_ball_comparison_v1.html",
    )
    print("== aggregate ==")
    for model, agg in report.aggregate.items():
        print(
            f"  {model:>4}: Brier={agg.mean_brier:.4f}  ECE={agg.mean_ece:.4f}  "
            f"Acc={agg.mean_accuracy:.3f}"
        )
    print(f"  prefer_for_production: {report.prefer_for_production}")
    for r in report.rationale:
        print(f"    {r}")
    print(f"wrote -> {args.out_dir}")


if __name__ == "__main__":
    main()
