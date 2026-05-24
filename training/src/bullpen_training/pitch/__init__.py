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
