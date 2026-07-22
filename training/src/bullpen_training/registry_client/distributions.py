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
from dataclasses import dataclass, field
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


# --- champion baseline registry + block emission (hoisted from the backfill CLI for reuse) --------
# The request-key config + the source-space decoders live here (torch-free: numpy + pandas only) so
# BOTH the backfill CLI and the native trainer emission produce byte-identical blocks, and the
# prod-confirmed request-key casing is defined once.


@dataclass(frozen=True)
class ChampionConfig:
    """Per-champion baseline spec.

    Request keys are prod-confirmed (2026-07-04); source columns are training-frame columns AFTER
    family prep (reconstruct / decode).
    """

    model_name: str
    class_labels: list[str]
    continuous: dict[str, str]
    categorical: dict[str, str]
    # Documentary only: records request keys deliberately NOT drift-watched (they never enter the
    # block). `continuous` + `categorical` are the sole functional inputs - a key is suppressed by
    # its ABSENCE from those, not by listing it here.
    excluded: list[str] = field(default_factory=list)


# Keys confirmed against prod prediction_log.features 2026-07-04 (real logged rows, both champions).
# Do NOT "fix" the camelCase request keys to snake_case model names - PSI joins observed<->reference
# by EXACT key and a mismatch fails silently.
CHAMPIONS: dict[str, ChampionConfig] = {
    "battedball_outcome": ChampionConfig(
        model_name="battedball_outcome",
        class_labels=["out", "1b", "2b", "3b", "hr"],
        continuous={
            "launchSpeedMph": "launch_speed_mph",
            "launchAngleDeg": "launch_angle_deg",
            "sprayAngleDeg": "spray_angle_deg",
            "hitDistanceFt": "hit_distance_ft",
        },
        # reconstructed from the model one-hots by reconstruct_battedball_categoricals.
        categorical={"stand": "stand_str", "baseState": "base_state_int", "outs": "outs"},
        # No parkId / releaseSpeedMph: absent from the battedball request (would silent-skip).
        excluded=["parkId", "releaseSpeedMph"],
    ),
    "pitch_outcome_post": ChampionConfig(
        model_name="pitch_outcome_post",
        class_labels=["ball", "called_strike", "swinging_strike", "foul", "in_play"],
        continuous={
            "releaseSpeedMph": "release_speed_mph",
            "plateXIn": "plate_x_in",
            "plateZIn": "plate_z_in",
            "pfxXIn": "pfx_x_in",
            "pfxZIn": "pfx_z_in",
            "spinRateRpm": "spin_rate_rpm",
            "spinAxisDeg": "spin_axis_deg",
            "releasePosXIn": "release_pos_x_in",
            "releasePosZIn": "release_pos_z_in",
        },
        # park_id/pitch_type/pitcher_throws/batter_stand decoded from the parquet _int cols by
        # decode_pitch_categoricals; the numeric ints (count_balls...) are request-space already.
        categorical={
            "pitcherThrows": "pitcher_throws",
            "batterStand": "batter_stand",
            "parkId": "park_id",
            "pitchType": "pitch_type",
            "countBalls": "count_balls",
            "countStrikes": "count_strikes",
            "outs": "outs",
            "inning": "inning",
            "baseState": "base_state",
            "scoreDiff": "score_diff",
            "dow": "dow",
        },
        excluded=["pitcherId", "batterId"],  # high-cardinality IDs, meaningless as drift features.
    ),
    "pitch_outcome_pre": ChampionConfig(
        model_name="pitch_outcome_pre",
        class_labels=["ball", "called_strike", "swinging_strike", "foul", "in_play"],
        # The pre head consumes PITCH_FEATURE_COLUMNS (31), which carries NO Tier-4 request-space
        # continuous features (release / plate / spin all land post-release, absent pre-pitch). So
        # the drift reference is categorical-only - the same Tier-1 request keys post uses, minus
        # pitchType (a Tier-4 categorical). Tier-2 target-encodings + Tier-3 rolling form are
        # server-DERIVED, not request-logged, so they are out of the drift block on both heads.
        continuous={},
        categorical={
            "pitcherThrows": "pitcher_throws",
            "batterStand": "batter_stand",
            "parkId": "park_id",
            "countBalls": "count_balls",
            "countStrikes": "count_strikes",
            "outs": "outs",
            "inning": "inning",
            "baseState": "base_state",
            "scoreDiff": "score_diff",
            "dow": "dow",
        },
        # pitcherId/batterId: high-cardinality IDs (as post). pitchType + the Tier-4 measured values
        # may ride in a shared PitchRequest but the pre MODEL never sees them, so they are not drift
        # features here.
        excluded=["pitcherId", "batterId", "pitchType"],
    ),
}


def _resolve_present(
    frame: pd.DataFrame, mapping: dict[str, str]
) -> tuple[dict[str, str], list[str]]:
    """Split a key->column mapping into (present, missing) against the frame's columns."""
    present = {k: c for k, c in mapping.items() if c in frame.columns}
    missing = [f"{k} <- {c}" for k, c in mapping.items() if c not in frame.columns]
    return present, missing


