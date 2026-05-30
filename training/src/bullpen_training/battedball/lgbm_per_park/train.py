"""Per-park LightGBM trainer: 30 independent boosters, one per MLB park.

Each model trains only on BIPs that occurred at its park, using the
15 launch/game-state features (no park_id feature — it's always the
same park). Per-class isotonic calibration follows the same pattern
as the global LightGBM baseline (2c.8).

CLI:

  uv run python -m bullpen_training.battedball.lgbm_per_park.train \
      --train-season-from 2024 --train-season-to 2024 \
      --out-dir artifacts/battedball_lgbm_per_park_v1
"""

from __future__ import annotations

import argparse
import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Final

import lightgbm as lgb
import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression

from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.battedball.lgbm_per_park.dataset import (
    FEATURE_COLUMNS,
    LABEL_COLUMN,
    load_park_lgbm_dataset,
)
from bullpen_training.battedball.parks.loader import load_all_parks

DEFAULT_LR: Final[float] = 0.05
DEFAULT_NUM_LEAVES: Final[int] = 63
DEFAULT_NUM_BOOST_ROUND: Final[int] = 2000
DEFAULT_EARLY_STOPPING: Final[int] = 50
DEFAULT_SEED: Final[int] = 42


@dataclass
class LgbmPerParkBundle:
    park_id: str
    booster: lgb.Booster
    calibrators: list[IsotonicRegression]
    feature_columns: tuple[str, ...]
    outcome_names: tuple[str, ...]
    train_summary: dict[str, object]


def train_single_park_lgbm(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame | None = None,
    *,
    park_id: str,
    lr: float = DEFAULT_LR,
    num_leaves: int = DEFAULT_NUM_LEAVES,
    num_boost_round: int = DEFAULT_NUM_BOOST_ROUND,
    early_stopping: int = DEFAULT_EARLY_STOPPING,
    seed: int = DEFAULT_SEED,
    verbose_eval: int = 0,
) -> LgbmPerParkBundle:
    lgb.register_logger(logging.getLogger("lightgbm"))

    params = dict(
        objective="multiclass",
        num_class=len(OUTCOME_NAMES),
        metric="multi_logloss",
        learning_rate=lr,
        num_leaves=num_leaves,
        seed=seed,
        deterministic=True,
        force_row_wise=True,
        verbose=-1,
    )

    train_x = train_df[list(FEATURE_COLUMNS)]
    train_y = train_df[LABEL_COLUMN].astype(int)
    dtrain = lgb.Dataset(train_x, label=train_y)
    valid_sets = [dtrain]
    valid_names = ["train"]
    dval = None
    if val_df is not None and not val_df.empty:
        val_x = val_df[list(FEATURE_COLUMNS)]
        val_y = val_df[LABEL_COLUMN].astype(int)
        dval = lgb.Dataset(val_x, label=val_y, reference=dtrain)
        valid_sets.append(dval)
        valid_names.append("val")

    callbacks: list = []
    if dval is not None and early_stopping > 0:
        callbacks.append(lgb.early_stopping(early_stopping, first_metric_only=True, verbose=False))
    if verbose_eval > 0:
        callbacks.append(lgb.log_evaluation(verbose_eval))

    t0 = time.perf_counter()
    booster = lgb.train(
        params,
        dtrain,
        num_boost_round=num_boost_round,
        valid_sets=valid_sets,
        valid_names=valid_names,
        callbacks=callbacks,
    )
    elapsed = time.perf_counter() - t0

    if val_df is not None and not val_df.empty:
        cal_source = "val"
        cal_x = val_df[list(FEATURE_COLUMNS)]
        cal_y = val_df[LABEL_COLUMN].astype(int)
    else:
        cal_source = "train"
        cal_x = train_x
        cal_y = train_y
    raw_preds = np.asarray(booster.predict(cal_x), dtype=np.float64)
    calibrators: list[IsotonicRegression] = []
    for c in range(len(OUTCOME_NAMES)):
        iso = IsotonicRegression(out_of_bounds="clip", y_min=0.0, y_max=1.0)
        iso.fit(raw_preds[:, c], (cal_y == c).astype(np.float64))
        calibrators.append(iso)

    summary: dict[str, object] = {
        "park_id": park_id,
        "n_train_rows": len(train_df),
        "n_val_rows": int(len(val_df) if val_df is not None else 0),
        "num_boost_round": int(booster.current_iteration()),
        "best_iteration": int(booster.best_iteration or booster.current_iteration()),
        "elapsed_sec": float(elapsed),
        "calibration_source": cal_source,
    }
    return LgbmPerParkBundle(
        park_id=park_id,
        booster=booster,
        calibrators=calibrators,
        feature_columns=FEATURE_COLUMNS,
        outcome_names=tuple(OUTCOME_NAMES),
        train_summary=summary,
    )


