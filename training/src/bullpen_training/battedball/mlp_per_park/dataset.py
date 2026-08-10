"""Dataset loader for the per-park MLP experiment.

Pulls BIPs that physically occurred at a specific park from ClickHouse,
paired with the retrodicted label distribution for that park. Each row
is (15-feature vector, 5-outcome distribution) — no multi-park axis.
"""

from __future__ import annotations

import subprocess

import numpy as np
from torch.utils.data import Dataset

from bullpen_training.battedball.features_shared import (
    FEATURE_NAMES,
    OUTCOME_NAMES,
    base_state_one_hot,
    stand_one_hot,
)
from bullpen_training.battedball.features_shared import (
    hc_to_spray_deg as _hc_to_spray_deg,
)
from bullpen_training.battedball.mlp.dataset import FeatureScaler


def _query_park_bips(
    *,
    park_id: str,
    season_from: int,
    season_to: int,
    limit: int | None = None,
) -> str:
    limit_clause = f"LIMIT {limit}" if limit else ""
    # partial_merge: the FINAL x FINAL hash join OOMs under the box's 4 GiB cap
    # (CH exit 241) - same fix as #238 / mlp/dataset.py, identical result set.
    return f"""
    SELECT
      toString(p.launch_speed_mph) AS launch_speed_mph,
      toString(p.launch_angle_deg) AS launch_angle_deg,
      toString(p.hc_x) AS hc_x,
      toString(p.hc_y) AS hc_y,
      toString(p.hit_distance_ft) AS hit_distance_ft,
      p.stand AS stand,
      toString(p.base_state) AS base_state,
      toString(p.outs) AS outs,
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
      AND p.park_id = '{park_id}'
      AND r.park_id = '{park_id}'
    ORDER BY p.game_date, p.game_id, p.at_bat_index, p.pitch_number
    {limit_clause}
    SETTINGS join_algorithm = 'partial_merge'
    FORMAT TSV
    """


def _query_park_count(
    *,
    park_id: str,
    season_from: int,
    season_to: int,
    limit: int | None = None,
) -> str:
    limit_clause = f"LIMIT {limit}" if limit else ""
    return f"""
    SELECT count() FROM (
      SELECT DISTINCT p.game_id, p.at_bat_index, p.pitch_number
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
        AND p.park_id = '{park_id}'
        AND r.park_id = '{park_id}'
      {limit_clause}
    )
    SETTINGS join_algorithm = 'partial_merge'
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


def load_park_arrays(
    *,
    park_id: str,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
) -> tuple[np.ndarray, np.ndarray]:
    """Load BIPs for a single park. Returns (features, labels) where
    features is (N, 15) and labels is (N, 5)."""
    n_outcomes = len(OUTCOME_NAMES)
    count_tsv = _run_clickhouse(
        _query_park_count(
            park_id=park_id,
            season_from=season_from,
            season_to=season_to,
            limit=limit,
        ),
        container=container,
    ).strip()
    total = int(count_tsv)
    if limit is not None:
        total = min(total, limit)

    n_features = len(FEATURE_NAMES)
    features = np.zeros((total, n_features), dtype=np.float32)
    labels = np.zeros((total, n_outcomes), dtype=np.float32)

    tsv = _run_clickhouse(
        _query_park_bips(
            park_id=park_id,
            season_from=season_from,
            season_to=season_to,
            limit=limit,
        ),
        container=container,
    )
    lines = tsv.strip().split("\n")
    written = 0
    for line in lines:
        if not line:
            continue
        cols = line.split("\t")
        features[written] = _row_to_features(
            cols[0],
            cols[1],
            cols[2],
            cols[3],
            cols[4],
            cols[5],
            cols[6],
            cols[7],
        )
        labels[written, 0] = float(cols[8])
        labels[written, 1] = float(cols[9])
        labels[written, 2] = float(cols[10])
        labels[written, 3] = float(cols[11])
        labels[written, 4] = float(cols[12])
        written += 1

    if written < total:
        features = features[:written]
        labels = labels[:written]
    return features, labels


class PerParkDataset(Dataset):
    """Torch Dataset for a single park's BIPs."""

    def __init__(
        self,
        features: np.ndarray,
        labels: np.ndarray,
        scaler: FeatureScaler | None = None,
    ) -> None:
        self._features = features
        self._labels = labels
        self._scaler = scaler

    def __len__(self) -> int:
        return self._features.shape[0]

    def __getitem__(self, idx: int) -> tuple[np.ndarray, np.ndarray]:
        f = self._features[idx]
        if self._scaler is not None:
            f = self._scaler.transform(f)
        return f, self._labels[idx]

    def all_features(self) -> np.ndarray:
        return self._features


__all__ = (
    "PerParkDataset",
    "load_park_arrays",
)
