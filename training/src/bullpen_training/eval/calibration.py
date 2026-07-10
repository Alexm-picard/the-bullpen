"""Shared isotonic-calibration primitives (road-to-9s M-task 23).

The per-calibrator layer that every calibrated head duplicated is hoisted here:
the clip-mode fit, the serialized dict shape, the fitted-state rebuild, and the
2-D apply+renormalise idiom. The CONTAINERS deliberately stay where they are
(``pitch.isotonic.IsotonicCalibrator`` and ``battedball.mlp.calibration
.ParkCalibrators``) because each serializes a DIFFERENT frozen Java-contract
schema (``IsotonicCalibratorJava`` reads the pitch shape; ``BattedBallCalibrators
.load`` reads the park-map shape) - merging them would change contract bytes,
which is exactly what this refactor must not do.

Byte-stability contract: :func:`isotonic_to_dict` preserves the historical key
order (``outcome, x_thresholds, y_thresholds, y_min, y_max, out_of_bounds``)
verbatim - ``json.dumps`` follows dict insertion order, so every existing
``calibrator.json`` writer produces byte-identical output through this helper.
Pinned by ``tests/eval/test_calibration.py``.

:func:`isotonic_from_dict` is the ONE sanctioned place that touches sklearn's
private fitted state (``X_thresholds_``/``y_thresholds_``/``_build_f``); the
five former copies of that idiom made every sklearn upgrade a five-file audit.
"""

from __future__ import annotations

from typing import Any, Literal

import numpy as np
from sklearn.isotonic import IsotonicRegression


def fit_isotonic(
    x: np.ndarray,
    y: np.ndarray,
    *,
    y_min: float | None = None,
    y_max: float | None = None,
    increasing: bool | Literal["auto"] = True,
) -> IsotonicRegression:
    """Fit one clip-mode isotonic regressor - the single sklearn-touching fit site.

    Every calibrated head in the project fits with ``out_of_bounds="clip"``; the
    remaining kwargs pass through verbatim so each caller keeps its historical
    behavior (pitch: ``y_min=0, y_max=1, increasing=True``; batted-ball MLP/LGBM
    and the promotion driver: ``y_min=0, y_max=1``; the batted-ball LR baseline:
    sklearn defaults).

    ``increasing`` defaults to ``True`` - sklearn's OWN default, and what every
    historical fit site used. Do NOT default this to ``"auto"``: auto infers the
    direction from a Spearman estimate and would fit a DECREASING calibrator on a
    negatively-correlated (class, park) cell - a wrong-direction output change the
    M-task-23 leakage audit caught before merge.
    """
    # sklearn's stub types `increasing` as bool, but the runtime accepts
    # bool | "auto" - suppress the stub's too-narrow type.
    iso = IsotonicRegression(
        out_of_bounds="clip",
        y_min=y_min,
        y_max=y_max,
        increasing=increasing,  # pyright: ignore[reportArgumentType]
    )
    iso.fit(x, y)
    return iso


def isotonic_to_dict(iso: IsotonicRegression, outcome_name: str) -> dict[str, Any]:
    """Serialize one fitted regressor to the calibrator.json per-class entry.

    Key order is part of the byte-level contract (json.dumps follows insertion
    order): ``outcome, x_thresholds, y_thresholds, y_min, y_max, out_of_bounds``.
    The Java loaders read only x/y thresholds; the extra fields exist for the
    Python round-trip and are ignored by Java.
    """
    return {
        "outcome": outcome_name,
        "x_thresholds": iso.X_thresholds_.astype(float).tolist(),
        "y_thresholds": iso.y_thresholds_.astype(float).tolist(),
        "y_min": float(iso.y_min) if iso.y_min is not None else None,
        "y_max": float(iso.y_max) if iso.y_max is not None else None,
        "out_of_bounds": iso.out_of_bounds,
    }


def isotonic_from_dict(d: dict[str, Any]) -> IsotonicRegression:
    """Rebuild a fitted IsotonicRegression from an :func:`isotonic_to_dict` entry.

    Recreates the fitted state by stuffing the breakpoint arrays into the
    attributes sklearn looks at on transform, then rebuilds the interpolator
    via the private ``_build_f`` (sklearn builds it lazily inside ``fit()`` but
    not on attribute restore). This is the one sanctioned use of sklearn
    privates in the project - if an sklearn upgrade breaks it, it breaks HERE,
    with the round-trip tests in ``tests/eval/test_calibration.py`` as the
    tripwire.
    """
    iso = IsotonicRegression(
        out_of_bounds=d.get("out_of_bounds", "clip"),
        y_min=d.get("y_min"),
        y_max=d.get("y_max"),
    )
    iso.X_thresholds_ = np.asarray(d["x_thresholds"], dtype=np.float64)
    iso.y_thresholds_ = np.asarray(d["y_thresholds"], dtype=np.float64)
    iso.X_min_ = float(iso.X_thresholds_.min()) if iso.X_thresholds_.size else 0.0
    iso.X_max_ = float(iso.X_thresholds_.max()) if iso.X_thresholds_.size else 1.0
    # Nominal on restore: transform correctness comes from the interpolator rebuilt
    # from the stored thresholds below, not from this flag - do not "fix" it.
    iso.increasing_ = True
    iso._build_f(iso.X_thresholds_, iso.y_thresholds_)
    return iso


def apply_per_class_isotonic(
    calibrators: list[IsotonicRegression], raw_probs: np.ndarray
) -> np.ndarray:
    """Per-class isotonic transform + eps floor + row renormalise, for 2-D (N, K) probs.

    The shared body of the LGBM/LR ``predict_proba_calibrated`` implementations:
    each class column goes through its own regressor, results are floored at
    1e-9 (isotonic can map to exactly 0 on the low end, which would produce NaN
    if a whole row zeroed out), rows renormalise to sum to 1, and the result is
    float32 to match the serving dtype.
    """
    raw = np.asarray(raw_probs, dtype=np.float64)
    if len(calibrators) != raw.shape[1]:
        raise ValueError(
            f"raw_probs has {raw.shape[1]} classes; got {len(calibrators)} calibrators"
        )
    calibrated = np.empty_like(raw)
    for c in range(raw.shape[1]):
        calibrated[:, c] = calibrators[c].transform(raw[:, c])
    calibrated = np.maximum(calibrated, 1e-9)
    return (calibrated / calibrated.sum(axis=-1, keepdims=True)).astype(np.float32)


__all__ = (
    "apply_per_class_isotonic",
    "fit_isotonic",
    "isotonic_from_dict",
    "isotonic_to_dict",
)
