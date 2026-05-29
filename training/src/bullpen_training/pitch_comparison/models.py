"""Four model architectures for pre-pitch multi-target prediction.

Each model predicts 4 targets from the same feature set:
  1. pitch_type (8-class classification)
  2. release_speed_mph (regression)
  3. pitch_outcome (6-class classification)
  4. ab_total_pitches (regression)
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Any

import lightgbm as lgb
import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.nn.functional as F
import xgboost as xgb
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.preprocessing import StandardScaler
from torch.utils.data import DataLoader, Dataset

from bullpen_training.pitch_comparison.data import (
    FEATURE_COLS,
    OUTCOME_CLASSES,
    PITCH_TYPE_CLASSES,
)

log = logging.getLogger(__name__)


@dataclass
class PredictionBundle:
    """Predictions from one model on one dataset."""

    pitch_type_proba: np.ndarray   # (N, 8)
    velocity: np.ndarray           # (N,)
    outcome_proba: np.ndarray      # (N, 6)
    ab_pitch_count: np.ndarray     # (N,)
    elapsed_train_sec: float


# --- 1. LightGBM ensemble ---------------------------------------------------


def train_lgbm(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = 42,
) -> tuple[dict[str, Any], float]:
    lgb.register_logger(logging.getLogger("lightgbm"))
    feat = list(FEATURE_COLS)
    t0 = time.perf_counter()

    base_params = dict(
        learning_rate=0.05, num_leaves=63, seed=seed,
        deterministic=True, force_row_wise=True, verbose=-1,
    )
    has_val = len(val_df) > 0

    def _train_one(params: dict, train_label, val_label) -> lgb.Booster:
        dt = lgb.Dataset(train_df[feat], label=train_label)
        valid_sets = [dt]
        valid_names = ["t"]
        cbs: list = []
        if has_val:
            dv = lgb.Dataset(
                val_df[feat], label=val_label, reference=dt,
            )
            valid_sets.append(dv)
            valid_names.append("v")
            cbs.append(lgb.early_stopping(
                50, first_metric_only=True, verbose=False,
            ))
        return lgb.train(
            params, dt, 2000,
            valid_sets=valid_sets, valid_names=valid_names,
            callbacks=cbs,
        )

    pt_params = {
        **base_params, "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
        "metric": "multi_logloss",
    }
    pt_model = _train_one(
        pt_params, train_df["pitch_type_int"],
        val_df["pitch_type_int"] if has_val else None,
    )

    velo_params = {
        **base_params, "objective": "regression", "metric": "rmse",
    }
    velo_model = _train_one(
        velo_params, train_df["release_speed_mph"],
        val_df["release_speed_mph"] if has_val else None,
    )

    out_params = {
        **base_params, "objective": "multiclass",
        "num_class": len(OUTCOME_CLASSES),
        "metric": "multi_logloss",
    }
    out_model = _train_one(
        out_params, train_df["outcome_int"],
        val_df["outcome_int"] if has_val else None,
    )

    ab_params = {
        **base_params, "objective": "regression", "metric": "rmse",
    }
    ab_model = _train_one(
        ab_params, train_df["ab_total_pitches"],
        val_df["ab_total_pitches"] if has_val else None,
    )

    elapsed = time.perf_counter() - t0
    bundle = {
        "pitch_type": pt_model, "velocity": velo_model,
        "outcome": out_model, "ab_count": ab_model,
    }
    return bundle, elapsed


def predict_lgbm(bundle: dict[str, Any], df: pd.DataFrame) -> PredictionBundle:
    feat = list(FEATURE_COLS)
    x = df[feat]
    return PredictionBundle(
        pitch_type_proba=np.asarray(bundle["pitch_type"].predict(x), dtype=np.float32),
        velocity=np.asarray(bundle["velocity"].predict(x), dtype=np.float32),
        outcome_proba=np.asarray(bundle["outcome"].predict(x), dtype=np.float32),
        ab_pitch_count=np.asarray(bundle["ab_count"].predict(x), dtype=np.float32),
        elapsed_train_sec=0.0,
    )


# --- 2. Multi-task MLP ------------------------------------------------------


class _PitchDataset(Dataset):
    def __init__(self, features: np.ndarray, targets: dict[str, np.ndarray]) -> None:
        self.features = features.astype(np.float32)
        self.targets = {k: v for k, v in targets.items()}

    def __len__(self) -> int:
        return self.features.shape[0]

    def __getitem__(self, idx: int) -> tuple[np.ndarray, dict[str, np.ndarray]]:
        return self.features[idx], {k: v[idx] for k, v in self.targets.items()}


class MultiTaskMLP(nn.Module):
    def __init__(self, n_features: int, *, hidden: int = 256, dropout: float = 0.15):
        super().__init__()
        self.backbone = nn.Sequential(
            nn.Linear(n_features, hidden), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(hidden, hidden), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(hidden, hidden // 2), nn.ReLU(), nn.Dropout(dropout),
        )
        self.head_pitch_type = nn.Linear(hidden // 2, len(PITCH_TYPE_CLASSES))
        self.head_velocity = nn.Linear(hidden // 2, 1)
        self.head_outcome = nn.Linear(hidden // 2, len(OUTCOME_CLASSES))
        self.head_ab_count = nn.Linear(hidden // 2, 1)

    def forward(self, x: torch.Tensor) -> dict[str, torch.Tensor]:
        h = self.backbone(x)
        return {
            "pitch_type": self.head_pitch_type(h),
            "velocity": self.head_velocity(h).squeeze(-1),
            "outcome": self.head_outcome(h),
            "ab_count": self.head_ab_count(h).squeeze(-1),
        }


def train_mlp(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = 42,
    epochs: int = 15,
    batch_size: int = 4096,
    lr: float = 1e-3,
) -> tuple[MultiTaskMLP, StandardScaler, float]:
    torch.manual_seed(seed)
    feat = list(FEATURE_COLS)

    scaler = StandardScaler()
    train_x = scaler.fit_transform(train_df[feat].values.astype(np.float32))

    train_targets = {
        "pitch_type": train_df["pitch_type_int"].values.astype(np.int64),
        "velocity": train_df["release_speed_mph"].values.astype(np.float32),
        "outcome": train_df["outcome_int"].values.astype(np.int64),
        "ab_count": train_df["ab_total_pitches"].values.astype(np.float32),
    }

    train_ds = _PitchDataset(train_x, train_targets)
    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = MultiTaskMLP(len(feat)).to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    t0 = time.perf_counter()
    for epoch in range(epochs):
        model.train()
        for batch_x, batch_y in train_loader:
            if isinstance(batch_x, np.ndarray):
                batch_x = torch.from_numpy(batch_x).to(device)
            else:
                batch_x = batch_x.to(device)
            preds = model(batch_x)

            loss_pt = F.cross_entropy(
                preds["pitch_type"],
                torch.tensor(batch_y["pitch_type"], dtype=torch.long, device=device),
            )
            loss_velo = F.mse_loss(
                preds["velocity"],
                torch.tensor(batch_y["velocity"], dtype=torch.float32, device=device),
            )
            loss_out = F.cross_entropy(
                preds["outcome"],
                torch.tensor(batch_y["outcome"], dtype=torch.long, device=device),
            )
            loss_ab = F.mse_loss(
                preds["ab_count"],
                torch.tensor(batch_y["ab_count"], dtype=torch.float32, device=device),
            )
            # Weight classification losses more since MSE scale differs.
            loss = loss_pt + 0.01 * loss_velo + loss_out + 0.1 * loss_ab
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
        scheduler.step()

        if (epoch + 1) % 10 == 0:
            print(f"  MLP epoch {epoch + 1}/{epochs} loss={float(loss):.4f}", flush=True)

    elapsed = time.perf_counter() - t0
    return model, scaler, elapsed


def predict_mlp(
    model: MultiTaskMLP, scaler: StandardScaler, df: pd.DataFrame,
) -> PredictionBundle:
    feat = list(FEATURE_COLS)
    x = scaler.transform(df[feat].values.astype(np.float32))
    device = next(model.parameters()).device
    model.eval()
    with torch.no_grad():
        preds = model(torch.from_numpy(x).to(device))
    return PredictionBundle(
        pitch_type_proba=F.softmax(preds["pitch_type"], dim=-1).cpu().numpy(),
        velocity=preds["velocity"].cpu().numpy(),
        outcome_proba=F.softmax(preds["outcome"], dim=-1).cpu().numpy(),
        ab_pitch_count=preds["ab_count"].cpu().numpy(),
        elapsed_train_sec=0.0,
    )


# --- 3. XGBoost ensemble ----------------------------------------------------


def train_xgb(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = 42,
) -> tuple[dict[str, Any], float]:
    feat = list(FEATURE_COLS)
    has_val = len(val_df) > 0
    t0 = time.perf_counter()

    def _fit_xgb(model, train_x, train_y, val_x, val_y):
        fit_kwargs: dict[str, Any] = {"verbose": False}
        if has_val:
            fit_kwargs["eval_set"] = [(val_x, val_y)]
        model.fit(train_x, train_y, **fit_kwargs)
        return model

    pt_model = _fit_xgb(
        xgb.XGBClassifier(
            n_estimators=500, max_depth=6, learning_rate=0.1,
            objective="multi:softprob",
            num_class=len(PITCH_TYPE_CLASSES),
            eval_metric="mlogloss",
            early_stopping_rounds=30 if has_val else None,
            random_state=seed, verbosity=0, tree_method="hist",
        ),
        train_df[feat], train_df["pitch_type_int"],
        val_df[feat] if has_val else None,
        val_df["pitch_type_int"] if has_val else None,
    )

    velo_model = _fit_xgb(
        xgb.XGBRegressor(
            n_estimators=500, max_depth=6, learning_rate=0.1,
            objective="reg:squarederror", eval_metric="rmse",
            early_stopping_rounds=30 if has_val else None,
            random_state=seed, verbosity=0, tree_method="hist",
        ),
        train_df[feat], train_df["release_speed_mph"],
        val_df[feat] if has_val else None,
        val_df["release_speed_mph"] if has_val else None,
    )

    out_model = _fit_xgb(
        xgb.XGBClassifier(
            n_estimators=500, max_depth=6, learning_rate=0.1,
            objective="multi:softprob",
            num_class=len(OUTCOME_CLASSES),
            eval_metric="mlogloss",
            early_stopping_rounds=30 if has_val else None,
            random_state=seed, verbosity=0, tree_method="hist",
        ),
        train_df[feat], train_df["outcome_int"],
        val_df[feat] if has_val else None,
        val_df["outcome_int"] if has_val else None,
    )

    ab_model = _fit_xgb(
        xgb.XGBRegressor(
            n_estimators=500, max_depth=6, learning_rate=0.1,
            objective="reg:squarederror", eval_metric="rmse",
            early_stopping_rounds=30 if has_val else None,
            random_state=seed, verbosity=0, tree_method="hist",
        ),
        train_df[feat], train_df["ab_total_pitches"],
        val_df[feat] if has_val else None,
        val_df["ab_total_pitches"] if has_val else None,
    )

    elapsed = time.perf_counter() - t0
    return {"pitch_type": pt_model, "velocity": velo_model,
            "outcome": out_model, "ab_count": ab_model}, elapsed


def predict_xgb(bundle: dict[str, Any], df: pd.DataFrame) -> PredictionBundle:
    feat = list(FEATURE_COLS)
    x = df[feat]
    return PredictionBundle(
        pitch_type_proba=bundle["pitch_type"].predict_proba(x).astype(np.float32),
        velocity=bundle["velocity"].predict(x).astype(np.float32),
        outcome_proba=bundle["outcome"].predict_proba(x).astype(np.float32),
        ab_pitch_count=bundle["ab_count"].predict(x).astype(np.float32),
        elapsed_train_sec=0.0,
    )


# --- 4. Logistic / Ridge baseline -------------------------------------------


def train_baseline(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = 42,
) -> tuple[dict[str, Any], StandardScaler, float]:
    feat = list(FEATURE_COLS)
    t0 = time.perf_counter()

    scaler = StandardScaler()
    train_x = scaler.fit_transform(
        train_df[feat].values.astype(np.float64)
    )
    nan_mask = np.isnan(train_x)
    if nan_mask.any():
        col_means = np.nanmean(train_x, axis=0)
        inds = np.where(nan_mask)
        train_x[inds] = np.take(col_means, inds[1])

    # Pitch type.
    pt_model = LogisticRegression(
        max_iter=500, solver="lbfgs", random_state=seed,
    )
    pt_model.fit(train_x, train_df["pitch_type_int"])

    # Velocity.
    velo_model = Ridge(alpha=1.0, random_state=seed)
    velo_model.fit(train_x, train_df["release_speed_mph"])

    # Outcome.
    out_model = LogisticRegression(
        max_iter=500, solver="lbfgs", random_state=seed,
    )
    out_model.fit(train_x, train_df["outcome_int"])

    # AB pitch count.
    ab_model = Ridge(alpha=1.0, random_state=seed)
    ab_model.fit(train_x, train_df["ab_total_pitches"])

    elapsed = time.perf_counter() - t0
    return {"pitch_type": pt_model, "velocity": velo_model,
            "outcome": out_model, "ab_count": ab_model}, scaler, elapsed


def predict_baseline(
    bundle: dict[str, Any], scaler: StandardScaler, df: pd.DataFrame,
) -> PredictionBundle:
    feat = list(FEATURE_COLS)
    x = scaler.transform(df[feat].values.astype(np.float64))
    nan_mask = np.isnan(x)
    if nan_mask.any():
        col_means = np.nanmean(x, axis=0)
        inds = np.where(nan_mask)
        x[inds] = np.take(col_means, inds[1])
    return PredictionBundle(
        pitch_type_proba=bundle["pitch_type"].predict_proba(x).astype(np.float32),
        velocity=bundle["velocity"].predict(x).astype(np.float32),
        outcome_proba=bundle["outcome"].predict_proba(x).astype(np.float32),
        ab_pitch_count=bundle["ab_count"].predict(x).astype(np.float32),
        elapsed_train_sec=0.0,
    )


__all__ = (
    "MultiTaskMLP",
    "PredictionBundle",
    "predict_baseline",
    "predict_lgbm",
    "predict_mlp",
    "predict_xgb",
    "train_baseline",
    "train_lgbm",
    "train_mlp",
    "train_xgb",
)
