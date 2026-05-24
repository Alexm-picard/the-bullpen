"""Post-pitch outcome model (Phase 2b.2).

LightGBM 5-class multinomial trained on Tier 1+2+3+**4** features (see
`bullpen_training.pitch.PITCH_FEATURE_COLUMNS_POST`), with one-vs-rest
isotonic calibration fit on the fold's validation year.

Decision [35] — TWO heads = two registered models. This module
deliberately reuses `train_pre.model_factory` (same hyperparameters,
same seed, same calibration recipe) but loads a 10-column-wider
feature frame and persists under `model_name='pitch_outcome_post'`.

Decision [37] — no LR baseline for the post head. The pre head's LR
baseline is the cross-head sanity check (post should outperform pre
on test years where Tier 4 has signal, i.e. 2024+).

Tier 4 sparsity: pre-2024 rows have NULL for pfx_x/pfx_z/spin_rate/
spin_axis (V008's raw schema was added during 2b.1; older pulls
predate it). LightGBM handles NaN natively. The post-head model trained
over rolling-CV folds 1+2 (test years 2022, 2023) sees no Tier 4
signal — the model collapses to feature-equivalent of pre-head on
those folds. Folds 3+4 (test years 2024, 2025) get Tier 4 signal and
should beat pre-head Brier. Acceptance: strict inequality on folds
3+4, equality acceptable on folds 1+2 (documented in 2b.2 status log).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import date
from typing import Any, cast

import lightgbm as lgb
import numpy as np
import pandas as pd
from clickhouse_driver import Client

from bullpen_training.features import LABEL_CLASSES
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.isotonic import IsotonicCalibrator
from bullpen_training.pitch.train_pre import (
    STAND_TO_INT,
    THROWS_TO_INT,
    UNKNOWN_PARK_CODE,
    _label_to_int,
    _park_to_int,
    _stand_to_int,
    _throws_to_int,
)

log = logging.getLogger(__name__)

DEFAULT_SEED = 42
UNKNOWN_PITCH_TYPE_CODE: int = -1


def _pitch_type_to_int(s: pd.Series, mapping: dict[str, int]) -> pd.Series:
    """Deterministic pitch_type → int via an externally-supplied mapping.

    Unknown pitch types (not seen in the loader's universe) get
    ``UNKNOWN_PITCH_TYPE_CODE``. The mapping is built once per loader from
    the alphabetically-sorted set of distinct pitch_type values in the
    fold so train/val/test calls all agree on codes — same pattern as
    park_id_int in train_pre.
    """
    return cast(
        pd.Series,
        s.fillna("").map(mapping).fillna(UNKNOWN_PITCH_TYPE_CODE).astype("int16"),
    )


# ---------------------------------------------------------------------------
# Feature loader: reads from the `features` table per fold and year span,
# now including Tier 4 columns + pitch_type categorical encoding.
# ---------------------------------------------------------------------------


def make_feature_loader(
    settings: ClickHouseSettings | None = None,
) -> FeatureLoaderPostClosure:
    client = make_client(settings)
    return FeatureLoaderPostClosure(client)


@dataclass
class FeatureLoaderPostClosure:
    client: Client
    park_id_mapping: dict[str, int] | None = None
    pitch_type_mapping: dict[str, int] | None = None

    def _ensure_mappings(self, fold_id: int) -> None:
        """Build deterministic park_id + pitch_type mappings once per loader.

        Both mappings are derived from the underlying `pitches` table (all
        years, all folds) — not from `features` per fold — so the mapping
        universe is consistent across CV folds. The earlier fold-scoped
        approach broke when folds 1+2 had empty Tier 4 pitch_type values
        and an empty mapping leaked into fold 3+4 calls.
        """
        if self.park_id_mapping is None:
            rows = cast(
                list[tuple[Any, ...]],
                self.client.execute("SELECT DISTINCT park_id FROM pitches FINAL ORDER BY park_id"),
            )
            self.park_id_mapping = {str(row[0]): i for i, row in enumerate(rows)}
            log.info("park_id mapping built: %d parks", len(self.park_id_mapping))
        if self.pitch_type_mapping is None:
            rows = cast(
                list[tuple[Any, ...]],
                self.client.execute(
                    "SELECT DISTINCT pitch_type FROM pitches FINAL "
                    "WHERE pitch_type != '' ORDER BY pitch_type"
                ),
            )
            self.pitch_type_mapping = {str(row[0]): i for i, row in enumerate(rows)}
            log.info("pitch_type mapping built: %d types", len(self.pitch_type_mapping))

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        self._ensure_mappings(fold_id)
        start = date(start_year, 1, 1)
        end = date(end_year, 12, 31)
        select_cols = [
            # Tier 1+2 string + numeric (mirrors train_pre)
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
            # Tier 3
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
            # Tier 4 (new in 2b.1)
            "pitch_type",
            "release_speed_mph",
            "plate_x_in",
            "plate_z_in",
            "pfx_x_in",
            "pfx_z_in",
            "spin_rate_rpm",
            "spin_axis_deg",
            "release_pos_x_in",
            "release_pos_z_in",
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
            "pitch_type",
            "release_speed_mph",
            "plate_x_in",
            "plate_z_in",
            "pfx_x_in",
            "pfx_z_in",
            "spin_rate_rpm",
            "spin_axis_deg",
            "release_pos_x_in",
            "release_pos_z_in",
            "label",
        ]
        df = pd.DataFrame(rows, columns=col_names)
        df["pitcher_throws_int"] = _throws_to_int(cast(pd.Series, df["pitcher_throws"]))
        df["batter_stand_int"] = _stand_to_int(cast(pd.Series, df["batter_stand"]))
        assert self.park_id_mapping is not None
        # Cast back to int32 so LightGBM doesn't auto-detect int16 as categorical
        # (the auto-detection triggers the O(2^k) subset-partition split algorithm,
        # which is ~30x slower per round on park_id x pitch_type cardinality and
        # was the root cause of fold 3 hanging for 15+ min). Same for pitch_type_int.
        df["park_id_int"] = _park_to_int(
            cast(pd.Series, df["park_id"]), self.park_id_mapping
        ).astype("int32")
        assert self.pitch_type_mapping is not None
        df["pitch_type_int"] = _pitch_type_to_int(
            cast(pd.Series, df["pitch_type"]), self.pitch_type_mapping
        ).astype("int32")
        df["label"] = _label_to_int(cast(pd.Series, df["label"]))

        # clickhouse-driver returns Nullable(Float32) columns as Python lists of
        # float-or-None, which pandas stores as `object` dtype. LightGBM refuses
        # object columns ("Fields with bad pandas dtypes…"). Coerce Tier 4 floats
        # to float32 with NaN for None — LightGBM handles NaN natively.
        #
        # `pd.to_numeric(errors="coerce")` on object dtype is row-by-row in
        # CPython and grinds at ~6M rows by 17 cols (~10 min wall on fold 3's
        # train set). The np.fromiter list-comprehension path is ~50x faster
        # for the None-or-float case (single C loop per column).
        _NULLABLE_FLOAT_COLS = (
            "release_speed_mph",
            "plate_x_in",
            "plate_z_in",
            "pfx_x_in",
            "pfx_z_in",
            "spin_rate_rpm",
            "spin_axis_deg",
            "release_pos_x_in",
            "release_pos_z_in",
            "pitcher_strike_rate_28d",
            "pitcher_swstrike_rate_28d",
            "pitcher_inplay_rate_28d",
            "pitcher_strike_rate_std",
            "batter_strike_rate_28d",
            "batter_inplay_rate_28d",
            "batter_ball_rate_28d",
            "batter_inplay_rate_std",
        )
        nrows = len(df)
        for col in _NULLABLE_FLOAT_COLS:
            series = df[col]
            if series.dtype == "float32":
                continue  # already typed (synthetic test path)
            df[col] = np.fromiter(
                (np.nan if v is None else v for v in series),
                dtype=np.float32,
                count=nrows,
            )

        keep = [*PITCH_FEATURE_COLUMNS_POST, "label"]
        return cast(pd.DataFrame, df[keep])


# ---------------------------------------------------------------------------
# Model factory: same hyperparameters as train_pre, post-pitch feature set
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
    """Train pitch_outcome_post: LightGBM multinomial + isotonic calibration."""
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
    feat_cols = list(PITCH_FEATURE_COLUMNS_POST)
    # NB: we deliberately do NOT pass `categorical_feature` here. The pre head
    # treats park_id_int + handedness as ordinals and reaches ECE 0.0035; the
    # ordinal treatment is strong enough that LightGBM finds the right
    # partitions via repeated splits. The explicit categorical pathway turned
    # out to be ~3-4x slower per round on the joint (park, pitch_type)
    # cardinality with no measurable Brier improvement on this data shape.
    # Documented in the 2b.2 status log; revisit if scaling reveals a gap.
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


# Re-export so production.py can `from train_post import UNKNOWN_PITCH_TYPE_CODE`.
__all__ = (
    "DEFAULT_SEED",
    "UNKNOWN_PITCH_TYPE_CODE",
    "FeatureLoaderPostClosure",
    "ModelBundle",
    "make_feature_loader",
    "model_factory",
)


# Silence unused-import warnings for symbols re-exported from train_pre.
_ = (THROWS_TO_INT, STAND_TO_INT, UNKNOWN_PARK_CODE)
