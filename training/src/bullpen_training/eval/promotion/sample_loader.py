"""Sample-data FeatureLoader for the promotion-evidence driver (W5, Mac side).

The production loaders (``pitch.train_pre.make_feature_loader``,
``battedball.lr_baseline``) read from ClickHouse, which lives on the box
(ADR-0006). On the Mac the equivalent of the box's feature table is the
``samples/dev/`` parquet mirror (ADR-0007: small stratified samples pulled
from R2 / served by MinIO offline). This module provides:

1. ``ParquetSampleLoader`` - a ``cv_harness.FeatureLoader`` that reads
   per-year parquet files from a directory laid out as

       <root>/<dataset>/year=<YYYY>.parquet

   and returns the rows for the requested ``[start_year, end_year]`` span with
   a ``label`` column + the model's feature columns. This is the SAME parquet
   the box would sync to ``samples/dev/`` - the loader does not care whether
   the file came from R2, MinIO, or the generator below.

2. ``generate_sample_dataset`` - a deterministic, leakage-clean-BY-CONSTRUCTION
   sample generator. It materialises one parquet per CV year so the gate can be
   proven end-to-end on the Mac with NO ClickHouse and NO MinIO. Each year is
   generated INDEPENDENTLY (no cross-year carry, so no temporal contamination
   is even representable), with a stable learnable signal so a non-linear
   challenger (LightGBM) genuinely outscores the linear LR baseline - which is
   what makes the challenger-vs-baseline gate demonstrate a real verdict rather
   than a coin-flip.

2026 is NEVER generated and the loader REFUSES any year >= 2026 (rule 13): the
sample mirror is a training/validation artifact only; 2026 is holdout.

The generator's signal is a deliberately non-linear function of the features
(a product / threshold interaction) so the LR baseline - linear in the feature
space - cannot capture it as well as the tree challenger. This is a property of
the GENERATOR, not a tuned outcome: the gate still computes the honest verdict.
"""

from __future__ import annotations

import hashlib
from collections.abc import Sequence
from pathlib import Path
from typing import Final, cast

import numpy as np
import pandas as pd

from bullpen_training.battedball.features_shared import FEATURE_NAMES

# Holdout fence (rule 13). The loader + generator both refuse 2026+.
HOLDOUT_YEAR: Final[int] = 2026

# The two sample datasets this W5 driver evidences. Dataset name -> the feature
# columns the parquet must carry (besides `label`). Kept here so the driver and
# the generator agree on the on-disk schema.
PITCH_FEATURES: Final[tuple[str, ...]] = (
    "count_balls",
    "count_strikes",
    "outs",
    "inning",
    "base_state",
    "score_diff",
    "pitcher_throws_int",
    "batter_stand_int",
    "park_id_int",
    "pitcher_te_in_play",
    "batter_te_in_play",
    "pitcher_strike_rate_28d",
    "batter_inplay_rate_28d",
)
PITCH_POST_EXTRA: Final[tuple[str, ...]] = (
    "release_speed_mph",
    "plate_x_in",
    "plate_z_in",
    "pitch_type_int",
)
# The per-park MLP champion AND its co-registered LR baseline both train on the production
# FEATURE_NAMES (15: the 4 physics measures + stand one-hot + the 8-dim base_state one-hot + outs).
# The CV reuses that exact tuple so the H2 gate certifies the SERVED champion's representation, not
# a reduced proxy (the served contract carries base_state_0..7). base_state is ~irrelevant to the
# batted-ball OUTCOME class but it IS what production trains, so faithful > tidy here.
BATTED_BALL_FEATURES: Final[tuple[str, ...]] = FEATURE_NAMES

# The retrodicted 5-outcome label DISTRIBUTION columns the per-park MLP champion trains on (KL
# loss), distinct from the integer `label` (the realized outcome the CV harness scores against).
# On the box these come from `bbip_retrodicted_labels`; in the sample they are synthesised.
RETRO_COLS: Final[tuple[str, ...]] = tuple(f"retro_{i}" for i in range(5))

# A small synthetic park set so the per-park MLP CV exercises the per-park train+route path without
# the full 30-park cost on the Mac sample (the box full-data run carries the real 30 parks).
SAMPLE_PARKS: Final[tuple[str, ...]] = ("BOS", "NYY", "LAD", "SFG", "COL", "HOU")

# n_classes per dataset (pitch outcome = 5 labels; batted-ball = 5 outcomes).
N_CLASSES: Final[dict[str, int]] = {
    "pitch_outcome_pre": 5,
    "pitch_outcome_post": 5,
    "batted_ball_lr_baseline": 5,
    "batted_ball_mlp": 5,
    # The served-champion REGISTRY name (decision [166]). Defensive/consistency only: the carry
    # promotion path bypasses run_evidence (the sole N_CLASSES consumer), so SEGMENT_COLS below is
    # the load-bearing add (read by experiment_results_artifact). Same per-park 5-outcome family.
    "battedball_outcome": 5,
}

