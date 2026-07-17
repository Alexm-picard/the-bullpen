"""Predictors + calibration fits for the promotion-evidence driver (M5 decomposition).

Extracted VERBATIM from ``driver.py`` (which had grown to ~1100 lines): the
request-independent modeling machinery - the four predictor wrappers, the
marginal/LR/LGBM factories, and the val-fold calibration fits. ``driver.py``
keeps the orchestration + CLI + artifact contract (``EvidenceRun``,
``run_evidence``, ``experiment_results_artifact``, ``write_artifact``,
``main``) AND ``_mlp_factory``: that factory reads the driver's mutable module
globals (``_MLP_EPOCHS`` / ``_MLP_CALIBRATION``), which are the CLI's
``--mlp-calibration`` mutation point and the tests' monkeypatch targets - the
artifact FILENAME depends on ``_MLP_CALIBRATION`` too, so the mutable config
surface stays whole in one module.

Move-not-rewrite: no behavior, signature, or constant-value changes. The
promotion-evidence CLI contract (arguments, JSON artifacts, filenames) is
byte-identical - it lives entirely in ``driver.py``.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

import numpy as np
import pandas as pd
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from bullpen_training.pitch.isotonic import IsotonicCalibrator

# Reproducible model fits (NOT a data split seed - these seed the LightGBM
# booster / the LR solver, never the rolling-origin splits, which are pure
# date windows. No random_state ever touches a split. rule: no random splits).
_LGBM_SEED = 42

# A park needs >= this many val rows to fit its own temperature; below it, the pooled T is used.
_MIN_PARK_VAL_ROWS: int = 200


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
