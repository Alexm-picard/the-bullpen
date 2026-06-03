"""Humidor EV adjustment for the cross-park counterfactual (decision [137], ADR-0009).

A baseball stored in a humidor equilibrates to the humidor's relative humidity
(RH) rather than the local climate. Higher stored RH => wetter, heavier, softer
ball => lower coefficient of restitution (COR) => lower exit velocity (EV) off the
bat => less carry. The effect is **ambient-relative**: a 50-57 % humidor *lowers*
COR in dry Denver (suppresses carry) but *raises* it in humid Miami (a small
boost), because the sign of ``RH_humidor - RH_ambient`` flips.

The cross-park retrodiction is a counterfactual — it flies each ball through every
park. The as-measured EVs were produced by the hitters' home-park balls; to ask
"what would this ball do at park P?" honestly, we must apply P's humidor effect on
the EV. So this module returns a per-(destination park, season) EV delta (mph)
that ``labels.py`` adds to ``launch_speed_mph`` before integration.

All inputs are exogenous physics/climate constants — **none are fit to the 2c.7
gate** (the non-circularity discipline of ADR-0009):

- **COR(RH)** is linear, ``0.574`` at 0 % RH -> ``0.452`` at 100 % (Alan Nathan,
  "Influence of a humidor on the aerodynamics of baseballs",
  baseball.physics.illinois.edu/humidor.pdf). Slope ``-0.00122`` per %RH.
- **EV per unit COR** is set so the Denver case reproduces Nathan's headline
  ~2.8 mph total EV reduction (0.6 mph ball-weight + 2.2 mph COR) over the ~20 %
  RH the 50 % humidor adds to Denver's ~30 % ambient — i.e. COL => -2.8 mph EV.
  The small ball-weight contribution is folded into this effective constant.
- **Humidor setpoints**: 50 % RH (COL since 2002, AZ since 2018) -> 57 % RH for all
  30 parks under the 2022 MLB mandate.
- **Ambient RH** is a per-park baseball-season climate normal (the storage RH a
  ball reaches *without* a humidor), deliberately distinct from the per-game
  weather in the pipeline.

KNOWN SIMPLIFICATIONS (v1, documented per ADR-0009): we model the EV/COR effect
but not the small +2 ft aero offset (slightly over-suppresses); we approximate the
few 2018-2021 voluntary early adopters as joining at the 2022 mandate; dome /
retractable-roof clubhouses are treated with their outdoor-climate ambient RH.
"""

from __future__ import annotations

# --- COR(RH), from Nathan's measured endpoints --------------------------------
COR_AT_0_RH: float = 0.574
COR_AT_100_RH: float = 0.452
COR_SLOPE_PER_PCT_RH: float = (COR_AT_100_RH - COR_AT_0_RH) / 100.0  # -0.00122

# EV (mph) per unit of COR change, folding in the ball-weight contribution so the
# Denver humidor reproduces Nathan's ~2.8 mph: 2.8 / (|slope| * 20 %RH) ~ 114.8.
EV_MPH_PER_COR_UNIT: float = 114.8

# --- Humidor setpoints + adoption (documented) --------------------------------
HUMIDOR_RH_LEGACY_PCT: float = 50.0  # COL (2002+), AZ (2018+) pre-mandate
HUMIDOR_RH_MANDATE_PCT: float = 57.0  # all 30 parks, 2022 MLB mandate
MLB_MANDATE_SEASON: int = 2022

# Parks with a humidor *before* the 2022 league mandate, and the first season.
HUMIDOR_SINCE_SEASON: dict[str, int] = {
    "COL": 2002,
    "AZ": 2018,
}

# --- Per-park ambient (climate-normal) RH, baseball-season afternoon avg (%) ---
# The storage RH a ball equilibrates to WITHOUT a humidor. COL/AZ (the dry,
# pre-mandate, load-bearing parks) are the values that actually move the ranking;
# the rest are a league-humid baseline (post-2022 only, small deltas). SANITY-
# CHECK TARGET: COL~30 anchors Nathan's -2.8 mph; the others want NOAA normals.
AMBIENT_RH_PCT: dict[str, float] = {
    "COL": 30.0,  # Denver — semi-arid; anchors the magnitude
    "AZ": 25.0,  # Phoenix — desert (retractable roof)
    "TEX": 55.0,  # Arlington (retractable roof)
    "LAD": 58.0,
    "LAA": 60.0,
    "SD": 66.0,  # San Diego — coastal
    "SF": 70.0,  # cool marine
    "ATH": 68.0,  # Oakland — marine
    "SEA": 64.0,
    "HOU": 65.0,  # humid (retractable roof)
    "KC": 62.0,
    "STL": 62.0,
    "MIN": 64.0,
    "MIL": 66.0,  # (retractable roof)
    "CHC": 64.0,
    "CWS": 64.0,
    "CIN": 64.0,
    "CLE": 66.0,
    "DET": 64.0,
    "PIT": 65.0,
    "TOR": 64.0,  # (retractable roof)
    "BOS": 64.0,
    "NYY": 60.0,
    "NYM": 60.0,
    "PHI": 60.0,
    "BAL": 62.0,
    "WSH": 62.0,
    "ATL": 62.0,
    "TB": 68.0,  # Tampa — fixed dome
    "MIA": 70.0,  # Miami — humid (retractable roof)
}
_DEFAULT_AMBIENT_RH_PCT: float = 62.0  # league-typical, for any unlisted park


def cor(rh_pct: float) -> float:
    """Coefficient of restitution at storage relative humidity ``rh_pct`` (Nathan)."""
    return COR_AT_0_RH + COR_SLOPE_PER_PCT_RH * rh_pct


def ev_delta_mph(rh_ambient_pct: float, rh_humidor_pct: float) -> float:
    """EV change (mph) from storing a ball at ``rh_humidor`` vs the park's ambient.

    Negative when the humidor is wetter than ambient (dry parks -> suppressed
    carry), positive when drier (humid parks -> small boost).
    """
    return EV_MPH_PER_COR_UNIT * (cor(rh_humidor_pct) - cor(rh_ambient_pct))


def humidor_rh_for(park_id: str, season: int) -> float | None:
    """Storage RH (%) for ``park_id`` in ``season``, or ``None`` if no humidor then."""
    since = HUMIDOR_SINCE_SEASON.get(park_id, MLB_MANDATE_SEASON)
    if season < since:
        return None
    return HUMIDOR_RH_MANDATE_PCT if season >= MLB_MANDATE_SEASON else HUMIDOR_RH_LEGACY_PCT


def ev_delta_for(park_id: str, season: int) -> float:
    """EV (mph) adjustment for a ball flown at ``park_id`` in ``season``.

    0.0 when the park had no humidor that season. Otherwise the ambient-relative
    EV delta (negative = suppress, positive = boost).
    """
    rh_humidor = humidor_rh_for(park_id, season)
    if rh_humidor is None:
        return 0.0
    rh_ambient = AMBIENT_RH_PCT.get(park_id, _DEFAULT_AMBIENT_RH_PCT)
    return ev_delta_mph(rh_ambient, rh_humidor)
