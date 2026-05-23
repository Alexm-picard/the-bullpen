"""Feature extraction for the toy HR classifier (Phase 1.3).

Five features — kept tiny to keep the ONNX export trivial:

    launch_speed_mph    raw Statcast exit velocity
    launch_angle_deg    raw Statcast launch angle
    release_speed_mph   the pitch's velocity (mild correlation with HR)
    park_id_encoded     2024-season HR rate for the home park (target-encoded)
    stand_is_left       1 if batter L, 0 otherwise

The training target is `is_hr`: 1 iff `events = 'home_run'`. We restrict to
`description = 'in_play'` and require non-null launch_speed + launch_angle
so the toy never sees pre-contact pitches.

Park target-encoding here is *deliberately leaky* (same-year HR rate fits
the same data the classifier trains on). That's acceptable for the toy;
the real Phase 2a feature pipeline ships out-of-fold target-encoding +
the leakage tests that would catch this.
"""

from __future__ import annotations

from typing import Any, cast

import pandas as pd
from clickhouse_driver import Client

FEATURES: tuple[str, ...] = (
    "launch_speed_mph",
    "launch_angle_deg",
    "release_speed_mph",
    "park_id_encoded",
    "stand_is_left",
)
TARGET = "is_hr"


def load_training_frame(client: Client, year: int) -> pd.DataFrame:
    """Pull labeled in-play rows, derive `stand_is_left` and target-encode park.

    Returned DataFrame columns: FEATURES + [TARGET]. Any rows with NULL
    release_speed_mph after the in-play filter are dropped (pitchers' radar
    occasionally has gaps); the toy doesn't try to impute.
    """
    # ORDER BY the PK so the row order is stable across runs. Without this,
    # FINAL plus the merge engine can return rows in different orders run-to-run,
    # which silently breaks reproducibility (train/test split shuffles a
    # different starting order → different model bytes).
    query = f"""
        SELECT
            launch_speed_mph,
            launch_angle_deg,
            release_speed_mph,
            park_id,
            stand,
            toUInt8(events = 'home_run') AS is_hr
        FROM pitches FINAL
        WHERE description = 'in_play'
          AND toYear(game_date) = {year}
          AND launch_speed_mph IS NOT NULL
          AND launch_angle_deg IS NOT NULL
        ORDER BY game_date, game_id, at_bat_index, pitch_number
    """
    rows = cast(list[tuple[Any, ...]], client.execute(query))
    df = pd.DataFrame(
        rows,
        columns=[
            "launch_speed_mph",
            "launch_angle_deg",
            "release_speed_mph",
            "park_id",
            "stand",
            TARGET,
        ],
    )
    return _engineer_features(df)


def _engineer_features(df: pd.DataFrame) -> pd.DataFrame:
    """Pure DataFrame → DataFrame transformation. Easier to unit test."""
    out = df.copy()
    out = out.dropna(subset=["release_speed_mph"]).reset_index(drop=True)
    out["stand_is_left"] = (out["stand"] == "L").astype("int8")

    # Target-encode park_id by HR rate. Deliberately leaky — see module docstring.
    park_hr_rate: dict[Any, float] = cast(
        pd.Series, out.groupby("park_id")[TARGET].mean()
    ).to_dict()
    out["park_id_encoded"] = out["park_id"].map(park_hr_rate.get).astype("float32")

    for col in ("launch_speed_mph", "launch_angle_deg", "release_speed_mph"):
        out[col] = out[col].astype("float32")

    return cast(pd.DataFrame, out[[*FEATURES, TARGET]])