# segment columns used by the per-segment breakdown in the artifact - chosen
# per dataset so the artifact doesn't reference pitch columns for batted-ball.
SEGMENT_COLS: Final[dict[str, tuple[str, ...]]] = {
    "pitch_outcome_pre": ("count_strikes", "batter_stand_int"),
    "pitch_outcome_post": ("count_strikes", "batter_stand_int"),
    "batted_ball_lr_baseline": ("stand_R",),
    "batted_ball_mlp": ("park",),
    # carry-promotion eval evidences the served champion under its registry name (decision [166]).
    "battedball_outcome": ("park",),
}


def feature_cols_for(dataset: str) -> tuple[str, ...]:
    if dataset == "pitch_outcome_pre":
        return PITCH_FEATURES
    if dataset == "pitch_outcome_post":
        return (*PITCH_FEATURES, *PITCH_POST_EXTRA)
    if dataset in ("batted_ball_lr_baseline", "batted_ball_mlp"):
        return BATTED_BALL_FEATURES
    raise ValueError(f"unknown sample dataset {dataset!r}")


# ---------------------------------------------------------------------------
# Parquet loader (the FeatureLoader the CV harness drives)
# ---------------------------------------------------------------------------


class ParquetSampleLoader:
    """A ``cv_harness.FeatureLoader`` over a ``samples/dev/`` parquet mirror.

    ``loader(start_year, end_year, fold_id) -> DataFrame`` reads every
    ``<root>/<dataset>/year=<YYYY>.parquet`` for ``YYYY`` in
    ``[start_year, end_year]`` and concatenates them. The harness calls this
    three times per fold (train / val / test) with the fold's documented year
    spans; each call reads only the years it needs, so no future year is ever
    resident during an earlier split's load (the on-disk per-year split IS the
    streaming temporal cutoff for this sampled path).
    """

    def __init__(self, root: Path, dataset: str) -> None:
        self.root = Path(root)
        self.dataset = dataset
        self.feature_cols = feature_cols_for(dataset)

    def _year_path(self, year: int) -> Path:
        return self.root / self.dataset / f"year={year}.parquet"

    def __call__(self, start_year: int, end_year: int, fold_id: int) -> pd.DataFrame:
        if start_year >= HOLDOUT_YEAR or end_year >= HOLDOUT_YEAR:
            raise ValueError(
                f"rule 13: {HOLDOUT_YEAR} is holdout-only; sample loader refuses "
                f"a span touching it (got {start_year}-{end_year})"
            )
        frames: list[pd.DataFrame] = []
        for year in range(start_year, end_year + 1):
            path = self._year_path(year)
            if not path.is_file():
                raise FileNotFoundError(
                    f"sample parquet missing: {path}. Generate the sample with "
                    "`generate_sample_dataset(...)` or sync samples/dev/ from R2."
                )
            frames.append(pd.read_parquet(path))
        df = pd.concat(frames, ignore_index=True)
        required = [*self.feature_cols, "label"]
        missing = [c for c in required if c not in df.columns]
        if missing:
            raise ValueError(
                f"sample parquet for {self.dataset} is missing columns {missing}; "
                f"present: {sorted(df.columns)}"
            )
        # Also surface the segment column(s) + the retrodicted-distribution columns when present:
        # the per-park MLP model_factory routes by `park` (a segment col, not a feature) and trains
        # on `retro_*`. Present-only, so pitch/LR datasets that lack these are unaffected.
        optional = [
            c
            for c in (*SEGMENT_COLS.get(self.dataset, ()), *RETRO_COLS)
            if c in df.columns and c not in required
        ]
        keep = [*required, *optional]
        # list-indexing returns a DataFrame at runtime; cast pins it for the type
        # checker (the pandas stubs widen df[[...]] to Series | DataFrame).
        return cast(pd.DataFrame, df[keep])


# ---------------------------------------------------------------------------
# Deterministic, leakage-clean sample generator (Mac proof-of-path)
# ---------------------------------------------------------------------------


def _years_for_folds() -> tuple[int, ...]:
    """Every year the 4 rolling-origin folds touch: 2015-2025 (rule 13: no 2026)."""
    return tuple(range(2015, HOLDOUT_YEAR))  # 2015..2025