def predict_proba(bundle: LgbmPerParkBundle, df: pd.DataFrame) -> np.ndarray:
    x = df[list(bundle.feature_columns)]
    return np.asarray(bundle.booster.predict(x), dtype=np.float32)


def predict_proba_calibrated(bundle: LgbmPerParkBundle, df: pd.DataFrame) -> np.ndarray:
    raw = predict_proba(bundle, df).astype(np.float64)
    calibrated = np.empty_like(raw)
    for c in range(raw.shape[1]):
        calibrated[:, c] = bundle.calibrators[c].transform(raw[:, c])
    calibrated = np.maximum(calibrated, 1e-9)
    return (calibrated / calibrated.sum(axis=-1, keepdims=True)).astype(np.float32)


def _calibrator_to_dict(iso: IsotonicRegression, outcome_name: str) -> dict:
    return {
        "outcome": outcome_name,
        "x_thresholds": iso.X_thresholds_.astype(float).tolist(),
        "y_thresholds": iso.y_thresholds_.astype(float).tolist(),
        "y_min": float(iso.y_min) if iso.y_min is not None else None,
        "y_max": float(iso.y_max) if iso.y_max is not None else None,
        "out_of_bounds": iso.out_of_bounds,
    }


def _calibrator_from_dict(d: dict) -> IsotonicRegression:
    iso = IsotonicRegression(
        out_of_bounds=d.get("out_of_bounds", "clip"),
        y_min=d.get("y_min"),
        y_max=d.get("y_max"),
    )
    iso.X_thresholds_ = np.asarray(d["x_thresholds"], dtype=np.float64)
    iso.y_thresholds_ = np.asarray(d["y_thresholds"], dtype=np.float64)
    iso.X_min_ = float(iso.X_thresholds_.min()) if iso.X_thresholds_.size else 0.0
    iso.X_max_ = float(iso.X_thresholds_.max()) if iso.X_thresholds_.size else 1.0
    iso.increasing_ = True
    iso._build_f(iso.X_thresholds_, iso.y_thresholds_)
    return iso


def save_per_park_bundle(bundle: LgbmPerParkBundle, out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    bundle.booster.save_model(str(out_dir / "model.txt"))
    (out_dir / "calibrator.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "park_id": bundle.park_id,
                "outcome_order": list(bundle.outcome_names),
                "classes": [
                    _calibrator_to_dict(iso, name)
                    for iso, name in zip(bundle.calibrators, bundle.outcome_names, strict=True)
                ],
            },
            indent=2,
        )
    )
    (out_dir / "metadata.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "model_name": "battedball_lgbm_per_park",
                "model_version": "v1",
                "framework": "lightgbm",
                "park_id": bundle.park_id,
                "feature_columns": list(bundle.feature_columns),
                "outcome_names": list(bundle.outcome_names),
                "train_summary": bundle.train_summary,
            },
            indent=2,
        )
    )


def load_per_park_bundle(park_dir: Path) -> LgbmPerParkBundle:
    booster = lgb.Booster(model_file=str(park_dir / "model.txt"))
    cal_payload = json.loads((park_dir / "calibrator.json").read_text())
    calibrators = [_calibrator_from_dict(c) for c in cal_payload["classes"]]
    md = json.loads((park_dir / "metadata.json").read_text())
    return LgbmPerParkBundle(
        park_id=md["park_id"],
        booster=booster,
        calibrators=calibrators,
        feature_columns=tuple(md["feature_columns"]),
        outcome_names=tuple(md["outcome_names"]),
        train_summary=md.get("train_summary", {}),
    )


