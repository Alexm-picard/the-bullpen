"""Compute the drift-baseline blocks that the Java ``TrainingDistributionLoader`` consumes.

Wave E / E-1. A registered champion's ``metadata.json`` needs two additive top-level keys so the
worker PSI jobs can compute drift against a training-time reference:

- ``feature_distributions``: per observable-feature reference. Continuous features carry a raw
  ``sample`` array (the Java side derives 10 equal-frequency quantile edges from it via
  ``Psi.computeContinuous`` - so it must be raw values, NOT pre-binned); categorical features carry
  ``counts`` (a category -> integer histogram).
- ``training_prediction_distribution``: per served class, a raw ``sample`` array of the model's
  calibrated per-row probability for that class over the training frame (again raw, the Java side
  bins it).

Both blocks are additive metadata keys. They do NOT affect the rule-7 feature schema hash, which is
computed over ``feature_pipeline.json`` only (see ``registry_client.feature_hasher`` /
``FeatureSchemaHasher`` on the Java side), so writing them into an existing ``metadata.json`` cannot
trip the registration gate.

CRITICAL - the ``feature_distributions`` KEYS must match the observed side. The worker fetches the
observed distribution from ``prediction_log.features``, which is the serialized REQUEST DTO
(camelCase field names like ``launchSpeedMph`` / ``parkId``), NOT the transformed model feature
vector (snake_case ``launch_speed_mph`` / one-hots). PSI joins reference vs observed by exact key,
and a mismatch fails SILENTLY (empty observed -> the job skips the feature, no error). So the caller
must key these blocks by the request-field names and confirm the exact casing against a real logged
row. This module takes the key->column mapping as input and stays agnostic to that choice; the CLI
owns it.

No RNG: sub-sampling is a deterministic, quantile-representative stride over the sorted values, so
runs are reproducible and there is no ``random_state`` anywhere (this is a distribution summary, not
a data split - the temporal-split rule does not apply, but determinism is still the right default).
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import cast

import numpy as np
import pandas as pd

#: Default cap on a reference ``sample`` array. Enough to stabilize 10 quantile edges while keeping
#: ``metadata.json`` bounded (the Java loader reads the whole array into memory, uncapped).
DEFAULT_MAX_SAMPLE = 5000


def _quantile_representative_sample(values: np.ndarray, max_sample: int) -> list[float]:
    """Sorted, evenly-spaced (by rank) sub-sample of ``values`` - deterministic, no RNG.

    Dropping NaN then sorting and taking ``max_sample`` evenly-spaced ranks preserves the shape the
    downstream quantile-edge computation cares about far better than a head/tail slice would.
    """
    clean = values[~np.isnan(values)] if np.issubdtype(values.dtype, np.floating) else values
    clean = np.sort(clean)
    if clean.size == 0:
        return []
    if clean.size <= max_sample:
        return [float(v) for v in clean]
    idx = np.linspace(0, clean.size - 1, max_sample).astype(int)
    return [float(v) for v in clean[idx]]


def _continuous_block(series: pd.Series, max_sample: int) -> dict:
    return {
        "kind": "continuous",
        "sample": _quantile_representative_sample(series.to_numpy(dtype=float), max_sample),
    }


def _categorical_block(series: pd.Series) -> dict:
    # Keys must match the observed side, which reads the JSONExtract token stripped of quotes. A
    # whole-number categorical (base_state / outs / count_balls) must render as "0"/"1", NOT "1.0" -
    # the request logs them as JSON ints - so cast a numeric series to int first; string categories
    # ("BOS") pass through unchanged.
    clean = series.dropna()
    if pd.api.types.is_numeric_dtype(clean):
        clean = clean.astype("int64")
    counts = clean.astype(str).value_counts()
    return {"kind": "categorical", "counts": {str(k): int(v) for k, v in counts.items()}}


def compute_feature_distributions(
    df: pd.DataFrame,
    continuous: Mapping[str, str],
    categorical: Mapping[str, str],
    max_sample: int = DEFAULT_MAX_SAMPLE,
) -> dict:
    """Build the ``feature_distributions`` block.

    ``continuous`` / ``categorical`` map an OUTPUT KEY (the request-field name the observed side
    logs) to the SOURCE COLUMN in ``df`` (the training frame). Keeping them distinct is what lets
    the reference be keyed in request space while the values come from the snapshot columns.
    """
    out: dict[str, dict] = {}
    for key, col in continuous.items():
        out[key] = _continuous_block(cast(pd.Series, df[col]), max_sample)
    for key, col in categorical.items():
        out[key] = _categorical_block(cast(pd.Series, df[col]))
    return out


def compute_prediction_distribution(
    proba: np.ndarray,
    class_labels: list[str],
    max_sample: int = DEFAULT_MAX_SAMPLE,
) -> dict:
    """Build ``training_prediction_distribution`` from CALIBRATED served probabilities.

    ``proba`` is ``(n_rows, n_classes)`` of the model's calibrated (served) per-class probability
    over the training frame - the same space the observed ``prediction.probabilities`` map lives
    in. Each class gets a raw, quantile-representative sample of its probability column.
    """
    if proba.ndim != 2 or proba.shape[1] != len(class_labels):
        raise ValueError(
            f"proba shape {proba.shape} does not match {len(class_labels)} class labels"
        )
    return {
        label: _quantile_representative_sample(proba[:, j], max_sample)
        for j, label in enumerate(class_labels)
    }