def _rng_for_year(dataset: str, year: int) -> np.random.Generator:
    """Per-(dataset, year) generator seeded ONLY by (dataset, year).

    This is the structural anti-leakage property: each year's rows are drawn
    independently of every other year's, so there is no cross-year carry to
    contaminate a later fold. The seed is NOT a data split seed (rule:
    no random_state on splits) - it is the generator's own determinism for the
    SYNTHETIC sample, and the splits remain pure date windows.

    The seed uses a STABLE hash (blake2b), NOT Python's builtin ``hash()``,
    which is per-process randomised for strings (PYTHONHASHSEED) and would make
    the sample differ between runs - and so make the gate verdict flap.
    """
    digest = hashlib.blake2b(f"{dataset}:{year}".encode(), digest_size=8).digest()
    seed = int.from_bytes(digest, "big") % (2**31) + 1
    return np.random.default_rng(seed)


def _generate_pitch_year(rng: np.random.Generator, n: int, *, post: bool) -> pd.DataFrame:
    count_balls = rng.integers(0, 4, n)
    count_strikes = rng.integers(0, 3, n)
    outs = rng.integers(0, 3, n)
    inning = rng.integers(1, 10, n)
    base_state = rng.integers(0, 8, n)
    score_diff = rng.integers(-5, 6, n)
    throws = rng.integers(0, 2, n)
    stand = rng.integers(0, 2, n)
    park = rng.integers(0, 30, n)
    pitcher_te = rng.uniform(0.1, 0.35, n).astype("float32")
    batter_te = rng.uniform(0.1, 0.35, n).astype("float32")
    pitcher_sr = rng.uniform(0.55, 0.75, n).astype("float32")
    batter_ip = rng.uniform(0.1, 0.3, n).astype("float32")

    # Non-linear signal: an interaction (count pressure x handedness match) plus
    # a threshold on a continuous rate - the kind of structure a tree captures
    # but a single linear LR cannot fully separate. Drives the 5-class label.
    pressure = (count_strikes == 2).astype(np.float64) * (count_balls < 2).astype(np.float64)
    match = (throws == stand).astype(np.float64)
    score = 1.6 * pressure * match + 2.2 * (pitcher_sr > 0.65).astype(np.float64) + batter_ip
    noise = rng.normal(0.0, 0.6, n)
    raw = score + noise
    # Map the continuous score to 5 ordered-ish classes via quantile cuts.
    qs = np.quantile(raw, [0.2, 0.4, 0.6, 0.8])
    label = np.digitize(raw, qs).astype(np.int64)

    cols: dict[str, np.ndarray] = {
        "count_balls": count_balls.astype("int16"),
        "count_strikes": count_strikes.astype("int16"),
        "outs": outs.astype("int16"),
        "inning": inning.astype("int16"),
        "base_state": base_state.astype("int16"),
        "score_diff": score_diff.astype("int16"),
        "pitcher_throws_int": throws.astype("int8"),
        "batter_stand_int": stand.astype("int8"),
        "park_id_int": park.astype("int16"),
        "pitcher_te_in_play": pitcher_te,
        "batter_te_in_play": batter_te,
        "pitcher_strike_rate_28d": pitcher_sr,
        "batter_inplay_rate_28d": batter_ip,
        "label": label,
    }
    if post:
        # Tier-4 early-flight features - give the post head a sharper signal
        # (release speed threshold + plate location) so it can outscore LR by
        # at least as much as the pre head.
        release = rng.uniform(85.0, 100.0, n).astype("float32")
        plate_x = rng.normal(0.0, 8.0, n).astype("float32")
        plate_z = rng.normal(24.0, 10.0, n).astype("float32")
        pitch_type = rng.integers(0, 6, n).astype("int8")
        # Sharpen the label with a flight-aware interaction (does not leak: it
        # is part of THIS row's post-pitch input, available at post-pitch time).
        flight = (
            1.4 * (release > 94.0).astype(np.float64) * (np.abs(plate_x) < 5.0).astype(np.float64)
        )
        raw2 = raw + flight
        qs2 = np.quantile(raw2, [0.2, 0.4, 0.6, 0.8])
        cols["label"] = np.digitize(raw2, qs2).astype(np.int64)
        cols["release_speed_mph"] = release
        cols["plate_x_in"] = plate_x
        cols["plate_z_in"] = plate_z
        cols["pitch_type_int"] = pitch_type
    return pd.DataFrame(cols)


