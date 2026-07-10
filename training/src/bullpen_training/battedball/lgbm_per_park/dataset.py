"""Dataset loader for per-park LightGBM models.

Pulls BIPs that occurred at a specific park from ClickHouse, returning
a pandas DataFrame with the 15 launch/game-state features and an
argmax label column. No park_id feature — each model is park-specific.
"""

from __future__ import annotations

import json
import subprocess
from typing import Final

import numpy as np
import pandas as pd

from bullpen_training.battedball.features_shared import (
    FEATURE_NAMES,
    base_state_one_hot,
    stand_one_hot,
)
from bullpen_training.battedball.features_shared import (
    hc_to_spray_deg as _hc_to_spray_deg,
)

FEATURE_COLUMNS: Final[tuple[str, ...]] = FEATURE_NAMES
LABEL_COLUMN: Final[str] = "label"


def _query_park_bips_json(
    *,
    park_id: str,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    offset: int = 0,
) -> str:
    limit_clause = f"LIMIT {limit}" if limit else ""
    offset_clause = f"OFFSET {offset}" if offset > 0 else ""
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
    {limit_clause} {offset_clause}
    SETTINGS join_algorithm = 'partial_merge'
    FORMAT JSONEachRow
    """


def _run_clickhouse(query: str, *, container: str = "bullpen-clickhouse") -> str:
    res = subprocess.run(
        ["docker", "exec", container, "clickhouse-client", "--query", query],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _row_to_record(row: dict[str, str]) -> dict[str, object]:
    spray = _hc_to_spray_deg(float(row["hc_x"]), float(row["hc_y"]))
    record: dict[str, object] = {
        "launch_speed_mph": float(row["launch_speed_mph"]),
        "launch_angle_deg": float(row["launch_angle_deg"]),
        "spray_angle_deg": spray,
        "hit_distance_ft": float(row["hit_distance_ft"]),
    }
    stand = stand_one_hot(row["stand"])
    record["stand_R"] = float(stand[0])
    record["stand_L"] = float(stand[1])
    base = base_state_one_hot(int(row["base_state"]))
    for i in range(8):
        record[f"base_state_{i}"] = float(base[i])
    record["outs"] = float(row["outs"])

    probs = [
        float(row["prob_out"]),
        float(row["prob_1b"]),
        float(row["prob_2b"]),
        float(row["prob_3b"]),
        float(row["prob_hr"]),
    ]
    record[LABEL_COLUMN] = int(np.argmax(probs))
    return record


def load_park_lgbm_dataset(
    *,
    park_id: str,
    season_from: int,
    season_to: int,
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
    chunk_rows: int = 150_000,
) -> pd.DataFrame:
    """Pull BIPs for a single park into a LightGBM-ready DataFrame.

    Same 15 features as the global LGBM baseline but without park_id
    (each model is park-specific so it would be constant).
    """
    chunks: list[pd.DataFrame] = []
    offset = 0
    total = 0

    while True:
        effective_limit = chunk_rows
        if limit is not None:
            remaining = limit - total
            if remaining <= 0:
                break
            effective_limit = min(chunk_rows, remaining)

        raw = _run_clickhouse(
            _query_park_bips_json(
                park_id=park_id,
                season_from=season_from,
                season_to=season_to,
                limit=effective_limit,
                offset=offset,
            ),
            container=container,
        )
        records: list[dict[str, object]] = []
        for line in raw.strip().split("\n"):
            if not line:
                continue
            records.append(_row_to_record(json.loads(line)))

        if not records:
            break

        chunks.append(pd.DataFrame.from_records(records))
        n = len(records)
        offset += n
        total += n

        if n < effective_limit:
            break

    if not chunks:
        return pd.DataFrame()

    return pd.concat(chunks, ignore_index=True)


__all__ = (
    "FEATURE_COLUMNS",
    "LABEL_COLUMN",
    "load_park_lgbm_dataset",
)
