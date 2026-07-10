"""Rolling-origin CV + experiment_results evidence driver (W5, Mac side).

This is the missing batted-ball-side analogue of the pitch
``production.py`` CV caller, generalised to ALSO emit the rule-5
``experiment_results``-shaped evidence artifact that a SHADOW -> CHAMPION
promotion reads.

For one model_name it:

  1. Runs the rolling-origin 4-fold CV (``eval.cv_harness.run``) for BOTH the
     co-registered BASELINE and the CHALLENGER on the sample data - same folds,
     same metrics - so the two models' per-fold metrics are directly
     comparable. (Rolling-origin only; 2026 excluded; no random splits - the
     harness + the per-year parquet loader enforce all three.)
  2. On the FINAL fold's held-out test year, scores both models on the SAME
     rows (paired predictions) and computes the challenger-vs-baseline verdict
     against the PRE-DECLARED criteria (``promotion.criteria``), using the same
     verdict math the Java gate uses.
  3. Writes an ``experiment_results``-shaped JSON artifact carrying the
     pre-declared criteria, the observed champion/challenger metrics, the
     guardrail deltas, and the verdict (passed/failed). A human reads this and
     promotes (rule 6: NO auto-promotion here).

Model wiring:

  - ``pitch_outcome_pre``  : challenger = LightGBM, baseline = LR (sklearn).
  - ``pitch_outcome_post`` : challenger = LightGBM (Tier-4 features),
                             baseline = LR.
  - ``batted_ball_lr_baseline`` : challenger = the LR baseline itself,
                             baseline = the DEGENERATE constant
                             marginal-class predictor (the floor any
                             registered model must beat). This evidences the
                             rule-9 baseline clears the worth-registering bar.

The real LIVE/CHAMPION promotion re-runs this on full BOX data (operator
hand-off H2): pass ``--data-source full`` (pointing ``--sample-root`` at the
full-box parquet) so the artifact is labelled ``full`` and written as
``<model>_experiment_results_full.json``. A SAMPLE-data passing row clears only
the SHADOW-stage evidence per the locked decision - it is NOT a LIVE promotion.
The artifact's ``data_source`` field records the label loudly (default
"sample") so no one mistakes sample evidence for the production verdict. The
flag is a LABEL ONLY - it changes no metric, threshold, verdict, or status.
"""

from __future__ import annotations

import json
import logging
import subprocess
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import click
import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.eval.cv_harness import FOLDS, CVResult, FeatureLoader
from bullpen_training.eval.cv_harness import run as cv_run
from bullpen_training.eval.metrics import (
    expected_calibration_error,
    multiclass_brier,
    multiclass_log_loss,
)
from bullpen_training.eval.promotion.criteria import (
    PromotionCriteria,
    Verdict,
    criteria_for,
    evaluate_challenger_vs_baseline,
)
from bullpen_training.eval.promotion.sample_loader import (
    N_CLASSES,
    RETRO_COLS,
    SEGMENT_COLS,
    ParquetSampleLoader,
    feature_cols_for,
    generate_sample_dataset,
)
from bullpen_training.pitch import PITCH_FEATURE_COLUMNS, PITCH_FEATURE_COLUMNS_POST
from bullpen_training.pitch.fold_store import ParquetFoldLoader
from bullpen_training.pitch.isotonic import IsotonicCalibrator

log = logging.getLogger(__name__)

REPO_ROOT = Path(__file__).resolve().parents[5]
DEFAULT_SAMPLE_ROOT = REPO_ROOT / "training" / "data" / "samples" / "dev"
DEFAULT_OUT_DIR = REPO_ROOT / "training" / "data" / "eval" / "promotion"

# Reproducible model fits (NOT a data split seed - these seed the LightGBM
# booster / the LR solver, never the rolling-origin splits, which are pure
# date windows. No random_state ever touches a split. rule: no random splits).
_LGBM_SEED = 42
# Seeds the per-park MLP weight init + the torch RNG (NOT a data split - the rolling-origin folds
# are pure date windows). rule: no random splits.
_MLP_SEED = 42
# None -> the per-park trainer's production DEFAULT_EPOCHS. A module constant (not a threaded param)
# so tests can monkeypatch a small epoch count for speed without touching the production path.
_MLP_EPOCHS: int | None = None

# Recalibration knob for the batted-ball per-park MLP champion (set via --mlp-calibration).
# "isotonic" (default) is a GLOBAL one-vs-rest isotonic on val (what #115 used); it stays the
# default so #115's evidence + existing tests reproduce, but it is NOT the served champion's
# calibration. The served champion uses per-(park, class) isotonic (decision [51]) - use
# "per_park_isotonic" for a faithful H2 verdict:
#   "per_park_isotonic"             - PRODUCTION-faithful per-(park, class) isotonic (raw -> retro)
#   "per_park_temperature"          - per-park temperature only (experiment; negative result)
#   "per_park_temperature_isotonic" - per-park T then global isotonic (experiment; negative result)
# The temperature variants chased the absolute ECE<0.02 bar #115 missed at 0.0346; both came back
# worse than plain isotonic, so per_park_isotonic (the faithful calibration) is the real H2 path.
_MLP_CALIBRATION: str = "isotonic"
_MLP_CALIBRATION_CHOICES = (
    "isotonic",
    "per_park_isotonic",
    "per_park_temperature",
    "per_park_temperature_isotonic",
)
# A park needs >= this many val rows to fit its own temperature; below it, the pooled T is used.
_MIN_PARK_VAL_ROWS: int = 200

_CV_METRICS = (multiclass_brier, multiclass_log_loss, expected_calibration_error)


# ---------------------------------------------------------------------------
# Predictors. Each exposes .predict_proba(X) -> (N, K) for the harness.
# ---------------------------------------------------------------------------


class _MarginalPredictor:
    """Constant predictor: the train-set class marginals for every row. The
    DEGENERATE baseline floor the batted-ball LR must beat to be worth
    registering."""

    def __init__(self, marginals: np.ndarray) -> None:
        self._m = np.asarray(marginals, dtype=np.float64)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        return np.tile(self._m, (len(X), 1))


