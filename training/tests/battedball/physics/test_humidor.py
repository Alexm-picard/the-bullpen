"""Tests for the humidor EV adjustment (decision [137], ADR-0009)."""

from __future__ import annotations

import pytest

from bullpen_training.battedball.physics import humidor


def test_cor_matches_nathan_endpoints() -> None:
    """COR(RH) is linear from 0.574 @ 0% to 0.452 @ 100% (Nathan)."""
    assert humidor.cor(0.0) == pytest.approx(0.574)
    assert humidor.cor(100.0) == pytest.approx(0.452)
    # 1.2% COR drop per 5% RH (Nathan) ~ 0.0066 per 5% near COR~0.55.
    assert humidor.cor(50.0) - humidor.cor(55.0) == pytest.approx(0.0061, abs=5e-4)


def test_col_humidor_reproduces_nathan_2_8_mph() -> None:
    """The load-bearing anchor: Denver's ~30% ambient -> 50% humidor ~ -2.8 mph EV
    (Nathan's headline). This is the constant to sanity-check before wiring in."""
    delta = humidor.ev_delta_mph(rh_ambient_pct=30.0, rh_humidor_pct=50.0)
    assert delta == pytest.approx(-2.8, abs=0.1)


def test_dry_park_suppresses_humid_park_boosts() -> None:
    """Ambient-relative sign flip: a 57% humidor suppresses EV in dry Denver but
    boosts it in humid Miami (where 57% is drier than the ~70% ambient)."""
    col = humidor.ev_delta_for("COL", 2023)  # dry -> suppress
    mia = humidor.ev_delta_for("MIA", 2023)  # humid -> boost
    assert col < 0.0
    assert mia > 0.0


def test_no_humidor_before_adoption_is_zero() -> None:
    """A park with no humidor in a season gets no EV adjustment."""
    # A generic park before the 2022 mandate: no humidor.
    assert humidor.ev_delta_for("BOS", 2019) == 0.0
    # COL has had one since 2002.
    assert humidor.ev_delta_for("COL", 2016) != 0.0
    # AZ since 2018 — not 2017.
    assert humidor.ev_delta_for("AZ", 2017) == 0.0
    assert humidor.ev_delta_for("AZ", 2018) != 0.0


def test_mandate_year_turns_on_all_parks_at_57() -> None:
    """From 2022 every park has a 57% humidor."""
    assert humidor.humidor_rh_for("BOS", 2021) is None
    assert humidor.humidor_rh_for("BOS", 2022) == 57.0
    # COL steps 50% -> 57% at the mandate.
    assert humidor.humidor_rh_for("COL", 2021) == 50.0
    assert humidor.humidor_rh_for("COL", 2022) == 57.0


def test_ev_delta_for_is_monotonic_in_dryness() -> None:
    """Among 2023 humidor parks, drier ambient => more suppression. COL (30%) is
    the driest after AZ's roof correction (45%), so COL suppresses most; humid
    Miami (70%) flips to a boost."""
    assert humidor.ev_delta_for("COL", 2023) < humidor.ev_delta_for("AZ", 2023)
    assert humidor.ev_delta_for("AZ", 2023) < humidor.ev_delta_for("MIA", 2023)
