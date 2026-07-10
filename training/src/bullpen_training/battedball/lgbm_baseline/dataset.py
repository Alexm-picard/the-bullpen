"""Flatten BIP x park retrodicted labels into the LightGBM training shape (2c.8).

The MLP in 2c.5 sees ``(features, labels)`` pairs where ``labels`` is a
(30, 5) distribution. LightGBM Option-A flattens that: each of the 30
parks becomes a separate training row with ``park_id`` as a categorical
feature; the label is the argmax of the per-park distribution.

The pipeline reuses the same ClickHouse join from
``bullpen_training.battedball.mlp.dataset.load_rows`` so feature
parity with the MLP is mechanical — same 15 launch/game-state features,
plus the categorical ``park_id``.
"""

from __future__ import annotations

import json
import subprocess
from typing import Final

import numpy as np
import pandas as pd

# Import the torch-free feature helpers directly so this module doesn't
# pull torch in. Co-loading torch + lightgbm segfaults on macOS due to
# duplicate libomp; the CI workflow splits the two suites for the same
# reason. Tests for this module run in the lightgbm-path pytest pass.
from bullpen_training.battedball.features_shared import (
    FEATURE_NAMES,
    OUTCOME_NAMES,
    base_state_one_hot,
    stand_one_hot,
)
from bullpen_training.battedball.features_shared import (
    hc_to_spray_deg as _hc_to_spray_deg,
)

# Feature columns the LGBM booster sees. Same 15 launch + game-state
# columns the MLP uses, plus the categorical ``park_id``.
FEATURE_COLUMNS: Final[tuple[str, ...]] = (*FEATURE_NAMES, "park_id")
PARK_FEATURE: Final[str] = "park_id"
LABEL_COLUMN: Final[str] = "label"

# Outcome ordering — matches V011's Enum8 and the MLP's outcome axis.
_OUTCOME_TO_INT: Final[dict[str, int]] = {name: i for i, name in enumerate(OUTCOME_NAMES)}


def _query_joined_for_lgbm(
    *,
    season_from: int,
    season_to: int,
    park_filter: tuple[str, ...] | None = None,
    limit: int | None = None,
    include_keys: bool = False,
) -> str:
    """ClickHouse join query - emits one row per (BIP, park) pair.

    Same join as the MLP's dataset query but selects the WHOLE 5-class
    label distribution per row (so the caller can pick argmax in Python).
    Park filter is optional; default returns all 30 parks.

    No ``ORDER BY`` and no ``OFFSET`` pagination: the LGBM baseline doesn't
    need row order, and deep-OFFSET pages re-materialize + re-sort the whole
    ~30x FINAL fan-out join (~32M rows) on every page - that blew past the
    ClickHouse container's memory cap (code 241). ``load_lgbm_dataset`` instead
    pages by PARK, so each query is bounded to one park's ~1.1M rows.

    ``include_keys`` adds the natural-key columns (game_date/game_id/at_bat_index/
    pitch_number) so a caller can re-sort the per-park (park-major) result into the
    MLP loader's BIP-major order - needed by the 2c.9 comparison, which aligns the
    two models' predictions row-for-row. They are NOT features (training ignores
    them; ``predict`` selects only FEATURE_COLUMNS).
    """
    park_clause = ""
    if park_filter is not None:
        plist = ", ".join(f"'{p}'" for p in park_filter)
        park_clause = f"AND r.park_id IN ({plist})"
    limit_clause = f"LIMIT {limit}" if limit else ""
    key_cols = (
        "toString(p.game_date) AS game_date,"
        " toString(p.game_id) AS game_id,"
        " toString(p.at_bat_index) AS at_bat_index,"
        " toString(p.pitch_number) AS pitch_number,\n      "
        if include_keys
        else ""
    )
    # partial_merge: the FINAL x FINAL hash join OOMs under the box's 4 GiB cap
    # (CH exit 241) - same fix as #238 / mlp/dataset.py, identical result set.
    return f"""
    SELECT
      {key_cols}toString(p.launch_speed_mph) AS launch_speed_mph,
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
      {park_clause}
    {limit_clause}
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
    """Decode one TSV/JSON row into the LGBM-ready feature+label dict."""
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
    record["park_id"] = row["park_id"]

    # Argmax of the 5-class distribution = the supervised label.
    probs = [
        float(row["prob_out"]),
        float(row["prob_1b"]),
        float(row["prob_2b"]),
        float(row["prob_3b"]),
        float(row["prob_hr"]),
    ]
    record[LABEL_COLUMN] = int(np.argmax(probs))

    # Natural-key columns (present only when the query was built with
    # include_keys) - for re-sorting to the MLP loader's BIP-major order. game_id
    # is UInt64 / at_bat_index + pitch_number are ints in pitches, so parse them
    # numerically to match ClickHouse's ORDER BY (string sort would diverge).
    if "game_id" in row:
        record["game_date"] = row["game_date"]
        record["game_id"] = int(row["game_id"])
        record["at_bat_index"] = int(row["at_bat_index"])
        record["pitch_number"] = int(row["pitch_number"])
    return record


def _distinct_parks(*, season_from: int, season_to: int, container: str) -> tuple[str, ...]:
    """Parks present in the label table for the window — the loader pages over
    these so each query stays bounded to one park's rows. Cheap (30 rows)."""
    raw = _run_clickhouse(
        f"SELECT DISTINCT park_id FROM bbip_retrodicted_labels "
        f"WHERE toYear(game_date) BETWEEN {season_from} AND {season_to} "
        f"ORDER BY park_id FORMAT TSV",
        container=container,
    )
    return tuple(p for p in raw.strip().split("\n") if p)


