"""Pre-pitch pitch-TYPE trainer (Phase 2a, decision [183]).

LightGBM 7-class multinomial over the 24 pitch-type features (Tier S + ARS + SEQ,
see `pitch_type.PITCH_TYPE_FEATURE_COLUMNS`), with single-scalar TEMPERATURE
calibration fit on the fold's validation year - NOT the per-class isotonic the
pitch-OUTCOME heads use (report section 4). The [183] gate is ABSOLUTE calibration
(ECE < 0.02), and temperature is order-preserving, so calibration improves without
touching the top-1/top-3 ranking - the honest-framing constraint holds by construction.

Two integration points, mirroring `pitch.train_pre`:
  * `make_pitch_type_feature_loader` -> a `(start_year, end_year, fold_id) -> DataFrame`
    closure the `eval.cv_harness` drives. It reads the materialised store
    (`pitch_type_features`, V029) by DATE WINDOW, never by fold: the store is a
    single-pass `fold=0` materialisation (features are fold-independent), so the
    rolling-origin split IS the date filter. The loader ASSERTS the store is fold=0-only
    so a per-fold store (which would silently mis-slice) fails loud.
  * `model_factory(train_df, val_df)` -> a `ModelBundle` with `predict_proba`.

Deterministic per machine (`force_row_wise=True` + `deterministic=True`); cross-machine
bit-identical is not guaranteed (the LightGBM caveat `pitch.train_pre` documents).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any, cast

import lightgbm as lgb
import numpy as np
import pandas as pd
from clickhouse_driver import Client

from bullpen_training.eval.leakage_guards import refuse_holdout
from bullpen_training.ingest.clickhouse_client import ClickHouseSettings, make_client
from bullpen_training.logging_config import get_logger
from bullpen_training.pitch.lgb_dataset import build_lgb_dataset
from bullpen_training.pitch_type import PITCH_TYPE_CLASSES, PITCH_TYPE_FEATURE_COLUMNS
from bullpen_training.pitch_type.materialize import MATERIALIZE_FOLD
from bullpen_training.pitch_type.temperature import TemperatureCalibrator

log = get_logger(__name__)

DEFAULT_SEED = 42

_LABEL_TO_INT: dict[str, int] = {cls: i for i, cls in enumerate(PITCH_TYPE_CLASSES)}

# Contract encodings (feature_pipeline_pitchtype.json): stand/p_throws L=0,R=1 (missing->R=1);
# park_id -> a deterministic per-loader int, unknown park -> -1. Mirrors pitch.train_pre.
STAND_TO_INT: dict[str, int] = {"L": 0, "R": 1}
THROWS_TO_INT: dict[str, int] = {"L": 0, "R": 1}
UNKNOWN_PARK_CODE: int = -1

# Raw V029 columns the loader SELECTs, in order (label first for readability). The three
# categoricals (stand/p_throws/park_id) are integer-encoded downstream into stand_i/throws_i/
# park_i; everything else passes through under its own name.
_RAW_SELECT: tuple[str, ...] = (
    "label_pitch_type",
    "balls",
    "strikes",
    "outs",
    "inning",
    "base_state",
    "stand",
    "p_throws",
    "park_id",
    "times_through_order",
    "at_bat_number_in_game",
    "times_faced_today",
    "ars_FF",
    "ars_SI",
    "ars_FC",
    "ars_SL",
    "ars_CU",
    "ars_CH",
    "ars_OFF",
    "ars_FF_by_count",
    "pitcher_prior_n",
    "prev1_pt_i",
    "prev2_pt_i",
    "prev1_missing",
    "pitches_into_outing",
)


def _decode_fixedstring(value: Any) -> str:
    """clickhouse-driver may hand back FixedString(1) as bytes or str; normalise to str."""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace").rstrip("\x00")
    return str(value)


def _stand_to_int(s: pd.Series) -> pd.Series:
    codes = s.map(_decode_fixedstring).map(STAND_TO_INT)
    return cast(pd.Series, codes.fillna(STAND_TO_INT["R"]).astype("int8"))


def _throws_to_int(s: pd.Series) -> pd.Series:
    codes = s.map(_decode_fixedstring).map(THROWS_TO_INT)
    return cast(pd.Series, codes.fillna(THROWS_TO_INT["R"]).astype("int8"))


def _park_to_int(s: pd.Series, mapping: dict[str, int]) -> pd.Series:
    return cast(
        pd.Series,
        s.map(_decode_fixedstring).map(mapping).fillna(UNKNOWN_PARK_CODE).astype("int16"),
    )


def _label_to_int(s: pd.Series) -> pd.Series:
    return cast(pd.Series, s.map(_decode_fixedstring).map(_LABEL_TO_INT).astype("int8"))


def make_pitch_type_feature_loader(
    settings: ClickHouseSettings | None = None,
) -> PitchTypeFeatureLoaderClosure:
    """Build a feature_loader bound to a ClickHouse connection.

    Returned callable signature: `(start_year, end_year, fold_id) -> DataFrame` with a
    `label` column (integer-encoded y7) and `PITCH_TYPE_FEATURE_COLUMNS`.
    """
    return PitchTypeFeatureLoaderClosure(make_client(settings))


@dataclass
class PitchTypeFeatureLoaderClosure:
    client: Client
    park_id_mapping: dict[str, int] | None = None
    _fold_checked: bool = False

    def _ensure_prereqs(self) -> None:
        """Once per loader: assert the store is single-pass fold=0, then build the
        deterministic park_id -> int mapping shared across train/val/test calls."""
        if not self._fold_checked:
            rows = cast(
                list[tuple[Any, ...]],
                self.client.execute("SELECT DISTINCT fold FROM pitch_type_features"),
            )
            folds = {int(r[0]) for r in rows}
            if not folds:
                raise ValueError(
                    "pitch_type_features is empty - run `pitch_type.materialize` before "
                    "training (the trainer reads the materialised store, it does not build it)."
                )
            if folds != {MATERIALIZE_FOLD}:
                raise ValueError(
                    f"pitch_type_features must be a single-pass fold={MATERIALIZE_FOLD} store; "
                    f"found folds {sorted(folds)}. The pitch-type trainer slices by DATE WINDOW, "
                    "not fold (features are fold-independent) - a per-fold store would silently "
                    "mis-slice the rolling-origin folds. Re-materialise via "
                    "pitch_type.materialize (fold=0)."
                )
            self._fold_checked = True
        if self.park_id_mapping is None:
            rows = cast(
                list[tuple[Any, ...]],
                self.client.execute(
                    "SELECT DISTINCT park_id FROM pitch_type_features ORDER BY park_id"
                ),
            )
            self.park_id_mapping = {_decode_fixedstring(r[0]): i for i, r in enumerate(rows)}
            log.info("park_id mapping built", parks=len(self.park_id_mapping))

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        # fold_id is INTENTIONALLY unused for filtering (the store is fold=0; the date window
        # IS the rolling-origin split). Rule 13: refuse a holdout-touching window defensively.
        refuse_holdout(season_from=start_year, season_to=end_year)
        self._ensure_prereqs()
        assert self.park_id_mapping is not None
        start = date(start_year, 1, 1)
        end = date(end_year, 12, 31)
        # toString the Enum8 label so the driver returns its string name regardless of Enum
        # config (matches pitch.train_pre's `toString(label) AS label`); other columns pass
        # through under their own name so the DataFrame columns align with _RAW_SELECT.
        select_clause = ", ".join(
            "toString(label_pitch_type) AS label_pitch_type" if c == "label_pitch_type" else c
            for c in _RAW_SELECT
        )
        sql = (
            f"SELECT {select_clause} FROM pitch_type_features FINAL "
            f"WHERE game_date BETWEEN '{start}' AND '{end}' "
            "ORDER BY game_date, game_id, at_bat_index, pitch_number"
        )
        rows = cast(list[tuple[Any, ...]], self.client.execute(sql))
        df = pd.DataFrame(rows, columns=list(_RAW_SELECT))
        df["stand_i"] = _stand_to_int(cast(pd.Series, df["stand"]))
        df["throws_i"] = _throws_to_int(cast(pd.Series, df["p_throws"]))
        df["park_i"] = _park_to_int(cast(pd.Series, df["park_id"]), self.park_id_mapping)
        df["label"] = _label_to_int(cast(pd.Series, df["label_pitch_type"]))
        keep = [*PITCH_TYPE_FEATURE_COLUMNS, "label"]
        return cast(pd.DataFrame, df[keep])


@dataclass
class ModelBundle:
    booster: lgb.Booster
    calibrator: TemperatureCalibrator
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
    early_stopping_rounds: int = 30,
    log_evaluation_period: int = 100,
) -> ModelBundle:
    """Train a LightGBM 7-class multinomial then fit temperature on val_df.

    Params are the report appendix (num_leaves 63, min_data_in_leaf 200, lr 0.05,
    feature/bagging 0.8, early-stop 30). num_threads is left to the LightGBM default
    for portability; with deterministic=True the thread count does not change the model,
    so the box run may tune it without affecting reproducibility.
    """
    params = {
        "objective": "multiclass",
        "num_class": len(PITCH_TYPE_CLASSES),
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
    feat_cols = list(PITCH_TYPE_FEATURE_COLUMNS)
    dtrain = build_lgb_dataset(train_df, feat_cols)
    dval = build_lgb_dataset(val_df, feat_cols, reference=dtrain)
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
    calibrator = TemperatureCalibrator.fit(
        np.asarray(val_df["label"], dtype=np.int64),
        val_pred,
        class_labels=PITCH_TYPE_CLASSES,
    )
    log.info("temperature fit", temperature=round(calibrator.temperature, 4))
    return ModelBundle(booster=booster, calibrator=calibrator, feature_cols=tuple(feat_cols))
