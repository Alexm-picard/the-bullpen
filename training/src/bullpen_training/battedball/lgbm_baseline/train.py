"""LightGBM Option-A baseline trainer (Phase 2c.8).

Trains a single LightGBM 5-class booster with ``park_id`` as a
categorical feature (decision [46]), persists the booster + a single
isotonic calibrator + metadata sidecar.

The per-class isotonic here is a sklearn-style one-vs-rest:
``sklearn.isotonic.IsotonicRegression`` per class against the binary
{is-class-c, predicted P(c)}. That mirrors what the production
``IsotonicCalibratorJava`` already implements for the pitch model
(see `backend/.../inference/IsotonicCalibratorJava.java` from 2a.8);
the 2c.6 ``ParkCalibrators`` are the OTHER calibration shape (30
per-park x 5-class) — explicitly NOT what this baseline uses.

CLI:

  uv run python -m bullpen_training.battedball.lgbm_baseline.train \\
      --train-season-from 2024 --train-season-to 2024 \\
      --out-dir artifacts/batted_ball_lgbm_baseline/v1

CI tests stub the ClickHouse path with synthetic DataFrames; production
training requires the 2c.4 backfill complete + runs on the desktop.
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

# Import OUTCOME_NAMES from the torch-free shared module so this trainer
# stays decoupled from torch (see lgbm_baseline.dataset for the reason).
from bullpen_training.battedball.features_shared import OUTCOME_NAMES
from bullpen_training.battedball.lgbm_baseline.dataset import (
    FEATURE_COLUMNS,
    LABEL_COLUMN,
    PARK_FEATURE,
    load_lgbm_dataset,
)
from bullpen_training.eval.calibration import (
    apply_per_class_isotonic,
    fit_isotonic,
    isotonic_from_dict,
    isotonic_to_dict,
)
from bullpen_training.eval.leakage_guards import refuse_holdout

# Defaults from the leaf body.
DEFAULT_LR: Final[float] = 0.05
DEFAULT_NUM_LEAVES: Final[int] = 63
DEFAULT_NUM_BOOST_ROUND: Final[int] = 2000
DEFAULT_EARLY_STOPPING: Final[int] = 50
DEFAULT_SEED: Final[int] = 42


@dataclass
class LgbmBaselineBundle:
    """A trained baseline: the booster, per-class isotonic calibrators,
    the feature order it expects, and the park-id category dictionary
    (so inference can encode park_id back to the integer codes the
    booster trained on)."""

    booster: lgb.Booster
    calibrators: list[IsotonicRegression]
    feature_columns: tuple[str, ...]
    outcome_names: tuple[str, ...]
    park_categories: list[str]
    train_summary: dict[str, object]


# --- core trainer ---------------------------------------------------------


def train_lgbm(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame | None = None,
    *,
    lr: float = DEFAULT_LR,
    num_leaves: int = DEFAULT_NUM_LEAVES,
    num_boost_round: int = DEFAULT_NUM_BOOST_ROUND,
    early_stopping: int = DEFAULT_EARLY_STOPPING,
    seed: int = DEFAULT_SEED,
    verbose_eval: int = 0,
) -> LgbmBaselineBundle:
    """Fit the baseline booster + per-class isotonic calibrator.

    Same pattern as 2a.5's pre-pitch trainer (lightgbm logger routed
    through Python's stderr logger so output flushes promptly under
    `tee` — the bug-fix from 2b.2).
    """
    # Route LightGBM's prints through Python logging so they don't
    # block-buffer when stdout is a pipe (the 2b.2 fix).
    lgb.register_logger(logging.getLogger("lightgbm"))

    if LABEL_COLUMN not in train_df.columns:
        raise ValueError(f"train_df missing '{LABEL_COLUMN}' column")
    missing = [c for c in FEATURE_COLUMNS if c not in train_df.columns]
    if missing:
        raise ValueError(f"train_df missing feature columns: {missing}")

    # Snapshot the park-id category dictionary so loaded models can
    # re-encode park_id strings to the same integer codes the booster
    # learned on.
    park_categories = list(train_df[PARK_FEATURE].cat.categories)

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
    dtrain = lgb.Dataset(train_x, label=train_y, categorical_feature=[PARK_FEATURE])
    valid_sets = [dtrain]
    valid_names = ["train"]
    dval = None
    if val_df is not None and not val_df.empty:
        val_x = val_df[list(FEATURE_COLUMNS)]
        val_y = val_df[LABEL_COLUMN].astype(int)
        dval = lgb.Dataset(val_x, label=val_y, categorical_feature=[PARK_FEATURE], reference=dtrain)
        valid_sets.append(dval)
        valid_names.append("val")

    callbacks = []
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

    # Fit one IsotonicRegression per class against the booster's
    # validation predictions (or train if no val supplied — flag as
    # such in the summary). Each calibrator maps raw P(class=c) -> a
    # calibrated probability; the transform path renormalises across
    # the 5 classes so the output is still a distribution.
    if val_df is not None and not val_df.empty:
        calibration_source = "val"
        cal_x = val_df[list(FEATURE_COLUMNS)]
        cal_y = val_df[LABEL_COLUMN].astype(int)
    else:
        calibration_source = "train"
        cal_x = train_x
        cal_y = train_y
    raw_preds = booster.predict(cal_x)  # (N, 5)
    raw_preds = np.asarray(raw_preds, dtype=np.float64)
    calibrators: list[IsotonicRegression] = []
    for c in range(len(OUTCOME_NAMES)):
        calibrators.append(
            fit_isotonic(raw_preds[:, c], (cal_y == c).astype(np.float64), y_min=0.0, y_max=1.0)
        )

    summary: dict[str, object] = {
        "n_train_rows": len(train_df),
        "n_val_rows": int(len(val_df) if val_df is not None else 0),
        "num_boost_round": int(booster.current_iteration()),
        "best_iteration": int(booster.best_iteration or booster.current_iteration()),
        "elapsed_sec": float(elapsed),
        "calibration_source": calibration_source,
    }
    return LgbmBaselineBundle(
        booster=booster,
        calibrators=calibrators,
        feature_columns=FEATURE_COLUMNS,
        outcome_names=tuple(OUTCOME_NAMES),
        park_categories=park_categories,
        train_summary=summary,
    )


# --- inference ------------------------------------------------------------


def predict_proba(bundle: LgbmBaselineBundle, df: pd.DataFrame) -> np.ndarray:
    """Raw booster softmax. Shape (N, 5)."""
    if PARK_FEATURE in df.columns and not isinstance(df[PARK_FEATURE].dtype, pd.CategoricalDtype):
        # Re-encode using the training-time category dictionary so
        # park ints line up with what the booster learned.
        df = df.copy()
        df[PARK_FEATURE] = pd.Categorical(df[PARK_FEATURE], categories=bundle.park_categories)
    x = df[list(bundle.feature_columns)]
    return np.asarray(bundle.booster.predict(x), dtype=np.float32)


def predict_proba_calibrated(bundle: LgbmBaselineBundle, df: pd.DataFrame) -> np.ndarray:
    """Booster softmax + per-class isotonic + row renormalisation."""
    return apply_per_class_isotonic(bundle.calibrators, predict_proba(bundle, df))


# --- persistence ----------------------------------------------------------


def save_baseline(bundle: LgbmBaselineBundle, out_dir: Path) -> None:
    """Persist the bundle to a directory matching the registry contract.

    Files written:
      - model.txt       — LightGBM native serialisation
      - calibrator.json — per-class isotonic breakpoints
      - metadata.json   — feature ordering, park categories, train summary
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    bundle.booster.save_model(str(out_dir / "model.txt"))
    (out_dir / "calibrator.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "calibrator_name": "battedball_lgbm_baseline_calibrator",
                "calibrator_version": "v1",
                "outcome_order": list(bundle.outcome_names),
                "classes": [
                    isotonic_to_dict(iso, name)
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
                "model_name": "batted_ball_lgbm_baseline",
                "model_version": "v1",
                "framework": "lightgbm",
                "feature_columns": list(bundle.feature_columns),
                "categorical_features": [PARK_FEATURE],
                "outcome_names": list(bundle.outcome_names),
                "park_categories": list(bundle.park_categories),
                "train_summary": bundle.train_summary,
            },
            indent=2,
        )
    )


