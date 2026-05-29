"""Rookie prototype clustering.

Rookies (and any pitcher with < 500 career MLB pitches in our data window)
have unreliable career features: their pitch-mix percentages, average
velocity, and biomechanics are estimated from a tiny sample, and their
learned transformer embedding is essentially untrained (it maps to the
padding row). The default model therefore falls back to league-average
behaviour for them.

This module instead borrows a *prototype* profile from established
pitchers who look similar. We:

  1. Cluster established pitchers (>= 1000 training pitches) on their
     physical metrics (velocity, extension, arm angle, handedness) plus
     their arsenal (pitch-mix fractions), using k-means.
  2. For each rookie pitch, assign the rookie to the nearest cluster from
     a *streaming, prior-only* profile (an expanding mean over the
     pitches the rookie has thrown so far — never the current pitch).
  3. Substitute the cluster's prototype career features (and, optionally,
     the cluster's prototype embedding) in place of the rookie's
     unreliable values before prediction.
  4. Stop once the pitcher reaches 500 career pitches — from then on the
     default model uses the pitcher's own (now well-estimated) profile.

Leakage discipline:
  - Clusters are fit on TRAIN-season established pitchers only.
  - The cumulative pitch count and the streaming assignment profile use
    strictly prior pitches (group cumsum minus the current row), so the
    pitch being predicted never informs its own rookie flag or cluster.
  - Career-stat values used as prototypes are the same train-only career
    aggregates the default model already consumes.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Final

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.preprocessing import StandardScaler

from bullpen_training.pitch_comparison.data import PITCH_TYPE_CLASSES

# Per-pitcher career features the model consumes that we replace with a
# cluster prototype for rookies. Subset of FEATURE_COLS + CONTEXT biomech.
SUBSTITUTE_FEATURE_COLS: Final[tuple[str, ...]] = (
    "pitcher_avg_velo",
    "pitcher_ff_pct",
    "pitcher_sl_pct",
    "pitcher_ch_pct",
    "pitcher_cu_pct",
    "pitcher_avg_extension",
    "pitcher_avg_arm_angle",
)

# Stable physical metrics used (with arsenal) to match a rookie to a cluster.
_PROFILE_PHYSICAL: Final[tuple[str, ...]] = (
    "release_speed_mph",
    "release_extension_ft",
    "arm_angle_deg",
)

ROOKIE_PITCH_THRESHOLD: Final[int] = 500
ESTABLISHED_MIN_PITCHES: Final[int] = 1000


def _arsenal_cols() -> list[str]:
    return [f"arsenal_{c}" for c in PITCH_TYPE_CLASSES]


@dataclass
class PrototypeClusters:
    scaler: StandardScaler
    kmeans: KMeans
    profile_cols: list[str]
    # cluster_id -> {career_feature_name: prototype_value}
    feature_protos: dict[int, dict[str, float]]
    # league-average career features (fallback for empty-prior rows)
    league_proto: dict[str, float]
    # profile-column fill values (train means) for empty-prior assignment
    profile_fill: dict[str, float]
    n_established: int
    n_clusters: int
    # (n_clusters, pitcher_embed_dim) prototype embeddings; set post-train.
    cluster_embeddings: np.ndarray | None = field(default=None)


def compute_cum_pitch_count(df: pd.DataFrame) -> np.ndarray:
    """Number of prior pitches per pitcher (0-based), in chronological order.

    A pitch is a "rookie" pitch when this count < ROOKIE_PITCH_THRESHOLD.
    Leakage-safe: counts only pitches strictly before the current one.
    """
    return df.groupby("pitcher_id", sort=False).cumcount().to_numpy()


def _established_profiles(train_df: pd.DataFrame) -> pd.DataFrame:
    """Per-established-pitcher physical + arsenal profile (train only)."""
    sizes = train_df.groupby("pitcher_id").size()
    established = sizes[sizes >= ESTABLISHED_MIN_PITCHES].index
    est = train_df[train_df["pitcher_id"].isin(established)]

    phys = est.groupby("pitcher_id").agg(
        release_speed_mph=("release_speed_mph", "mean"),
        release_extension_ft=("release_extension_ft", "mean"),
        arm_angle_deg=("arm_angle_deg", "mean"),
        pitcher_throws_int=("pitcher_throws_int", "first"),
    )
    # Arsenal fractions per pitcher.
    mix = (
        est.groupby(["pitcher_id", "pitch_type_int"]).size()
        .unstack(fill_value=0)
    )
    mix = mix.div(mix.sum(axis=1), axis=0)
    for k, cls in enumerate(PITCH_TYPE_CLASSES):
        col = f"arsenal_{cls}"
        phys[col] = mix[k] if k in mix.columns else 0.0
    return phys.reset_index()


def build_prototype_clusters(
    train_df: pd.DataFrame,
    *,
    n_clusters: int = 8,
    seed: int = 42,
) -> PrototypeClusters:
    """Fit k-means archetypes on established pitchers (train data only)."""
    profiles = _established_profiles(train_df)
    profile_cols = [
        "pitcher_throws_int", *(_PROFILE_PHYSICAL), *_arsenal_cols(),
    ]
    profile_fill = {
        c: float(profiles[c].mean()) for c in profile_cols
    }
    x = profiles[profile_cols].fillna(profile_fill).to_numpy(dtype=np.float64)

    scaler = StandardScaler()
    xs = scaler.fit_transform(x)
    n_clusters = min(n_clusters, len(profiles))
    kmeans = KMeans(n_clusters=n_clusters, random_state=seed, n_init=10)
    labels = kmeans.fit_predict(xs)
    profiles["cluster"] = labels

    # Per-pitcher career features (train-only career aggregates), one row
    # per established pitcher, to average into cluster prototypes.
    career = (
        train_df.groupby("pitcher_id")[list(SUBSTITUTE_FEATURE_COLS)]
        .first()
        .reset_index()
    )
    profiles = profiles.merge(career, on="pitcher_id", how="left")

    feature_protos: dict[int, dict[str, float]] = {}
    for cid in range(n_clusters):
        members = profiles[profiles["cluster"] == cid]
        feature_protos[cid] = {
            col: float(members[col].mean()) for col in SUBSTITUTE_FEATURE_COLS
        }
    league_proto = {
        col: float(train_df[col].mean()) for col in SUBSTITUTE_FEATURE_COLS
    }

    return PrototypeClusters(
        scaler=scaler,
        kmeans=kmeans,
        profile_cols=profile_cols,
        feature_protos=feature_protos,
        league_proto=league_proto,
        profile_fill=profile_fill,
        n_established=len(profiles),
        n_clusters=n_clusters,
    )


def _prior_expanding_mean(
    df: pd.DataFrame, col: str, count_prior: np.ndarray,
) -> np.ndarray:
    """Expanding mean over a pitcher's strictly-prior pitches."""
    csum = df.groupby("pitcher_id", sort=False)[col].cumsum().to_numpy()
    prior_sum = csum - df[col].to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        out = prior_sum / count_prior
    out[count_prior == 0] = np.nan
    return out


