"""Retrodiction labeling pipeline (Phase 2c.4).

For every batted ball in play, run the physics simulator under each of
the 30 MLB park atmospheres with light Monte Carlo noise on launch
parameters; turn each trajectory into a 5-class outcome via the
geometry classifier; aggregate into a probability vector per (BIP, park).
The result is the label source the multi-output MLP in 2c.5 trains on.

Public surface:

- :func:`retrodict_one` — one (BIP, park) -> (prob_out, prob_1b, ..., prob_hr).
- :func:`retrodict_bip_at_all_parks` — one BIP -> 30 outcome distributions.
- :func:`run_pipeline` — stream BIPs from ClickHouse, batch through 30
  parks, write to ``bbip_retrodicted_labels``.
"""

from __future__ import annotations

from bullpen_training.battedball.retrodict.labels import (
    BBIP,
    RetrodictionResult,
    event_to_outcome,
    retrodict_bip_at_all_parks,
    retrodict_one,
)

__all__ = (
    "BBIP",
    "RetrodictionResult",
    "event_to_outcome",
    "retrodict_bip_at_all_parks",
    "retrodict_one",
)
