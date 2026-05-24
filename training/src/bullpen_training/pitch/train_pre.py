"""Pre-pitch outcome model (Phase 2a.5).

LightGBM 5-class multinomial trained on Tier 1+2+3 features (see
`bullpen_training.pitch.PITCH_FEATURE_COLUMNS`), with one-vs-rest
isotonic calibration fit on the fold's validation year.

The harness in 2a.4 drives the 4-fold CV; this module supplies the
model_factory + the feature_loader callbacks. Phase 2 exit criterion
is ECE < 0.02 per model on the test split — see `metrics.expected_calibration_error`.

Deterministic per machine (CLAUDE.md rule on `force_row_wise=True` +
`deterministic=True`); cross-machine bit-identical is NOT guaranteed
(LightGBM caveat documented in the leaf).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import cast

import lightgbm as lgb
import numpy as np
import pandas as pd
from clickhouse_driver import Client

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS
from bullpen_training.pitch.isotonic import IsotonicCalibrator

log = logging.getLogger(__name__)

DEFAULT_SEED = 42

_LABEL_TO_INT: dict[str, int] = {cls: i for i, cls in enumerate(LABEL_CLASSES)}


def _label_to_int(series: pd.Series) -> pd.Series:
    return cast(pd.Series, series.map(_LABEL_TO_INT).astype("int8"))


def _stand_to_int(s: pd.Series) -> pd.Series:
    return cast(pd.Series, s.fillna("R").map({"L": 0, "R": 1}).astype("int8"))


def _throws_to_int(s: pd.Series) -> pd.Series:
    return cast(pd.Series, s.fillna("R").map({"L": 0, "R": 1}).astype("int8"))


def _park_to_int(s: pd.Series) -> pd.Series:
    # LightGBM categorical handler wants ints. Use category codes.
    return cast(pd.Series, s.astype("category").cat.codes.astype("int16"))


# ---------------------------------------------------------------------------
# Feature loader: reads from the `features` table per fold and year span
# ---------------------------------------------------------------------------


def make_feature_loader(
    settings: ClickHouseSettings | None = None,
) -> FeatureLoaderClosure:
    """Build a feature_loader bound to a ClickHouse connection.

    Returned callable signature: `(start_year, end_year, fold_id) -> DataFrame`
    with `label` column (integer-encoded) and `PITCH_FEATURE_COLUMNS`.
    """
    client = make_client(settings)
    return FeatureLoaderClosure(client)


@dataclass
class FeatureLoaderClosure:
    client: Client

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        start = date(start_year, 1, 1)
        end = date(end_year, 12, 31)
        # Select Tier 1+2+3 columns + label
        select_cols = [
            "count_balls",
            "count_strikes",
            "outs",
            "inning",
            "base_state",
            "score_diff",
            "dow",
            "pitcher_throws",
            "batter_stand",
            "park_id",
            "pitcher_te_ball",
            "pitcher_te_called_strike",
            "pitcher_te_swinging_strike",
            "pitcher_te_foul",
            "pitcher_te_in_play",
            "batter_te_ball",
            "batter_te_called_strike",
            "batter_te_swinging_strike",
            "batter_te_foul",
            "batter_te_in_play",
            "pitcher_pitches_last_28d",
            "pitcher_pitches_in_game",
            "days_since_last_appearance",
            "pitcher_strike_rate_28d",
            "pitcher_swstrike_rate_28d",
            "pitcher_inplay_rate_28d",
            "pitcher_strike_rate_std",
            "batter_strike_rate_28d",
            "batter_inplay_rate_28d",
            "batter_ball_rate_28d",
            "batter_inplay_rate_std",
            "toString(label) AS label",
        ]
        sql = (
            f"SELECT {', '.join(select_cols)} FROM features FINAL "
            f"WHERE fold = {fold_id} AND game_date BETWEEN '{start}' AND '{end}' "
            "ORDER BY game_date, game_id, at_bat_index, pitch_number"
        )
        rows = self.client.execute(sql)
        col_names = [
            "count_balls",
            "count_strikes",
            "outs",
            "inning",
            "base_state",
            "score_diff",
            "dow",
            "pitcher_throws",
            "batter_stand",
            "park_id",
            "pitcher_te_ball",
            "pitcher_te_called_strike",
            "pitcher_te_swinging_strike",
            "pitcher_te_foul",
            "pitcher_te_in_play",
            "batter_te_ball",
            "batter_te_called_strike",
            "batter_te_swinging_strike",
            "batter_te_foul",
            "batter_te_in_play",
            "pitcher_pitches_last_28d",
            "pitcher_pitches_in_game",
            "days_since_last_appearance",
            "pitcher_strike_rate_28d",
            "pitcher_swstrike_rate_28d",
            "pitcher_inplay_rate_28d",
            "pitcher_strike_rate_std",
            "batter_strike_rate_28d",
            "batter_inplay_rate_28d",
            "batter_ball_rate_28d",
            "batter_inplay_rate_std",
            "label",
        ]
        df = pd.DataFrame(rows, columns=col_names)
        # Encode categoricals as ints (LightGBM-ready)
        df["pitcher_throws_int"] = _throws_to_int(cast(pd.Series, df["pitcher_throws"]))
        df["batter_stand_int"] = _stand_to_int(cast(pd.Series, df["batter_stand"]))
        df["park_id_int"] = _park_to_int(cast(pd.Series, df["park_id"]))
        df["label"] = _label_to_int(cast(pd.Series, df["label"]))
        # Project to canonical columns
        keep = [*PITCH_FEATURE_COLUMNS, "label"]
        return cast(pd.DataFrame, df[keep])


# ---------------------------------------------------------------------------
# Model factory: LightGBM 5-class multinomial + isotonic per class
# ---------------------------------------------------------------------------


@dataclass
class ModelBundle:
    booster: lgb.Booster
    calibrator: IsotonicCalibrator
    feature_cols: tuple[str, ...]

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = cast(np.ndarray, self.booster.predict(X[list(self.feature_cols)]))
        return self.calibrator.transform(raw)


def model_factory(
    train_df: pd.DataFrame,
    val_df: pd.DataFrame,
    *,
    seed: int = DEFAULT_SEED,
    num_boost_round: int = 2000,
    early_stopping_rounds: int = 50,
    log_evaluation_period: int = 100,
) -> ModelBundle:
    """Train a LightGBM multinomial then fit isotonic calibration on val_df."""
    params = {
        "objective": "multiclass",
        "num_class": len(LABEL_CLASSES),
        "metric": "multi_logloss",
        "learning_rate": 0.05,
        "num_leaves": 63,
        "min_data_in_leaf": 200,
        "feature_fraction": 0.8,
        "bagging_fraction": 0.8,
        "bagging_freq": 5,
        "seed": seed,
        "deterministic": True,
        "force_row_wise": True,
        "verbosity": -1,
    }
    feat_cols = list(PITCH_FEATURE_COLUMNS)
    dtrain = lgb.Dataset(train_df[feat_cols], label=train_df["label"])
    dval = lgb.Dataset(val_df[feat_cols], label=val_df["label"], reference=dtrain)
    booster = cast(
        lgb.Booster,
        lgb.train(
            params,
            dtrain,
            num_boost_round=num_boost_round,
            valid_sets=[dtrain, dval],
            valid_names=["train", "val"],
            callbacks=[
                lgb.early_stopping(early_stopping_rounds, verbose=False),
                lgb.log_evaluation(log_evaluation_period),
            ],
        ),
    )

    val_pred = cast(np.ndarray, booster.predict(val_df[feat_cols]))
    calibrator = IsotonicCalibrator.fit(
        np.asarray(val_df["label"], dtype=np.int64),
        val_pred,
        class_labels=LABEL_CLASSES,
    )
    return ModelBundle(booster=booster, calibrator=calibrator, feature_cols=tuple(feat_cols))