def assign_clusters_streaming(
    df: pd.DataFrame, clusters: PrototypeClusters,
) -> np.ndarray:
    """Nearest cluster per row from the pitcher's prior-only profile.

    Leakage-safe: the profile is an expanding mean of strictly prior pitches.
    Rows with no prior data fall back to the train-mean profile (i.e. the
    league-average archetype).
    """
    count_prior = df.groupby("pitcher_id", sort=False).cumcount().to_numpy()

    feats: dict[str, np.ndarray] = {}
    feats["pitcher_throws_int"] = df["pitcher_throws_int"].to_numpy(
        dtype=np.float64,
    )
    for col in _PROFILE_PHYSICAL:
        feats[col] = _prior_expanding_mean(df, col, count_prior)

    # Arsenal prior fractions: expanding mean of per-type indicators.
    pt = df["pitch_type_int"].to_numpy()
    for k, cls in enumerate(PITCH_TYPE_CLASSES):
        ind = pd.Series((pt == k).astype(np.float64), index=df.index)
        tmp = df[["pitcher_id"]].copy()
        tmp["_ind"] = ind.to_numpy()
        feats[f"arsenal_{cls}"] = _prior_expanding_mean(
            tmp, "_ind", count_prior,
        )

    x = np.column_stack([feats[c] for c in clusters.profile_cols])
    # Fill empty-prior rows with the league-average profile.
    for j, col in enumerate(clusters.profile_cols):
        fill = clusters.profile_fill[col]
        colv = x[:, j]
        colv[np.isnan(colv)] = fill
        x[:, j] = colv
    xs = clusters.scaler.transform(x)
    return clusters.kmeans.predict(xs).astype(np.int32)