def train_all_parks(
    *,
    park_ids: tuple[str, ...],
    season_from: int,
    season_to: int,
    val_season: int | None = None,
    limit: int | None = None,
    lr: float = DEFAULT_LR,
    num_leaves: int = DEFAULT_NUM_LEAVES,
    num_boost_round: int = DEFAULT_NUM_BOOST_ROUND,
    early_stopping: int = DEFAULT_EARLY_STOPPING,
    seed: int = DEFAULT_SEED,
    out_dir: Path,
    verbose_eval: int = 0,
) -> list[dict[str, object]]:
    summaries: list[dict[str, object]] = []
    t0_all = time.perf_counter()

    for i, park_id in enumerate(park_ids):
        print(f"\n[{i + 1}/{len(park_ids)}] training {park_id}...")
        train_df = load_park_lgbm_dataset(
            park_id=park_id,
            season_from=season_from,
            season_to=season_to,
            limit=limit,
        )
        print(f"  train: {len(train_df)} rows")

        if train_df.empty:
            print(f"  SKIP: no training data for {park_id}")
            continue

        val_df = None
        if val_season is not None:
            val_df = load_park_lgbm_dataset(
                park_id=park_id,
                season_from=val_season,
                season_to=val_season,
                limit=limit,
            )
            print(f"  val:   {len(val_df)} rows")

        bundle = train_single_park_lgbm(
            train_df,
            val_df,
            park_id=park_id,
            lr=lr,
            num_leaves=num_leaves,
            num_boost_round=num_boost_round,
            early_stopping=early_stopping,
            seed=seed,
            verbose_eval=verbose_eval,
        )
        save_per_park_bundle(bundle, out_dir / park_id)

        print(
            f"  done: {bundle.train_summary['num_boost_round']} rounds, "
            f"elapsed={bundle.train_summary['elapsed_sec']:.1f}s"
        )
        summaries.append(bundle.train_summary)

    elapsed_all = time.perf_counter() - t0_all
    print(f"\n== all parks done in {elapsed_all:.1f}s ==")

    all_meta: dict[str, object] = {
        "schema_version": 1,
        "model_name": "battedball_lgbm_per_park",
        "model_version": "v1",
        "park_ids": list(park_ids),
        "n_parks_trained": len(summaries),
        "total_elapsed_sec": elapsed_all,
    }
    (out_dir / "metadata.json").write_text(json.dumps(all_meta, indent=2))
    return summaries


def main() -> None:
    parser = argparse.ArgumentParser(description="Train 30 per-park LightGBM models.")
    parser.add_argument("--train-season-from", type=int, default=2024)
    parser.add_argument("--train-season-to", type=int, default=2024)
    parser.add_argument("--val-season", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--lr", type=float, default=DEFAULT_LR)
    parser.add_argument("--num-leaves", type=int, default=DEFAULT_NUM_LEAVES)
    parser.add_argument("--num-boost-round", type=int, default=DEFAULT_NUM_BOOST_ROUND)
    parser.add_argument("--early-stopping", type=int, default=DEFAULT_EARLY_STOPPING)
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("artifacts/battedball_lgbm_per_park_v1"),
    )
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--verbose-eval", type=int, default=0)
    parser.add_argument(
        "--parks",
        nargs="*",
        default=None,
        help="Subset of park IDs to train (default: all 30).",
    )
    args = parser.parse_args()

    park_ids = tuple(sorted(args.parks)) if args.parks else tuple(sorted(load_all_parks().keys()))

    print(
        f"training {len(park_ids)} per-park LightGBM models "
        f"(seasons {args.train_season_from}-{args.train_season_to})"
    )

    train_all_parks(
        park_ids=park_ids,
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        val_season=args.val_season,
        limit=args.limit,
        lr=args.lr,
        num_leaves=args.num_leaves,
        num_boost_round=args.num_boost_round,
        early_stopping=args.early_stopping,
        seed=args.seed,
        out_dir=args.out_dir,
        verbose_eval=args.verbose_eval,
    )


if __name__ == "__main__":
    main()


__all__ = (
    "LgbmPerParkBundle",
    "load_per_park_bundle",
    "predict_proba",
    "predict_proba_calibrated",
    "save_per_park_bundle",
    "train_all_parks",
    "train_single_park_lgbm",
)