def _class_labels(n_classes: int) -> tuple[str, ...]:
    """Synthetic class-label names for the isotonic calibrator (it keys on
    count + order, not the strings)."""
    return tuple(str(i) for i in range(n_classes))


class _LRPredictor:
    """Standardised multinomial LR (sklearn) + per-class isotonic calibration,
    mirroring the production LR baseline (which always ships isotonic
    calibrators). Emits a full K-wide proba even if a class is unseen in train
    (anchor rows injected, as the production LR baseline does)."""

    def __init__(
        self,
        pipeline: Pipeline,
        feature_cols: tuple[str, ...],
        n_classes: int,
        calibrator: IsotonicCalibrator | None,
    ) -> None:
        self._pipe = pipeline
        self._cols = feature_cols
        self._k = n_classes
        self._cal = calibrator

    def _raw(self, X: pd.DataFrame) -> np.ndarray:
        # Predict on a bare ndarray (the pipeline was fit on ndarray) so sklearn
        # does not warn about feature-name mismatch.
        x = X[list(self._cols)].to_numpy(dtype=np.float64)
        raw = np.asarray(self._pipe.predict_proba(x), dtype=np.float64)
        if raw.shape[1] == self._k:
            return raw
        # Re-expand to K columns if sklearn dropped an unseen class.
        full = np.full((raw.shape[0], self._k), 1e-9, dtype=np.float64)
        for j, cls in enumerate(self._pipe.classes_):
            full[:, int(cls)] = raw[:, j]
        return full / full.sum(axis=1, keepdims=True)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = self._raw(X)
        return raw if self._cal is None else self._cal.transform(raw)


class _LGBMPredictor:
    """LightGBM multinomial + per-class isotonic calibration, mirroring the
    production pitch heads (``pitch.train_pre.model_factory`` always fits an
    isotonic calibrator on the fold's val year)."""

    def __init__(
        self, booster: Any, feature_cols: tuple[str, ...], calibrator: IsotonicCalibrator | None
    ) -> None:
        self._booster = booster
        self._cols = feature_cols
        self._cal = calibrator

    def _raw(self, X: pd.DataFrame) -> np.ndarray:
        return np.asarray(self._booster.predict(X[list(self._cols)]), dtype=np.float64)

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = self._raw(X)
        return raw if self._cal is None else self._cal.transform(raw)


class _MLPPredictor:
    """Per-park batted-ball MLP CHAMPION: one PerParkMLP per park (shared-backbone topology, trained
    on the retrodicted outcome DISTRIBUTION via KL), routed by each row's ``park``. A pooled
    all-parks model backs rows whose park was unseen in train (a cold park at val/test). Optional
    per-class isotonic on val mirrors the LR/LGBM predictors so the champion-vs-baseline comparison
    is calibration-fair."""

    def __init__(
        self,
        park_models: dict[str, tuple[Any, Any]],
        pooled: tuple[Any, Any],
        feature_cols: tuple[str, ...],
        n_classes: int,
        calibrator: IsotonicCalibrator | None,
        temperatures: dict[str, float] | None = None,
        default_temperature: float = 1.0,
        pp_isotonic: _PerParkIsotonic | None = None,
    ) -> None:
        self._models = park_models  # park -> (PerParkMLP, FeatureScaler)
        self._pooled = pooled  # (PerParkMLP, FeatureScaler) cold-park fallback
        self._cols = list(feature_cols)
        self._k = n_classes
        self._cal = calibrator
        # Optional per-park temperature scaling (recalibration experiment). Applied between _raw and
        # the isotonic step; None == the original isotonic-only path.
        self._temps = temperatures
        self._default_t = default_temperature
        # Optional per-(park, class) isotonic - the PRODUCTION batted-ball calibration (decision
        # [51]); when set it REPLACES the global ``calibrator`` so the CV evaluates the served
        # champion's actual calibration.
        self._pp_isotonic = pp_isotonic

    def _raw(self, X: pd.DataFrame) -> np.ndarray:
        import torch

        feat = X[self._cols].to_numpy(dtype=np.float64)
        parks = X["park"].to_numpy().astype(str)
        out = np.zeros((len(X), self._k), dtype=np.float64)
        for park in np.unique(parks):
            mask = parks == park
            model, scaler = self._models.get(park, self._pooled)
            x = np.ascontiguousarray(scaler.transform(feat[mask]), dtype=np.float32)
            model.eval()
            dev = next(
                model.parameters()
            ).device  # route input to the model's device (cpu/mps/cuda)
            with torch.no_grad():
                logits = model(torch.from_numpy(x).to(dev))
                proba = torch.softmax(logits, dim=1).cpu().numpy()
            out[mask] = np.asarray(proba, dtype=np.float64)
        return out

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        raw = self._raw(X)
        parks = X["park"].to_numpy().astype(str)
        if self._temps is not None:
            raw = _temperature_scale(raw, parks, self._temps, self._default_t)
        if self._pp_isotonic is not None:
            return self._pp_isotonic.transform(raw, parks)
        return raw if self._cal is None else self._cal.transform(raw)


# ---------------------------------------------------------------------------
# Model factories (cv_harness.ModelFactory: (train_df, val_df) -> predictor).
# val_df is unused by the simple sample fits but kept for the harness contract.
# ---------------------------------------------------------------------------


def _marginal_factory(n_classes: int) -> Callable[[pd.DataFrame, pd.DataFrame], _MarginalPredictor]:
    def factory(train: pd.DataFrame, _val: pd.DataFrame) -> _MarginalPredictor:
        counts = np.bincount(np.asarray(train["label"], dtype=np.int64), minlength=n_classes)
        return _MarginalPredictor(counts / counts.sum())

    return factory


def _fit_isotonic_on_val(
    raw_val_proba: np.ndarray, y_val: np.ndarray, n_classes: int
) -> IsotonicCalibrator:
    """One-vs-rest isotonic on the val fold - the SAME calibration the
    production models fit (decision [38]). Calibrating on val (never test)
    keeps the calibrator leakage-clean."""
    return IsotonicCalibrator.fit(y_val, raw_val_proba, class_labels=_class_labels(n_classes))