def load_baseline(out_dir: Path) -> LgbmBaselineBundle:
    """Reverse of :func:`save_baseline`."""
    booster = lgb.Booster(model_file=str(out_dir / "model.txt"))
    cal_payload = json.loads((out_dir / "calibrator.json").read_text())
    calibrators = [isotonic_from_dict(c) for c in cal_payload["classes"]]
    md = json.loads((out_dir / "metadata.json").read_text())
    return LgbmBaselineBundle(
        booster=booster,
        calibrators=calibrators,
        feature_columns=tuple(md["feature_columns"]),
        outcome_names=tuple(md["outcome_names"]),
        park_categories=list(md["park_categories"]),
        train_summary=md.get("train_summary", {}),
    )


# --- main CLI --------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="Train the 2c.8 LightGBM baseline.")
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
        default=Path("artifacts/batted_ball_lgbm_baseline/v1"),
    )
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--verbose-eval", type=int, default=50)
    args = parser.parse_args()
    refuse_holdout(
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        val_season=args.val_season,
    )

    print(f"loading train data ({args.train_season_from}-{args.train_season_to})...")
    train_df = load_lgbm_dataset(
        season_from=args.train_season_from,
        season_to=args.train_season_to,
        limit=args.limit,
    )
    print(f"  train: {len(train_df)} rows ({len(train_df[PARK_FEATURE].cat.categories)} parks)")
    val_df = None
    if args.val_season is not None:
        val_df = load_lgbm_dataset(
            season_from=args.val_season, season_to=args.val_season, limit=args.limit
        )
        print(f"  val:   {len(val_df)} rows")

    bundle = train_lgbm(
        train_df,
        val_df,
        lr=args.lr,
        num_leaves=args.num_leaves,
        num_boost_round=args.num_boost_round,
        early_stopping=args.early_stopping,
        seed=args.seed,
        verbose_eval=args.verbose_eval,
    )
    save_baseline(bundle, args.out_dir)
    print("== summary ==")
    for k, v in bundle.train_summary.items():
        if isinstance(v, float):
            print(f"  {k}: {v:.4f}")
        else:
            print(f"  {k}: {v}")
    print(f"  wrote -> {args.out_dir}")


if __name__ == "__main__":
    main()


__all__ = (
    "LgbmBaselineBundle",
    "load_baseline",
    "predict_proba",
    "predict_proba_calibrated",
    "save_baseline",
    "train_lgbm",
)
