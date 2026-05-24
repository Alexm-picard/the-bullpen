"""Pre-pitch and post-pitch outcome models.

Phase 2a serves the pre-pitch head; Phase 2b adds the post-pitch head
(separate registry entry per CLAUDE.md rule 9).
"""

PITCH_FEATURE_COLUMNS: tuple[str, ...] = (
    # Tier 1 — cheap context
    "count_balls",
    "count_strikes",
    "outs",
    "inning",
    "base_state",
    "score_diff",
    "dow",
    # Tier 1 — handedness + park as integer-encoded (LightGBM categorical)
    "pitcher_throws_int",
    "batter_stand_int",
    "park_id_int",
    # Tier 2 — target encodings
    "pitcher_te_ball",
    "pitcher_te_called_strike",
    "pitcher_te_swinging_strike",
    "pitcher_te_foul",
    "pitcher_te_in_play",
    "batter_te_ball",
    "batter_te_called_strike",
    "batter_te_swinging_strike",
    "batter_te_foul",
    "batter_te_in_play",
    # Tier 3 — rolling form (nullable; LightGBM handles NaN natively)
    "pitcher_pitches_last_28d",
    "pitcher_pitches_in_game",
    "days_since_last_appearance",
    "pitcher_strike_rate_28d",
    "pitcher_swstrike_rate_28d",
    "pitcher_inplay_rate_28d",
    "pitcher_strike_rate_std",
    "batter_strike_rate_28d",
    "batter_inplay_rate_28d",
    "batter_ball_rate_28d",
    "batter_inplay_rate_std",
)
"""31 features the pre-pitch model consumes. Categorical string columns
(pitcher_throws, batter_stand, park_id) are converted to ints in the
loader (Phase 2a.5 step 2). Tier 3 columns may be NULL on the first
~28 days of a season — LightGBM handles missing natively."""


PITCH_FEATURE_COLUMNS_POST: tuple[str, ...] = (
    *PITCH_FEATURE_COLUMNS,
    # Tier 4 — post-pitch (release / movement / spin / pitch type). Several
    # are sparse before 2024 (pfx_x/z, spin_rate, spin_axis live behind the
    # V008 raw-schema gate — see decisions.md and the 2b.1 status log).
    "pitch_type_int",
    "release_speed_mph",
    "plate_x_in",
    "plate_z_in",
    "pfx_x_in",
    "pfx_z_in",
    "spin_rate_rpm",
    "spin_axis_deg",
    "release_pos_x_in",
    "release_pos_z_in",
)
"""41 features the post-pitch model consumes. Tier 4 columns are deliberately
Nullable on the wire — LightGBM handles NaN natively and the post-head model
treats their absence as part of the input distribution. The pre-pitch
canonical contract MUST NOT list any of these columns; enforced at CI time
by `tests/features/test_tier_4_postpitch.py::test_pre_pipeline_contract_lists_no_tier4_columns`.

`pitch_type_int` is the integer encoding of the LowCardinality(String)
pitch_type column produced by the loader; the mapping is alphabetical
+ deterministic, shared across train/val/test calls like `park_id_int`."""