def _temperature_scale(
    probs: np.ndarray, parks: np.ndarray, temps: dict[str, float], default_t: float
) -> np.ndarray:
    """Apply a per-park temperature to softmax probs via log-prob scaling: softmax(log p / T)
    equals logit temperature scaling (the per-row softmax constant cancels). T > 1 softens an
    over-confident park, T < 1 sharpens an under-confident one. Parks absent from ``temps`` (cold
    parks at test, or small parks pooled at fit) use ``default_t``."""
    logp = np.log(np.clip(probs, 1e-12, 1.0))
    out = np.empty_like(probs)
    for park in np.unique(parks):
        mask = parks == park
        scaled = logp[mask] / temps.get(str(park), default_t)
        scaled = scaled - scaled.max(axis=1, keepdims=True)
        e = np.exp(scaled)
        out[mask] = e / e.sum(axis=1, keepdims=True)
    return out


def _fit_temperature(logp: np.ndarray, y: np.ndarray) -> float:
    """Fit a single temperature T (logits/T) minimising NLL on (log-probs, integer labels). One free
    parameter, so it is robust on small samples; the bounded search avoids a degenerate T."""
    from scipy.optimize import minimize_scalar

    def nll(t: float) -> float:
        scaled = logp / t
        scaled = scaled - scaled.max(axis=1, keepdims=True)
        ls = scaled - np.log(np.exp(scaled).sum(axis=1, keepdims=True))
        return float(-ls[np.arange(len(y)), y].mean())

    res = minimize_scalar(nll, bounds=(0.05, 20.0), method="bounded")
    return float(res.x)


def _fit_per_park_temperature(
    raw_val: np.ndarray, parks_val: np.ndarray, y_val: np.ndarray
) -> tuple[dict[str, float], float]:
    """Per-park temperatures + a pooled default, fit on val only (leakage-clean, mirroring the
    isotonic path). A park with >= ``_MIN_PARK_VAL_ROWS`` val rows fits its own T; smaller parks
    (and cold parks at test) fall back to the pooled T fit on ALL val rows."""
    logp = np.log(np.clip(raw_val, 1e-12, 1.0))
    pooled_t = _fit_temperature(logp, y_val)
    temps: dict[str, float] = {}
    for park in np.unique(parks_val):
        mask = parks_val == park
        if int(mask.sum()) >= _MIN_PARK_VAL_ROWS:
            temps[str(park)] = _fit_temperature(logp[mask], y_val[mask])
    return temps, pooled_t


class _PerParkIsotonic:
    """Per-(park, class) isotonic - the PRODUCTION batted-ball calibration (decision [51],
    ``battedball.mlp.calibration``): one sklearn ``IsotonicRegression`` per (park, outcome), applied
    per row by the row's park then renormalised (floor 1e-9, as production does). Cold parks at test
    use a pooled all-park grid. This is the calibration the SERVED champion uses, so the CV scores
    the real model rather than a global-isotonic surrogate."""

    def __init__(self, per_park: dict[str, list[Any]], pooled: list[Any], n_classes: int) -> None:
        self._per_park = per_park  # park -> [IsotonicRegression] * K
        self._pooled = pooled  # [IsotonicRegression] * K (cold-park fallback)
        self._k = n_classes

    def transform(self, raw: np.ndarray, parks: np.ndarray) -> np.ndarray:
        out = np.empty_like(raw, dtype=np.float64)
        for park in np.unique(parks):
            mask = parks == park
            isos = self._per_park.get(str(park), self._pooled)
            for c in range(self._k):
                out[mask, c] = isos[c].transform(raw[mask, c])
        out = np.maximum(out, 1e-9)
        return out / out.sum(axis=1, keepdims=True)


def _fit_per_park_isotonic(
    raw_val: np.ndarray, retro_val: np.ndarray, parks_val: np.ndarray, n_classes: int
) -> _PerParkIsotonic:
    """Fit the production per-(park, class) isotonic on val only (leakage-clean): each cell maps the
    MLP's raw prob to the retrodicted-distribution target - the SAME target + method production uses
    (decision [51]). A park with >= _MIN_PARK_VAL_ROWS val rows fits its own grid; smaller + cold
    parks use a pooled grid fit on all val."""
    # Imported lazily (sklearn is heavy) - same posture as the inline import it replaces.
    from bullpen_training.eval.calibration import fit_isotonic

    def fit_grid(raw: np.ndarray, retro: np.ndarray) -> list[Any]:
        grid: list[Any] = []
        for c in range(n_classes):
            grid.append(fit_isotonic(raw[:, c], retro[:, c], y_min=0.0, y_max=1.0))
        return grid

    pooled = fit_grid(raw_val, retro_val)
    per_park: dict[str, list[Any]] = {}
    for park in np.unique(parks_val):
        mask = parks_val == park
        if int(mask.sum()) >= _MIN_PARK_VAL_ROWS:
            per_park[str(park)] = fit_grid(raw_val[mask], retro_val[mask])
    return _PerParkIsotonic(per_park, pooled, n_classes)


def _lr_factory(
    feature_cols: tuple[str, ...], n_classes: int
) -> Callable[[pd.DataFrame, pd.DataFrame], _LRPredictor]:
    def factory(train: pd.DataFrame, val: pd.DataFrame) -> _LRPredictor:
        x = train[list(feature_cols)].to_numpy(dtype=np.float64)
        y = np.asarray(train["label"], dtype=np.int64)
        # Anchor an absent class with one mean-feature row (same trick the
        # production LR baseline uses) so predict_proba stays K-wide.
        present = set(int(v) for v in np.unique(y))
        absent = [c for c in range(n_classes) if c not in present]
        if absent:
            anchors = np.tile(x.mean(axis=0, keepdims=True), (len(absent), 1))
            x = np.vstack([x, anchors])
            y = np.concatenate([y, np.asarray(absent, dtype=y.dtype)])
        # Mirror production's LR baseline pipeline (train_lr_baseline._build_pipeline): the
        # SimpleImputer is REQUIRED on real data - the production feature set carries NULLs
        # (Tier-3 rolling at season start, Tier-4 sparse pre-2024) that sklearn LR rejects. The
        # sample proxy had none, which is why this only surfaced on the first fold-export gate.
        pipe = Pipeline(
            [
                ("imputer", SimpleImputer(strategy="median")),
                ("scale", StandardScaler(copy=False)),
                ("lr", LogisticRegression(max_iter=2000)),
            ]
        ).fit(x, y)
        uncal = _LRPredictor(pipe, feature_cols, n_classes, calibrator=None)
        # Fit isotonic on the val fold (never test) - mirrors production.
        raw_val = uncal._raw(val)
        cal = _fit_isotonic_on_val(raw_val, np.asarray(val["label"], dtype=np.int64), n_classes)
        return _LRPredictor(pipe, feature_cols, n_classes, calibrator=cal)

    return factory