def _generate_batted_ball_year(rng: np.random.Generator, n: int) -> pd.DataFrame:
    launch_speed = rng.uniform(60.0, 115.0, n).astype("float32")
    launch_angle = rng.uniform(-30.0, 50.0, n).astype("float32")
    spray = rng.uniform(-45.0, 45.0, n).astype("float32")
    distance = rng.uniform(50.0, 450.0, n).astype("float32")
    stand = rng.integers(0, 2, n)
    stand_r = (stand == 0).astype("float32")
    stand_l = (stand == 1).astype("float32")
    outs = rng.integers(0, 3, n)

    # Outcome signal: classic barrel interaction (high EV + good launch angle ->
    # extra bases / HR), non-linear in (speed, angle). out/1b/2b/3b/hr = 0..4.
    barrel = (launch_speed > 98.0).astype(np.float64) * (
        (launch_angle > 18.0) & (launch_angle < 35.0)
    ).astype(np.float64)
    dist_n = distance / 450.0
    score = 3.0 * barrel + 1.5 * dist_n + 0.4 * (launch_speed / 115.0)
    noise = rng.normal(0.0, 0.4, n)
    raw = score + noise
    qs = np.quantile(raw, [0.55, 0.78, 0.9, 0.97])  # most BIP are outs
    label = np.digitize(raw, qs).astype(np.int64)
    # base_state drawn AFTER the physics/label draws (purely additive, does not perturb them) and
    # deliberately NOT a label driver: ~irrelevant to a batted ball's outcome CLASS (it matters for
    # run-scoring, not single-vs-HR). Carried so the sample exercises the full 15-feature
    # FEATURE_NAMES vector the champion trains on.
    base_state = rng.integers(0, 8, n)

    df = pd.DataFrame(
        {
            "launch_speed_mph": launch_speed,
            "launch_angle_deg": launch_angle,
            "spray_angle_deg": spray,
            "hit_distance_ft": distance,
            "stand_R": stand_r,
            "stand_L": stand_l,
            "outs": outs.astype("int16"),
            "label": label,
        }
    )
    # base_state one-hot (FEATURE_NAMES positions 6..13), matching battedball.base_state_one_hot.
    for b in range(8):
        df[f"base_state_{b}"] = (base_state == b).astype("float32")
    return df


def _generate_batted_ball_mlp_year(rng: np.random.Generator, n: int) -> pd.DataFrame:
    """Per-park MLP champion sample: the batted-ball features + the integer outcome `label`, plus a
    `park` segment and a synthetic retrodicted 5-outcome DISTRIBUTION (``retro_*``).

    The per-park MLP trains on the retrodicted distribution (KL loss), NOT the realized label;
    the CV harness scores predict_proba against the realized integer `label`. The synthetic retro
    is a noisy softmax peaked near the true class - correlated with, but not equal to, the realized
    outcome, the way the physics retrodiction is. Box full-data carries the real
    `bbip_retrodicted_labels`.
    """
    df = _generate_batted_ball_year(rng, n)
    label = df["label"].to_numpy()
    df["park"] = rng.choice(np.asarray(SAMPLE_PARKS), size=n)
    onehot = np.eye(5, dtype=np.float64)[label]
    logits = 2.2 * onehot + rng.normal(0.0, 0.6, size=(n, 5))
    retro = np.exp(logits - logits.max(axis=1, keepdims=True))
    retro = retro / retro.sum(axis=1, keepdims=True)
    for i in range(5):
        df[f"retro_{i}"] = retro[:, i].astype("float32")
    return df


def generate_sample_dataset(
    root: Path,
    dataset: str,
    *,
    rows_per_year: int = 1_500,
    years: Sequence[int] | None = None,
) -> Path:
    """Write one ``year=<YYYY>.parquet`` per CV year under ``<root>/<dataset>/``.

    Deterministic for a fixed ``(dataset, rows_per_year, years)``. Refuses to
    generate 2026+ (rule 13). Returns the dataset directory.
    """
    out_dir = Path(root) / dataset
    out_dir.mkdir(parents=True, exist_ok=True)
    year_list = tuple(years) if years is not None else _years_for_folds()
    for year in year_list:
        if year >= HOLDOUT_YEAR:
            raise ValueError(
                f"rule 13: refusing to generate sample year {year} (>= {HOLDOUT_YEAR} is holdout)"
            )
        rng = _rng_for_year(dataset, year)
        if dataset == "pitch_outcome_pre":
            df = _generate_pitch_year(rng, rows_per_year, post=False)
        elif dataset == "pitch_outcome_post":
            df = _generate_pitch_year(rng, rows_per_year, post=True)
        elif dataset == "batted_ball_lr_baseline":
            df = _generate_batted_ball_year(rng, rows_per_year)
        elif dataset == "batted_ball_mlp":
            df = _generate_batted_ball_mlp_year(rng, rows_per_year)
        else:
            raise ValueError(f"unknown sample dataset {dataset!r}")
        df.to_parquet(out_dir / f"year={year}.parquet", index=False)
    return out_dir


__all__ = (
    "BATTED_BALL_FEATURES",
    "HOLDOUT_YEAR",
    "N_CLASSES",
    "PITCH_FEATURES",
    "PITCH_POST_EXTRA",
    "RETRO_COLS",
    "SAMPLE_PARKS",
    "SEGMENT_COLS",
    "ParquetSampleLoader",
    "feature_cols_for",
    "generate_sample_dataset",
)