def set_cluster_embeddings(
    clusters: PrototypeClusters,
    pitcher_embeddings: np.ndarray,
    pitcher_map: dict[int, int],
    train_df: pd.DataFrame,
) -> None:
    """Average the trained pitcher embeddings within each cluster.

    ``pitcher_embeddings`` is the model's embedding weight matrix
    (n_pitchers, pitcher_embed_dim); ``pitcher_map`` maps raw pitcher id ->
    row index. Established pitchers are re-clustered with the same profile
    pipeline so the embedding prototype matches the feature prototype.
    """
    profiles = _established_profiles(train_df)
    x = profiles[clusters.profile_cols].fillna(clusters.profile_fill)
    xs = clusters.scaler.transform(x.to_numpy(dtype=np.float64))
    labels = clusters.kmeans.predict(xs)

    dim = pitcher_embeddings.shape[1]
    protos = np.zeros((clusters.n_clusters, dim), dtype=np.float32)
    for cid in range(clusters.n_clusters):
        member_ids = profiles.loc[labels == cid, "pitcher_id"].tolist()
        rows = [pitcher_map[p] for p in member_ids if p in pitcher_map]
        if rows:
            protos[cid] = pitcher_embeddings[rows].mean(axis=0)
    clusters.cluster_embeddings = protos


def apply_prototype_substitution(
    feat_matrix: np.ndarray,
    feat_cols: list[str],
    emb_matrix: np.ndarray,
    *,
    cluster_ids: np.ndarray,
    is_rookie: np.ndarray,
    clusters: PrototypeClusters,
    pitcher_emb_slice: slice | None,
    substitute_features: bool = True,
    substitute_embedding: bool = True,
) -> tuple[np.ndarray, np.ndarray]:
    """Return (feat, emb) copies with rookie rows swapped to cluster protos.

    ``pitcher_emb_slice`` is the column range of the pitcher-embedding block
    inside the hybrid embedding (e.g. ``slice(d_model, d_model+pe_dim)``).
    """
    feat = feat_matrix.copy()
    emb = emb_matrix.copy()
    col_idx = {c: i for i, c in enumerate(feat_cols)}
    rookie_rows = np.where(is_rookie)[0]

    for r in rookie_rows:
        cid = int(cluster_ids[r])
        proto = clusters.feature_protos.get(cid, clusters.league_proto)
        if substitute_features:
            for col, val in proto.items():
                if col in col_idx:
                    feat[r, col_idx[col]] = val
        if (
            substitute_embedding
            and pitcher_emb_slice is not None
            and clusters.cluster_embeddings is not None
        ):
            emb[r, pitcher_emb_slice] = clusters.cluster_embeddings[cid]
    return feat, emb


__all__ = (
    "ESTABLISHED_MIN_PITCHES",
    "ROOKIE_PITCH_THRESHOLD",
    "SUBSTITUTE_FEATURE_COLS",
    "PrototypeClusters",
    "apply_prototype_substitution",
    "assign_clusters_streaming",
    "build_prototype_clusters",
    "compute_cum_pitch_count",
    "set_cluster_embeddings",
)