def _lgbm_factory(
    feature_cols: tuple[str, ...], n_classes: int
) -> Callable[[pd.DataFrame, pd.DataFrame], _LGBMPredictor]:
    import lightgbm as lgb

    def factory(train: pd.DataFrame, val: pd.DataFrame) -> _LGBMPredictor:
        params = {
            "objective": "multiclass",
            "num_class": n_classes,
            "metric": "multi_logloss",
            "learning_rate": 0.05,
            "num_leaves": 31,
            "min_data_in_leaf": 50,
            "seed": _LGBM_SEED,
            "deterministic": True,
            "force_row_wise": True,
            "verbosity": -1,
        }
        cols = list(feature_cols)
        dtrain = lgb.Dataset(train[cols], label=np.asarray(train["label"], dtype=np.int64))
        dval = lgb.Dataset(
            val[cols], label=np.asarray(val["label"], dtype=np.int64), reference=dtrain
        )
        booster = lgb.train(
            params,
            dtrain,
            num_boost_round=300,
            valid_sets=[dval],
            valid_names=["val"],
            callbacks=[lgb.early_stopping(30, verbose=False)],
        )
        uncal = _LGBMPredictor(booster, feature_cols, calibrator=None)
        # Per-class isotonic on val (never test) - the production pitch heads do
        # exactly this in pitch.train_pre.model_factory.
        raw_val = uncal._raw(val)
        cal = _fit_isotonic_on_val(raw_val, np.asarray(val["label"], dtype=np.int64), n_classes)
        return _LGBMPredictor(booster, feature_cols, calibrator=cal)

    return factory


def _mlp_factory(
    feature_cols: tuple[str, ...], n_classes: int
) -> Callable[[pd.DataFrame, pd.DataFrame], _MLPPredictor]:
    """Train the per-park batted-ball MLP CHAMPION the production way: one PerParkMLP per park,
    fit on that park's retrodicted outcome DISTRIBUTION via KL loss (the realized integer ``label``
    is NEVER a training target - only the CV harness scores against it). Reuses the production
    ``train_single_park`` + ``FeatureScaler`` + ``PerParkDataset`` so the CV is faithful to how the
    champion is actually trained. Parks are trained sequentially (one model resident at a time) to
    stay off the memory ceiling the box flagged."""

    def factory(train: pd.DataFrame, val: pd.DataFrame) -> _MLPPredictor:
        from bullpen_training.battedball.mlp.dataset import FeatureScaler
        from bullpen_training.battedball.mlp_per_park.dataset import PerParkDataset
        from bullpen_training.battedball.mlp_per_park.train import train_single_park

        cols = list(feature_cols)
        retro = list(RETRO_COLS)

        def _train(feat: np.ndarray, lab: np.ndarray) -> tuple[Any, Any]:
            scaler = FeatureScaler.fit(feat)
            ds = PerParkDataset(feat, lab, scaler=scaler)
            if _MLP_EPOCHS is None:
                model, _summary, _dev = train_single_park(
                    ds, n_features=len(cols), n_outcomes=n_classes, seed=_MLP_SEED
                )
            else:
                model, _summary, _dev = train_single_park(
                    ds,
                    n_features=len(cols),
                    n_outcomes=n_classes,
                    seed=_MLP_SEED,
                    n_epochs=_MLP_EPOCHS,
                )
            return model, scaler

        models: dict[str, tuple[Any, Any]] = {}
        for park, grp in train.groupby("park", sort=True):
            models[str(park)] = _train(
                grp[cols].to_numpy(dtype=np.float32),
                grp[retro].to_numpy(dtype=np.float32),
            )

        # Pooled all-parks fallback for a park unseen in train (cold park at val/test).
        pooled = _train(
            train[cols].to_numpy(dtype=np.float32),
            train[retro].to_numpy(dtype=np.float32),
        )

        uncal = _MLPPredictor(models, pooled, feature_cols, n_classes, calibrator=None)
        # Calibrate on the val fold only (never test) - leakage-clean, and the SAME val the LR/LGBM
        # factories isotonic-fit, so the champion-vs-baseline comparison stays calibration-fair.
        raw_val = uncal._raw(val)
        y_val = np.asarray(val["label"], dtype=np.int64)
        parks_val = val["park"].to_numpy().astype(str)
        if _MLP_CALIBRATION == "isotonic":
            cal = _fit_isotonic_on_val(raw_val, y_val, n_classes)
            return _MLPPredictor(models, pooled, feature_cols, n_classes, calibrator=cal)
        if _MLP_CALIBRATION == "per_park_isotonic":
            # PRODUCTION-faithful: per-(park, class) isotonic mapping raw prob -> retro target
            # (decision [51]) - the calibration the served champion actually uses.
            retro_val = val[list(RETRO_COLS)].to_numpy(dtype=np.float64)
            pp = _fit_per_park_isotonic(raw_val, retro_val, parks_val, n_classes)
            return _MLPPredictor(
                models, pooled, feature_cols, n_classes, calibrator=None, pp_isotonic=pp
            )
        # Recalibration: per-park temperature scaling, optionally + global isotonic on top.
        temps, pooled_t = _fit_per_park_temperature(raw_val, parks_val, y_val)
        cal = None
        if _MLP_CALIBRATION == "per_park_temperature_isotonic":
            scaled_val = _temperature_scale(raw_val, parks_val, temps, pooled_t)
            cal = _fit_isotonic_on_val(scaled_val, y_val, n_classes)
        elif _MLP_CALIBRATION != "per_park_temperature":
            raise ValueError(f"unknown _MLP_CALIBRATION {_MLP_CALIBRATION!r}")
        return _MLPPredictor(
            models,
            pooled,
            feature_cols,
            n_classes,
            calibrator=cal,
            temperatures=temps,
            default_temperature=pooled_t,
        )

    return factory