def _frame_from_query(query: str, *, container: str) -> pd.DataFrame:
    """Run one bounded query and decode JSONEachRow rows into a DataFrame."""
    raw = _run_clickhouse(query, container=container)
    records = [_row_to_record(json.loads(line)) for line in raw.strip().split("\n") if line]
    if not records:
        return pd.DataFrame()
    return pd.DataFrame.from_records(records)


def load_lgbm_dataset(
    *,
    season_from: int,
    season_to: int,
    park_filter: tuple[str, ...] | None = None,
    limit: int | None = None,
    container: str = "bullpen-clickhouse",
    include_keys: bool = False,
) -> pd.DataFrame:
    """Pull the joined (BIP, park) rows and return a LightGBM-ready DataFrame.

    The DataFrame has columns ``FEATURE_COLUMNS`` plus ``LABEL_COLUMN``
    (the argmax 0-4 outcome index). ``park_id`` is a categorical pandas column
    so LightGBM picks it up via ``categorical_feature=['park_id']`` at Dataset
    construction.

    Pages by PARK (not by deep OFFSET): the join fans out ~30x (one label row
    per BIP per park, ~32M rows for 2015-2024), and an ``OFFSET 30_000_000``
    page forces ClickHouse to re-materialize + re-sort the whole FINAL join in
    RAM, which exceeded the container's memory cap (code 241). One query per
    park keeps each read at ~1.1M rows - the same scale the MLP loader handles.

    The per-park paging makes the result PARK-major. ``include_keys=True`` adds
    the natural-key columns so callers that need BIP-major order (the 2c.9
    comparison) can re-sort; training leaves it False (order-agnostic + leaner).
    """
    # A capped pull (smoke / tests) is small enough for a single bounded query.
    if limit is not None:
        df = _frame_from_query(
            _query_joined_for_lgbm(
                season_from=season_from,
                season_to=season_to,
                park_filter=park_filter,
                limit=limit,
                include_keys=include_keys,
            ),
            container=container,
        )
        if not df.empty:
            df["park_id"] = df["park_id"].astype("category")
        return df

    parks = (
        park_filter
        if park_filter is not None
        else _distinct_parks(season_from=season_from, season_to=season_to, container=container)
    )
    chunks: list[pd.DataFrame] = []
    total = 0
    for i, park in enumerate(parks, start=1):
        frame = _frame_from_query(
            _query_joined_for_lgbm(
                season_from=season_from,
                season_to=season_to,
                park_filter=(park,),
                include_keys=include_keys,
            ),
            container=container,
        )
        if not frame.empty:
            chunks.append(frame)
            total += len(frame)
        print(
            f"  lgbm dataset: park {i}/{len(parks)} ({park}) -> {total} rows so far...",
            flush=True,
        )

    if not chunks:
        return pd.DataFrame()

    print(f"  lgbm dataset: {total} rows total, concatenating...", flush=True)
    df = pd.concat(chunks, ignore_index=True)
    df["park_id"] = df["park_id"].astype("category")
    return df


def outcome_int_to_name(idx: int) -> str:
    """0-4 -> 'out'/'1b'/'2b'/'3b'/'hr'. Tiny helper used in tests."""
    return OUTCOME_NAMES[idx]


def outcome_name_to_int(name: str) -> int:
    return _OUTCOME_TO_INT[name]


__all__ = (
    "FEATURE_COLUMNS",
    "LABEL_COLUMN",
    "PARK_FEATURE",
    "load_lgbm_dataset",
    "outcome_int_to_name",
    "outcome_name_to_int",
)
