"""Dataset loader for the multi-output MLP (Phase 2c.5).

Joins ``bbip_retrodicted_labels`` (the 2c.4 output) against ``pitches``
(for the launch-time features) and produces ``(features, labels)`` pairs
the Torch trainer consumes:

  - ``features``: (n_features,) float32 — 15 features described in
    :data:`FEATURE_NAMES`.
  - ``labels``: (n_parks, n_outcomes) float32 — the retrodicted
    probability vectors, in the same park ordering as the model's
    heads.

The class is intentionally light on Torch coupling — `__getitem__`
returns NumPy arrays, the trainer wraps with `torch.from_numpy` and
moves to device. That keeps tests Torch-free where they don't need it.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass

import numpy as np
from torch.utils.data import Dataset

# Feature / outcome ordering + one-hot helpers live in a torch-free
# sibling module so the LightGBM baseline (2c.8) and any other consumer
# can pick them up without dragging torch in transitively (co-loading
# torch + lightgbm on macOS triggers a double-libomp segfault).
from bullpen_training.battedball.features_shared import (
    FEATURE_NAMES,
    OUTCOME_NAMES,
    base_state_one_hot,
    stand_one_hot,
)
from bullpen_training.battedball.features_shared import (
    hc_to_spray_deg as _hc_to_spray_deg,
)


@dataclass(frozen=True)
class _BipRow:
    """Joined row from pitches + bbip_retrodicted_labels."""

    features: np.ndarray  # (n_features,) float32
    labels: np.ndarray  # (n_parks, n_outcomes) float32
    home_park_id: str


@dataclass(frozen=True)
class FeatureScaler:
    """Per-feature z-score normalisation parameters.

    Continuous features get ``(x - mean) / std`` applied. One-hot
    features (handedness, base state) are left alone (mean=0, std=1
    by convention). The mask + means + stds are persisted into
    metadata.json so the Java inference side can mirror exactly.
    """

    means: np.ndarray  # (n_features,)
    stds: np.ndarray  # (n_features,)
    is_continuous: np.ndarray  # (n_features,) bool

    @classmethod
    def fit(cls, features: np.ndarray) -> FeatureScaler:
        """Fit on a (N, n_features) array. Stand+base_state are detected
        as one-hot via uniqueness check on each column."""
        n_features = features.shape[1]
        is_continuous = np.ones(n_features, dtype=bool)
        # Known one-hot columns: indices 4-13 inclusive (stand 4-5, base_state 6-13)
        is_continuous[4:14] = False
        means = np.where(is_continuous, features.mean(axis=0), 0.0)
        stds = np.where(is_continuous, features.std(axis=0), 1.0)
        stds = np.where(stds < 1e-6, 1.0, stds)  # guard against /0 on degenerate cols
        return cls(
            means=means.astype(np.float32),
            stds=stds.astype(np.float32),
            is_continuous=is_continuous,
        )

    def transform(self, features: np.ndarray) -> np.ndarray:
        """Apply the z-score in place along axis -1."""
        return ((features - self.means) / self.stds).astype(np.float32)

    def to_dict(self) -> dict[str, list]:
        return {
            "means": self.means.tolist(),
            "stds": self.stds.tolist(),
            "is_continuous": self.is_continuous.tolist(),
        }


def _query_joined(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
) -> str:
    """SQL that joins pitches + bbip_retrodicted_labels and emits one
    row per BIP x park, ordered so reshape (-1, n_parks, n_outcomes)
    gives the right per-park label tensor."""
    parks = ", ".join(f"'{p}'" for p in park_order)
    limit_clause = f"LIMIT {limit * len(park_order)}" if limit else ""
    return f"""
    SELECT
      toString(p.game_date) AS game_date,
      toString(p.game_id) AS game_id,
      toString(p.at_bat_index) AS at_bat_index,
      toString(p.pitch_number) AS pitch_number,
      toString(p.launch_speed_mph) AS launch_speed_mph,
      toString(p.launch_angle_deg) AS launch_angle_deg,
      toString(p.hc_x) AS hc_x,
      toString(p.hc_y) AS hc_y,
      toString(p.hit_distance_ft) AS hit_distance_ft,
      p.stand AS stand,
      toString(p.base_state) AS base_state,
      toString(p.outs) AS outs,
      r.park_id AS park_id,
      toString(r.prob_out) AS prob_out,
      toString(r.prob_1b) AS prob_1b,
      toString(r.prob_2b) AS prob_2b,
      toString(r.prob_3b) AS prob_3b,
      toString(r.prob_hr) AS prob_hr
    FROM pitches AS p FINAL
    JOIN bbip_retrodicted_labels AS r FINAL
      ON r.game_id = p.game_id
     AND r.at_bat_index = p.at_bat_index
     AND r.pitch_number = p.pitch_number
    WHERE p.description = 'in_play'
      AND p.launch_speed_mph IS NOT NULL
      AND p.launch_angle_deg IS NOT NULL
      AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL
      AND p.hit_distance_ft IS NOT NULL
      AND toYear(p.game_date) BETWEEN {season_from} AND {season_to}
      AND r.park_id IN ({parks})
    ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number,
             indexOf([{parks}], r.park_id)
    {limit_clause}
    FORMAT TSV
    """


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _row_to_features(
    launch_speed: str,
    launch_angle: str,
    hc_x: str,
    hc_y: str,
    hit_distance: str,
    stand: str,
    base_state: str,
    outs: str,
) -> np.ndarray:
    """Pack the 15-dim feature vector from raw TSV string fields."""
    spray = _hc_to_spray_deg(float(hc_x), float(hc_y))
    features = np.zeros(len(FEATURE_NAMES), dtype=np.float32)
    features[0] = float(launch_speed)
    features[1] = float(launch_angle)
    features[2] = spray
    features[3] = float(hit_distance)
    features[4:6] = stand_one_hot(stand)
    features[6:14] = base_state_one_hot(int(base_state))
    features[14] = float(outs)
    return features


def load_rows(
    *,
    season_from: int,
    season_to: int,
    park_order: tuple[str, ...],
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
) -> list[_BipRow]:
    """Pull joined rows from ClickHouse and assemble into _BipRows.

    Materialises everything in memory — fine for the 2024-only smoke
    test (~150 K BIPs x 4 KB ~= 600 MB max). Streaming variants for the
    full 2015-2024 backfill belong in the trainer where shuffling
    happens; this function is the simplest correct path.
    """
    tsv = _run_clickhouse(
        _query_joined(
            season_from=season_from,
            season_to=season_to,
            park_order=park_order,
            limit=limit,
        ),
        container=container,
    )
    rows = [line.split("\t") for line in tsv.strip().split("\n") if line]
    n_parks = len(park_order)
    n_outcomes = len(OUTCOME_NAMES)
    assert len(rows) % n_parks == 0, (
        f"row count {len(rows)} not divisible by n_parks {n_parks} — "
        "join may have dropped some (BIP, park) pairs; check that the "
        "retrodict pipeline emitted all 30 rows per BIP."
    )

    out: list[_BipRow] = []
    park_index = {pid: i for i, pid in enumerate(park_order)}
    for i in range(0, len(rows), n_parks):
        block = rows[i : i + n_parks]
        # All n_parks rows in this block share the same BIP fields; pull
        # features from the first row.
        features = _row_to_features(
            block[0][4],
            block[0][5],
            block[0][6],
            block[0][7],
            block[0][8],
            block[0][9],
            block[0][10],
            block[0][11],
        )
        labels = np.zeros((n_parks, n_outcomes), dtype=np.float32)
        home_park_id = block[0][9]  # home park placeholder — overwritten below
        # The block has rows in the join's park ordering, which matches
        # park_order because the SQL ORDER BY uses arrayIndexOf.
        for row in block:
            pid = row[12]
            idx = park_index[pid]
            labels[idx, 0] = float(row[13])  # prob_out
            labels[idx, 1] = float(row[14])  # prob_1b
            labels[idx, 2] = float(row[15])  # prob_2b
            labels[idx, 3] = float(row[16])  # prob_3b
            labels[idx, 4] = float(row[17])  # prob_hr
        # home_park_id is in pitches.park_id (block[0] doesn't carry it
        # directly — we keep it for downstream; here we approximate via
        # the stand column position lookup. For dataset purposes the
        # home park id isn't needed for training the MLP.)
        home_park_id = ""
        out.append(_BipRow(features=features, labels=labels, home_park_id=home_park_id))
    return out


class BBIPDataset(Dataset):
    """Torch Dataset wrapping a list of ``_BipRow`` materialised from
    ClickHouse. Iteration order follows the original CH query order so
    rolling-origin CV can split by date deterministically.

    ``scaler`` (optional): if provided, features are z-score normalised
    on read. Production training fits the scaler on the train fold and
    reuses it for val/test so the same normalisation applies everywhere.
    """

    def __init__(self, rows: list[_BipRow], scaler: FeatureScaler | None = None) -> None:
        self._rows = rows
        self._scaler = scaler

    def __len__(self) -> int:
        return len(self._rows)

    def __getitem__(self, idx: int) -> tuple[np.ndarray, np.ndarray]:
        row = self._rows[idx]
        features = (
            self._scaler.transform(row.features) if self._scaler is not None else row.features
        )
        return features, row.labels

    def all_features(self) -> np.ndarray:
        """Stack all rows' raw features into one (N, n_features) array.
        Used by :meth:`FeatureScaler.fit`."""
        return np.stack([r.features for r in self._rows], axis=0)


__all__ = (
    "FEATURE_NAMES",
    "OUTCOME_NAMES",
    "BBIPDataset",
    "base_state_one_hot",
    "load_rows",
    "stand_one_hot",
)