@dataclass(frozen=True)
class _ModelPair:
    """The two co-registered models for one model_name's evidence run."""

    baseline_name: str
    baseline_factory: Callable[[pd.DataFrame, pd.DataFrame], Any]
    challenger_name: str
    challenger_factory: Callable[[pd.DataFrame, pd.DataFrame], Any]


def _evidence_feature_cols(
    model_name: str, fold_root: Path | None
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Return ``(challenger_cols, baseline_cols)`` for the evidence run.

    On the fold-export path the gate must certify the PRODUCTION feature set the registered head
    actually serves, not the sample mirror's reduced proxy: the pitch challenger consumes
    ``PITCH_FEATURE_COLUMNS_POST`` (41) / ``PITCH_FEATURE_COLUMNS`` (31), and the rule-9 baseline is
    the PRE-31 LR per decision [37] (the co-registered cross-head sanity check - there is no
    separate POST LR baseline). The sample path keeps the synthetic proxy for both heads; that
    mirror carries only the proxy columns, so the production names would KeyError there."""
    if fold_root is not None and model_name in ("pitch_outcome_pre", "pitch_outcome_post"):
        challenger = (
            PITCH_FEATURE_COLUMNS_POST
            if model_name == "pitch_outcome_post"
            else PITCH_FEATURE_COLUMNS
        )
        return challenger, PITCH_FEATURE_COLUMNS
    cols = feature_cols_for(model_name)
    return cols, cols


def _model_pair(
    model_name: str,
    challenger_cols: tuple[str, ...],
    baseline_cols: tuple[str, ...],
    n_classes: int,
) -> _ModelPair:
    """Pair a challenger with its rule-9 co-registered baseline. ``challenger_cols`` and
    ``baseline_cols`` differ only for the pitch heads on the fold-export path, where the
    challenger consumes the production POST/PRE set and the baseline is the PRE-31 LR ([37]);
    batted-ball passes the same tuple for both (apples-to-apples)."""
    if model_name in ("pitch_outcome_pre", "pitch_outcome_post"):
        return _ModelPair(
            baseline_name="pitch_outcome_lr_baseline",
            baseline_factory=_lr_factory(baseline_cols, n_classes),
            challenger_name=model_name,
            challenger_factory=_lgbm_factory(challenger_cols, n_classes),
        )
    if model_name == "batted_ball_lr_baseline":
        return _ModelPair(
            baseline_name="marginal_class_floor",
            baseline_factory=_marginal_factory(n_classes),
            challenger_name="batted_ball_lr_baseline",
            challenger_factory=_lr_factory(challenger_cols, n_classes),
        )
    if model_name == "batted_ball_mlp":
        # The CHAMPION (per-park MLP) vs the rule-9 co-registered LR baseline, both on the SAME
        # features so the comparison is apples-to-apples.
        return _ModelPair(
            baseline_name="batted_ball_lr_baseline",
            baseline_factory=_lr_factory(baseline_cols, n_classes),
            challenger_name="batted_ball_mlp",
            challenger_factory=_mlp_factory(challenger_cols, n_classes),
        )
    raise ValueError(f"no model pairing for {model_name!r}")


# ---------------------------------------------------------------------------
# The evidence run
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EvidenceRun:
    model_name: str
    criteria: PromotionCriteria
    baseline_cv: CVResult
    challenger_cv: CVResult
    verdict: Verdict
    baseline_name: str
    challenger_name: str
    final_fold_id: int
    final_test_year: int
    sample_root: Path
    rows_per_year: int
    # Production per-class ECE vs the retrodicted distribution on the final-fold test - the metric
    # [141]'s "test ECE < 0.02" gate is defined on (comparison.py's 2c.6 convention). batted-ball
    # only (needs the retro_* cols); None for pitch, whose gate is label-ECE.
    challenger_retro_ece: float | None = None
    baseline_retro_ece: float | None = None
    # When set, evidence came from the production fold-export (ParquetFoldLoader) instead of a
    # per-year mirror - the safe full-box path for the pitch heads (reuses the schema-hash-pinned,
    # leak-safe served contract; no re-derivation). Recorded in provenance.
    fold_root: Path | None = None


def _make_loader(model_name: str, sample_root: Path, fold_root: Path | None) -> FeatureLoader:
    """The CV FeatureLoader. With ``fold_root`` set, read the production fold-export via
    ParquetFoldLoader - the safe full-box path: it reuses the served contract's exact features and
    fails loud on a rule-7 schema-hash mismatch, so no feature re-derivation (and no chance of
    drifting from the registered hash) happens here. Otherwise the per-year sample/full mirror via
    ParquetSampleLoader - both satisfy cv_harness's ``FeatureLoader`` protocol
    (Callable[[int, int, int], DataFrame])."""
    if fold_root is not None:
        return ParquetFoldLoader(fold_root)
    return ParquetSampleLoader(sample_root, model_name)


def run_evidence(
    model_name: str,
    *,
    sample_root: Path,
    rows_per_year: int,
    fold_root: Path | None = None,
) -> EvidenceRun:
    """Run the dual rolling-origin CV + compute the challenger-vs-baseline verdict for
    ``model_name``. Reads the per-year mirror at ``sample_root``, or - when ``fold_root`` is set -
    the production fold-export (the safe full-box path; see :func:`_make_loader`)."""
    criteria = criteria_for(model_name)
    n_classes = N_CLASSES[model_name]
    challenger_cols, baseline_cols = _evidence_feature_cols(model_name, fold_root)
    pair = _model_pair(model_name, challenger_cols, baseline_cols, n_classes)

    loader = _make_loader(model_name, sample_root, fold_root)

    log.info("evidence: rolling-origin CV for baseline=%s", pair.baseline_name)
    baseline_cv = cv_run(
        model_factory=pair.baseline_factory,
        feature_loader=loader,
        eval_metrics=list(_CV_METRICS),
    )
    log.info("evidence: rolling-origin CV for challenger=%s", pair.challenger_name)
    challenger_cv = cv_run(
        model_factory=pair.challenger_factory,
        feature_loader=loader,
        eval_metrics=list(_CV_METRICS),
    )

    # Paired-prediction verdict on the FINAL fold's held-out test year. Both
    # models are refit on the final fold's train+val window and scored on the
    # SAME test rows (paired predictions, same order) so the gate compares like
    # with like - the box-side analogue is LIVE+SHADOW predictions on the same
    # requests, which is exactly what the Java fetcher returns.
    final = FOLDS[-1]
    train_df = loader(final.train_start_year, final.train_end_year, final.fold_id)
    val_df = loader(final.val_year, final.val_year, final.fold_id)
    test_df = loader(final.test_year, final.test_year, final.fold_id)
    y_test = np.asarray(test_df["label"], dtype=np.int64)

    baseline_model = pair.baseline_factory(train_df, val_df)
    challenger_model = pair.challenger_factory(train_df, val_df)
    baseline_proba = baseline_model.predict_proba(test_df)
    challenger_proba = challenger_model.predict_proba(test_df)

    verdict = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y_test,
        baseline_proba=baseline_proba,
        challenger_proba=challenger_proba,
    )

    # batted-ball: also compute the [141] gate metric - per-class ECE vs the retro distribution
    # (comparison.py's 2c.6 convention). The verdict's label-ECE is the reality measure; this is the
    # (self-referential) physics-calibration gate. Pitch has no retrodiction -> stays None.
    challenger_retro_ece = baseline_retro_ece = None
    if all(col in test_df.columns for col in RETRO_COLS):
        retro = test_df[list(RETRO_COLS)].to_numpy(dtype=np.float64)
        challenger_retro_ece = _aggregate_retro_ece(challenger_proba, retro)
        baseline_retro_ece = _aggregate_retro_ece(baseline_proba, retro)

    return EvidenceRun(
        model_name=model_name,
        criteria=criteria,
        baseline_cv=baseline_cv,
        challenger_cv=challenger_cv,
        verdict=verdict,
        baseline_name=pair.baseline_name,
        challenger_name=pair.challenger_name,
        final_fold_id=final.fold_id,
        final_test_year=final.test_year,
        sample_root=Path(sample_root),
        rows_per_year=rows_per_year,
        challenger_retro_ece=challenger_retro_ece,
        baseline_retro_ece=baseline_retro_ece,
        fold_root=Path(fold_root) if fold_root is not None else None,
    )


# ---------------------------------------------------------------------------
# experiment_results-shaped artifact
# ---------------------------------------------------------------------------


def _git_commit_sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT), text=True
        ).strip()
    except (subprocess.CalledProcessError, FileNotFoundError):
        return "unknown"


def _cv_summary_dict(cv: CVResult) -> dict[str, dict[str, float]]:
    return {name: {"mean": mean, "std": std} for name, (mean, std) in cv.summary.items()}


def _aggregate_retro_ece(proba: np.ndarray, retro: np.ndarray) -> float:
    """The production batted-ball ECE: per-class ECE of the predicted prob vs the retrodicted
    distribution, averaged over classes (battedball.mlp.calibration's convention, decision [51] /
    comparison.py's 2c.6 ECE - the metric [141]'s "test ECE < 0.02" gate is defined on). It is
    SELF-REFERENTIAL: the champion is calibrated TO the retrodiction, then scored against it, so a
    low value proves agreement with the physics sim, NOT reality (label-ECE is reality)."""
    from bullpen_training.battedball.mlp.calibration import expected_calibration_error as _prod_ece

    n_classes = proba.shape[1]
    return float(np.mean([_prod_ece(proba[:, c], retro[:, c]) for c in range(n_classes)]))


def experiment_results_artifact(run: EvidenceRun, data_source: str = "sample") -> dict[str, Any]:
    """The ``experiment_results``-shaped evidence dict (metrics + pre-declared
    criteria + verdict). Field names mirror the V012 ``experiment_results``
    columns so the box-side registration can map this 1:1 into a row.

    ``status`` is 'passed' | 'failed' exactly as the Java
    ``ExperimentService.complete`` would set it: passed iff the verdict is
    WOULD_PASS AND the sample-size target is met; failed otherwise. NO
    promotion is performed (rule 6) - this is evidence only.

    ``data_source`` is a LABEL ONLY (default 'sample' = the Mac/CI sample mirror;
    'full' = the box full-data H2 re-run for the LIVE/CHAMPION gate). It changes
    no metric, threshold, verdict, or status - only the artifact's data_source
    field + note, so a full-box evidence row is self-describing rather than
    silently carrying the 'sample' label.
    """
    if data_source not in ("sample", "full"):
        raise ValueError(f"data_source must be 'sample' or 'full', got {data_source!r}")
    v = run.verdict
    c = run.criteria
    sample_met = v.sample_size_observed >= c.sample_size_target

    # Supplementary ABSOLUTE checks that do not fit the relative
    # challenger-vs-baseline guardrail shape. Any model that declares an
    # ``absolute_ece_bar`` is gated on the challenger's ABSOLUTE ECE against the
    # Phase-2 exit bar (< bar), in addition to the relative guardrails. This is
    # leakage-free and baseline-agnostic - it does not depend on a possibly
    # degenerate baseline's ECE.
    supplementary: list[dict[str, Any]] = []
    if c.absolute_ece_bar is not None:
        # The [141] gate is defined on retro-ECE for batted-ball (comparison.py's 2c.6 convention,
        # per-class ECE vs the retro distribution); pitch has no retrodiction, so theirs stays the
        # label-ECE the verdict already computed.
        if run.challenger_retro_ece is not None:
            observed_ece = run.challenger_retro_ece
            ece_metric = "ece_vs_retro"
            rationale = (
                "Phase-2 exit bar ([141]/[51]). batted-ball uses ece_vs_retro (per-class ECE vs "
                "the retro distribution) - but the champion is calibrated TO that retrodiction, "
                "so this is a SELF-REFERENTIAL physics-calibration check, NOT reality. See "
                "calibration_note + the reality label-ECE in *_full_metrics.ece."
            )
        else:
            observed_ece = v.challenger_metrics.ece
            ece_metric = "ece"
            rationale = (
                "Phase-2 exit bar ECE < bar per model (label-ECE); supplements the relative ECE "
                "guardrail (loose at sample scale, meaningless vs a degenerate baseline)."
            )
        passed_abs_ece = observed_ece < c.absolute_ece_bar
        supplementary.append(
            {
                "name": "absolute_ece_phase2_bar",
                "metric": ece_metric,
                "max_allowed": c.absolute_ece_bar,
                "observed": observed_ece,
                "passed": passed_abs_ece,
                "rationale": rationale,
            }
        )

    supplementary_ok = all(s["passed"] for s in supplementary)
    status = "passed" if (v.passed and sample_met and supplementary_ok) else "failed"

    return {
        "schema_version": 1,
        "artifact_name": "promotion_evidence",
        "data_source": data_source,  # LOUD: 'sample' (SHADOW-stage) or 'full' (H2 LIVE gate)
        "data_source_note": (
            (
                "SAMPLE-data evidence clears the SHADOW-stage rule-5 bar per the locked "
                "decision; the LIVE/CHAMPION promotion re-runs on full BOX data (operator "
                "hand-off H2). No promotion is performed here (rule 6)."
            )
            if data_source == "sample"
            else (
                "FULL-box data evidence for the LIVE/CHAMPION promotion gate (operator "
                "hand-off H2) - this is the row the promote-model skill reads. No promotion "
                "is performed here (rule 6); promotion stays human-gated."
            )
        ),
        "model_name": run.model_name,
        # experiment_results column analogues -------------------------------
        "champion_model_name": run.baseline_name,  # the thing the challenger must beat
        "challenger_model_name": run.challenger_name,
        "primary_metric": c.primary_metric.db_value,
        "primary_threshold": c.primary_threshold,
        "guardrails": c.guardrails_as_map(),
        "sample_size_target": c.sample_size_target,
        "sample_size_observed": v.sample_size_observed,
        "champion_metric": v.baseline_metrics.value_for(c.primary_metric),
        "challenger_metric": v.challenger_metrics.value_for(c.primary_metric),
        "guardrails_observed": v.guardrail_deltas,
        "guardrails_violated": v.guardrails_violated,
        "status": status,
        # verdict detail ----------------------------------------------------
        "verdict": {
            "outcome": v.outcome.value,
            "passed": v.passed,
            "sample_size_met": sample_met,
            "supplementary_checks_passed": supplementary_ok,
            "primary_margin_required": c.primary_threshold,
            "primary_margin_observed": (
                v.baseline_metrics.value_for(c.primary_metric)
                - v.challenger_metrics.value_for(c.primary_metric)
            ),
        },
        "supplementary_checks": supplementary,
        # pre-declared criteria (rule 5) - copied in so the gate is self-describing
        "pre_declared_criteria": {
            "primary_metric": c.primary_metric.db_value,
            "primary_threshold": c.primary_threshold,
            "sample_size_target": c.sample_size_target,
            "guardrails": [
                {
                    "metric": g.metric.db_value,
                    "max_delta": g.max_delta,
                    "rationale": g.rationale,
                }
                for g in c.guardrails
            ],
            "absolute_ece_bar": c.absolute_ece_bar,
            "rationale": c.rationale,
        },
        # full metric tables for both predictors. `ece` is the REALITY measure (label-ECE); for
        # batted-ball `ece_vs_retro` is the [141] gate metric (self-referential - see
        # calibration_note). Both are reported so neither hides the other.
        "champion_full_metrics": {
            "brier": v.baseline_metrics.brier,
            "log_loss": v.baseline_metrics.log_loss,
            "ece": v.baseline_metrics.ece,
            "ece_vs_retro": run.baseline_retro_ece,
        },
        "challenger_full_metrics": {
            "brier": v.challenger_metrics.brier,
            "log_loss": v.challenger_metrics.log_loss,
            "ece": v.challenger_metrics.ece,
            "ece_vs_retro": run.challenger_retro_ece,
        },
        "calibration_note": (
            (
                "ece_vs_retro is the [141]/[51] gate metric (per-class ECE vs the retro "
                "distribution) - SELF-REFERENTIAL: the champion is calibrated TO the retrodiction, "
                "so a low value proves agreement with the physics sim, NOT reality. `ece` (vs the "
                "realized label) is the reality measure and is mediocre, bounded by the weak "
                "retrodiction (rho~0.30 vs reality, [141]'s documented future work). This model is "
                "a calibrated PHYSICS ESTIMATE, not a reality-validated predictor."
            )
            if run.challenger_retro_ece is not None
            else None
        ),
        # rolling-origin CV summaries (4-fold mean+/-std, both models) -------
        "rolling_origin_cv": {
            "folds": [
                {
                    "fold_id": f.fold_id,
                    "train_start_year": f.train_start_year,
                    "train_end_year": f.train_end_year,
                    "val_year": f.val_year,
                    "test_year": f.test_year,
                }
                for f in FOLDS
            ],
            "champion_summary": _cv_summary_dict(run.baseline_cv),
            "challenger_summary": _cv_summary_dict(run.challenger_cv),
        },
        "verdict_fold": {
            "fold_id": run.final_fold_id,
            "test_year": run.final_test_year,
        },
        # provenance --------------------------------------------------------
        "provenance": {
            "git_commit": _git_commit_sha(),
            "generated_at": datetime.now(UTC).isoformat(timespec="seconds"),
            "sample_root": str(run.fold_root) if run.fold_root else str(run.sample_root),
            "loader": "fold_export" if run.fold_root else "per_year_mirror",
            # rows_per_year is the per-year-mirror knob; on the fold-export path the loader reads
            # WHOLE folds, so the default would mislead - record null there (the real scale lives
            # in the verdict's sample_size_observed).
            "rows_per_year": None if run.fold_root else run.rows_per_year,
            "segment_cols": list(SEGMENT_COLS[run.model_name]),
            "rule_13_holdout": "2026 excluded from every split (sample mirror is 2015-2025 only)",
            "split_discipline": "rolling-origin temporal CV; no random_state on any split",
            "mlp_calibration": _MLP_CALIBRATION,
        },
    }


def _artifact_filename(model_name: str, data_source: str) -> str:
    """Artifact filename. A non-sample run gets a ``_{data_source}`` suffix so the box full-data H2
    row never clobbers the committed sample-stage row. For batted_ball_mlp, a NON-default
    calibration also appends ``_{_MLP_CALIBRATION}`` so a recalibration run never clobbers the
    committed isotonic H2 row (#115); isotonic keeps the original name."""
    suffix = "" if data_source == "sample" else f"_{data_source}"
    if model_name == "batted_ball_mlp" and _MLP_CALIBRATION != "isotonic":
        suffix += f"_{_MLP_CALIBRATION}"
    return f"{model_name}_experiment_results{suffix}.json"


def write_artifact(run: EvidenceRun, out_dir: Path, data_source: str = "sample") -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / _artifact_filename(run.model_name, data_source)
    path.write_text(json.dumps(experiment_results_artifact(run, data_source), indent=2) + "\n")
    return path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

# 'all' stays the fast, torch-free trio; the per-park MLP champion is run explicitly
# (--model batted_ball_mlp) since it pulls torch + trains one model per park.
_MODEL_CHOICES = (
    "pitch_outcome_pre",
    "pitch_outcome_post",
    "batted_ball_lr_baseline",
    "batted_ball_mlp",
    "all",
)


@click.command()
@click.option(
    "--model",
    type=click.Choice(_MODEL_CHOICES),
    default="all",
    show_default=True,
    help="Which model's evidence to produce ('all' runs the three).",
)
@click.option(
    "--sample-root",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_SAMPLE_ROOT,
    show_default=True,
    help="samples/dev parquet mirror root (per-year files under <root>/<model>/).",
)
@click.option(
    "--out-dir",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_OUT_DIR,
    show_default=True,
)
@click.option(
    "--generate-sample/--no-generate-sample",
    default=True,
    show_default=True,
    help="Generate the deterministic Mac proof-of-path sample if the mirror is absent.",
)
@click.option("--rows-per-year", type=int, default=1_500, show_default=True)
@click.option(
    "--data-source",
    type=click.Choice(["sample", "full"]),
    default="sample",
    show_default=True,
    help="Evidence LABEL only (no metric/threshold/verdict change). 'sample' = Mac/CI sample "
    "mirror (SHADOW-stage rule-5 bar); 'full' = box full-data H2 re-run for the LIVE/CHAMPION "
    "gate, written as <model>_experiment_results_full.json so it never clobbers the sample row. "
    "For 'full', point --sample-root at the full-box parquet.",
)
@click.option(
    "--fold-root",
    type=click.Path(exists=True, file_okay=False, path_type=Path),
    default=None,
    help="Production fold-export dir (pitch/fold_store ParquetFoldLoader). When set, the gate "
    "reads the schema-hash-pinned, leak-safe served-contract folds from here instead of a "
    "per-year mirror - the safe full-box path for the pitch heads. Needs an explicit single "
    "--model; pair with --data-source full.",
)
@click.option(
    "--mlp-calibration",
    type=click.Choice(_MLP_CALIBRATION_CHOICES),
    default="isotonic",
    show_default=True,
    help="batted_ball_mlp calibration (recalibration experiment). 'isotonic' is the original; the "
    "'per_park_temperature*' options add per-park temperature scaling to chase the absolute "
    "ECE<0.02 bar the #115 H2 run missed at 0.0346. Only affects --model batted_ball_mlp.",
)
def main(
    model: str,
    sample_root: Path,
    out_dir: Path,
    generate_sample: bool,
    rows_per_year: int,
    data_source: str,
    fold_root: Path | None,
    mlp_calibration: str,
) -> None:
    global _MLP_CALIBRATION
    _MLP_CALIBRATION = mlp_calibration
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    models = (
        ("pitch_outcome_pre", "pitch_outcome_post", "batted_ball_lr_baseline")
        if model == "all"
        else (model,)
    )
    if fold_root is not None and model == "all":
        raise click.UsageError(
            "--fold-root needs an explicit single --model (the fold-export is model-specific)."
        )
    for m in models:
        if fold_root is None and generate_sample:
            dataset_dir = Path(sample_root) / m
            if not dataset_dir.is_dir():
                log.info("evidence: generating sample mirror for %s under %s", m, dataset_dir)
                generate_sample_dataset(sample_root, m, rows_per_year=rows_per_year)
        run = run_evidence(
            m, sample_root=Path(sample_root), rows_per_year=rows_per_year, fold_root=fold_root
        )
        art = experiment_results_artifact(run, data_source)
        path = write_artifact(run, Path(out_dir), data_source=data_source)
        v = run.verdict
        # status is the FINAL gate result (relative verdict + sample-size +
        # supplementary absolute checks); verdict.outcome is the relative-gate
        # piece. Report the final status so it matches the artifact.
        click.echo(
            f"[{m}] data_source={data_source} status={art['status'].upper()} "
            f"relative_verdict={v.outcome.value} "
            f"primary({v.primary_metric.db_value}): "
            f"champion={v.baseline_metrics.value_for(v.primary_metric):.4f} "
            f"challenger={v.challenger_metrics.value_for(v.primary_metric):.4f} "
            f"(margin>={v.primary_threshold}); artifact -> {path}"
        )


if __name__ == "__main__":
    main()
