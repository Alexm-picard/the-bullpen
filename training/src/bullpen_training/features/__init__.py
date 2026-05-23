"""Feature pipeline for The Bullpen pre-pitch and post-pitch models.

Tier structure (per docs/design.md §4):

    Tier 1  cheap context           — count, base state, score diff,
                                       handedness, park, day-of-week
    Tier 2  identity (target-enc)   — pitcher_id, batter_id (leakage-safe
                                       rolling target encoding)
    Tier 3  rolling form            — last-N pitches/PA windowed at the
                                       pitch's instant (Phase 2a.2)
    Tier 4  post-pitch              — release-side data, initial flight
                                       (Phase 2b.1)

All tiers respect a strict pre-pitch temporal cutoff. The four leakage
tests in Phase 2a.3 are the CI gate that proves it (CLAUDE.md rule 10).
"""

LABEL_CLASSES: tuple[str, ...] = (
    "ball",
    "called_strike",
    "swinging_strike",
    "foul",
    "in_play",
)
"""The 5 labels the pre-pitch model predicts.

`hit_by_pitch` (rare, ~0.3% of pitches) and `unknown` are filtered out at
the feature-build step. Folding hbp into 'ball' or its own class is a
Phase 2a refinement candidate, not a 2a.1 concern.
"""