def build_feature_block(frame: pd.DataFrame, cfg: ChampionConfig, max_sample: int) -> dict:
    """feature_distributions for ``cfg`` from ``frame``.

    Raises ``ValueError`` on a missing source column rather than silently emitting a partial
    reference the box can't diagnose.
    """
    cont, miss_c = _resolve_present(frame, cfg.continuous)
    cat, miss_cat = _resolve_present(frame, cfg.categorical)
    missing = miss_c + miss_cat
    if missing:
        raise ValueError(
            f"[{cfg.model_name}] training frame is missing source columns for: {missing}. "
            "Confirm the source-schema column names against the parquet / ClickHouse schema."
        )
    return compute_feature_distributions(
        frame, continuous=cont, categorical=cat, max_sample=max_sample
    )


def reconstruct_battedball_categoricals(df: pd.DataFrame) -> pd.DataFrame:
    """Add request-space ``stand_str`` + ``base_state_int`` from the model one-hots.

    The model matrix carries the one-hots (stand_R/stand_L, base_state_0..7); the observed side logs
    the raw request ``stand`` ("L"/"R") and ``baseState`` (int), so recover those to join.
    """
    out = df.copy()
    out["stand_str"] = np.where(df["stand_L"] == 1, "L", "R")
    base_cols = [f"base_state_{i}" for i in range(8)]
    out["base_state_int"] = df[base_cols].to_numpy().argmax(axis=1)
    return out


def decode_pitch_categoricals(
    df: pd.DataFrame, park_by_int: dict[int, str], ptype_by_int: dict[int, str]
) -> pd.DataFrame:
    """Decode the pitch parquet's _int categoricals to request space.

    throws/stand use {0:"L", 1:"R"} (STAND_TO_INT/THROWS_TO_INT; R is the null fallback, so the int
    is always 0 or 1); park/pitch_type invert the bundle's name->int mapping.

    pitch_type is a Tier-4 column the pre head does not carry: when ``pitch_type_int`` is absent
    (a pre-head parquet) the pitch_type decode is skipped, and ``ptype_by_int`` may be empty.
    """
    throws_stand = {0: "L", 1: "R"}
    out = df.copy()
    out["park_id"] = df["park_id_int"].map(park_by_int.get)
    if "pitch_type_int" in df.columns:
        out["pitch_type"] = df["pitch_type_int"].map(ptype_by_int.get)
    out["pitcher_throws"] = df["pitcher_throws_int"].map(throws_stand.get)
    out["batter_stand"] = df["batter_stand_int"].map(throws_stand.get)
    return out


def battedball_feature_block_from_matrix(
    features: np.ndarray,
    feature_names: tuple[str, ...] | list[str],
    *,
    max_sample: int = DEFAULT_MAX_SAMPLE,
) -> dict:
    """``feature_distributions`` for the battedball family straight from the training matrix.

    The NATIVE-TRAINER entry point (E-1 part 2): at metadata-write time a battedball trainer
    holds the ``(N, 15)`` ``FEATURE_NAMES``-ordered float matrix in memory (the model one-hots,
    not the request fields), so this wraps matrix -> named frame -> request-space reconstruction
    -> :func:`build_feature_block` with the prod-confirmed ``battedball_outcome`` request keys.
    Same code path as the backfill CLI (which goes ``rows_to_frame`` -> the same reconstruction
    -> the same block builder), so a native emission and a backfill over the same rows are
    byte-identical.

    The caller passes its TRAIN-slice matrix: the drift reference is the distribution the model
    LEARNED from; the held-out calibration season ([51]) is deliberately not part of it.
    """
    # No dtype coercion: the production matrices are float32 (load_arrays), exactly like the
    # CLI's rows_to_frame columns, and the per-column float64 cast happens identically inside
    # _continuous_block on both paths. Forcing float64 here would only double the transient
    # footprint of the box's ~1.2M-row matrix during a retrain (C-31's memory lessons).
    frame = reconstruct_battedball_categoricals(
        pd.DataFrame(np.asarray(features), columns=list(feature_names))
    )
    return build_feature_block(frame, CHAMPIONS["battedball_outcome"], max_sample)


def emit_distribution_blocks(
    frame: pd.DataFrame,
    cfg: ChampionConfig,
    proba: np.ndarray,
    *,
    max_sample: int = DEFAULT_MAX_SAMPLE,
) -> tuple[dict, dict]:
    """Compute both baseline blocks for a champion in one call.

    The shared entry point the backfill CLI and native trainer emission both use, so their output
    is byte-identical. ``frame`` must already carry the request-space source columns ``cfg`` maps to
    (post reconstruct / decode); ``proba`` is the champion's calibrated served probabilities
    ``(n_rows, n_classes)``. Returns ``(feature_distributions, training_prediction_distribution)``.
    """
    feature_block = build_feature_block(frame, cfg, max_sample)
    prediction_block = compute_prediction_distribution(proba, cfg.class_labels, max_sample)
    return feature_block, prediction_block
