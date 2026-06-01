"""Tests for the retrodiction MC + 5-class label mapper (Phase 2c.4)."""

from __future__ import annotations

import pytest

from bullpen_training.battedball.retrodict.labels import (
    BBIP,
    DEFAULT_N_MC,
    _seed_for_bbip,
    event_to_outcome,
    retrodict_bip_at_all_parks,
    retrodict_bips_batch,
    retrodict_one,
)


def _hr_bbip(home_park: str = "NYY") -> BBIP:
    """A barrelled HR launch — well above NYY's short porch."""
    return BBIP(
        game_date="2024-07-15",
        game_id=746000123,
        at_bat_index=42,
        pitch_number=4,
        home_park_id=home_park,
        launch_speed_mph=110.0,
        launch_angle_deg=28.0,
        spray_angle_deg=-30.0,  # toward RF
        spin_rate_rpm=1800.0,
        spin_axis_tilt_deg=180.0,
        observed_event="home_run",
    )


def _liner_bbip(home_park: str = "STL") -> BBIP:
    """A soft single liner — no HR risk in any park."""
    return BBIP(
        game_date="2024-06-01",
        game_id=746000456,
        at_bat_index=15,
        pitch_number=2,
        home_park_id=home_park,
        launch_speed_mph=82.0,
        launch_angle_deg=15.0,
        spray_angle_deg=5.0,
        spin_rate_rpm=1500.0,
        spin_axis_tilt_deg=180.0,
        observed_event="single",
    )


# --- event_to_outcome -----------------------------------------------------


@pytest.mark.parametrize(
    ("event", "expected"),
    [
        ("home_run", "hr"),
        ("single", "1b"),
        ("double", "2b"),
        ("triple", "3b"),
        ("field_out", "out"),
        ("force_out", "out"),
        ("grounded_into_double_play", "out"),
        ("sac_fly", "out"),
        ("sac_bunt", "out"),
    ],
)
def test_event_to_outcome_clean_5_class(event: str, expected: str) -> None:
    assert event_to_outcome(event) == expected


@pytest.mark.parametrize(
    "event",
    ["field_error", "catcher_interf", "fan_interference", "batter_interference"],
)
def test_event_to_outcome_ambiguous_returns_none(event: str) -> None:
    assert event_to_outcome(event) is None


def test_event_to_outcome_unknown_string_returns_none() -> None:
    assert event_to_outcome("definitely_not_an_event") is None


# --- determinism + shape --------------------------------------------------


def test_retrodict_one_is_deterministic_under_fixed_seed() -> None:
    bbip = _hr_bbip()
    a = retrodict_one(bbip, "NYY", seed_offset=12345)
    b = retrodict_one(bbip, "NYY", seed_offset=12345)
    assert a.prob_out == b.prob_out
    assert a.prob_1b == b.prob_1b
    assert a.prob_hr == b.prob_hr


def test_retrodict_one_probabilities_sum_to_one() -> None:
    bbip = _hr_bbip()
    r = retrodict_one(bbip, "NYY")
    total = r.prob_out + r.prob_1b + r.prob_2b + r.prob_3b + r.prob_hr
    assert total == pytest.approx(1.0, abs=1e-6)


def test_seed_is_stable_across_runs() -> None:
    """_seed_for_bbip should be deterministic across processes — used
    for the ReplacingMergeTree idempotency guarantee."""
    assert _seed_for_bbip("746000123-42-4", 0xBA5EBA11) == _seed_for_bbip(
        "746000123-42-4", 0xBA5EBA11
    )
    # Different keys produce different seeds.
    assert _seed_for_bbip("a", 0) != _seed_for_bbip("b", 0)


# --- physically-meaningful outputs ---------------------------------------


def test_hr_launch_at_yankee_has_high_hr_probability() -> None:
    """A 110 mph / 28 deg / -30 spray (deep RF) at NYY should retrodict
    as overwhelmingly HR — short-porch park, barrelled launch."""
    r = retrodict_one(_hr_bbip("NYY"), "NYY")
    assert r.prob_hr > 0.8, f"expected dominant HR prob, got {r.prob_hr:.2f}"
    assert r.is_home_park is True
    assert r.observed_outcome == "hr"


def test_same_hr_launch_lower_hr_probability_at_deeper_park() -> None:
    """The same RF-pull HR at NYY should be a less-certain HR at parks
    with a deeper / taller RF wall (e.g. PIT's 21 ft Clemente Wall, SF's
    339 ft + brick wall). Doesn't have to drop to 0 — just lower."""
    bbip = _hr_bbip("NYY")
    nyy = retrodict_one(bbip, "NYY")
    sf = retrodict_one(bbip, "SF")
    assert sf.prob_hr <= nyy.prob_hr, (
        f"expected SF HR prob <= NYY's; got SF={sf.prob_hr:.2f} NYY={nyy.prob_hr:.2f}"
    )


def test_observed_outcome_only_populated_on_home_park() -> None:
    bbip = _hr_bbip("NYY")
    home = retrodict_one(bbip, "NYY")
    away = retrodict_one(bbip, "COL")
    assert home.observed_outcome == "hr"
    assert away.observed_outcome is None
    assert home.is_home_park is True
    assert away.is_home_park is False


def test_soft_liner_almost_never_hr() -> None:
    r = retrodict_one(_liner_bbip(), "STL")
    assert r.prob_hr < 0.05, f"soft liner should not be HR, got {r.prob_hr:.2f}"


# --- batched all-parks ---------------------------------------------------


def test_retrodict_bip_at_all_parks_returns_one_row_per_park() -> None:
    bbip = _hr_bbip("NYY")
    parks = ["NYY", "COL", "SF", "BOS", "DET"]
    results = retrodict_bip_at_all_parks(bbip, parks)
    assert len(results) == len(parks)
    assert [r.park_id for r in results] == parks
    # Exactly one row is the home park.
    n_home = sum(1 for r in results if r.is_home_park)
    assert n_home == 1


def test_retrodict_bip_at_all_parks_batched_matches_per_park_calls() -> None:
    """The batched all-parks path should produce identical probabilities
    to calling :func:`retrodict_one` per park (the seed-per-BIP RNG is
    consumed once, so the same MC samples flow into every park)."""
    bbip = _hr_bbip("NYY")
    parks = ["NYY", "COL", "BOS"]
    batched = retrodict_bip_at_all_parks(bbip, parks, seed_offset=999)
    for r_batched in batched:
        r_solo = retrodict_one(bbip, r_batched.park_id, seed_offset=999)
        # Probabilities use the same seed -> same trajectories -> same counts.
        assert r_batched.prob_hr == r_solo.prob_hr
        assert r_batched.prob_out == r_solo.prob_out


def test_retrodict_n_mc_field_propagates() -> None:
    bbip = _hr_bbip()
    r = retrodict_one(bbip, "NYY", n_mc=DEFAULT_N_MC)
    assert r.n_mc == DEFAULT_N_MC
    r5 = retrodict_one(bbip, "NYY", n_mc=5)
    assert r5.n_mc == 5


# --- bulk fused batch path (GPU-B; CPU fallback exercised here) ------------


def test_retrodict_bips_batch_shape_and_ordering() -> None:
    bbips = [_hr_bbip("NYY"), _liner_bbip("STL")]
    parks = ["NYY", "COL", "SF", "STL"]
    results = retrodict_bips_batch(bbips, parks, device="cpu")
    # One row per (bbip, park), ordered [bbip0 @ all parks, bbip1 @ all parks].
    assert len(results) == len(bbips) * len(parks)
    assert [r.park_id for r in results[: len(parks)]] == parks
    # Each bbip has exactly one home-park row.
    for b in range(len(bbips)):
        rows = results[b * len(parks) : (b + 1) * len(parks)]
        assert sum(1 for r in rows if r.is_home_park) == 1


def test_retrodict_bips_batch_probabilities_sum_to_one() -> None:
    results = retrodict_bips_batch([_hr_bbip("NYY")], ["NYY", "COL", "SF"], device="cpu")
    for r in results:
        total = r.prob_out + r.prob_1b + r.prob_2b + r.prob_3b + r.prob_hr
        assert total == pytest.approx(1.0, abs=1e-6)


def test_retrodict_bips_batch_is_deterministic_under_fixed_seed() -> None:
    bbips = [_hr_bbip("NYY")]
    a = retrodict_bips_batch(bbips, ["NYY", "COL"], seed_offset=777, device="cpu")
    b = retrodict_bips_batch(bbips, ["NYY", "COL"], seed_offset=777, device="cpu")
    assert [r.prob_hr for r in a] == [r.prob_hr for r in b]


def test_retrodict_bips_batch_home_park_carries_observed_only_there() -> None:
    results = retrodict_bips_batch([_hr_bbip("NYY")], ["NYY", "COL"], device="cpu")
    home = next(r for r in results if r.park_id == "NYY")
    away = next(r for r in results if r.park_id == "COL")
    assert home.is_home_park is True
    assert home.observed_outcome == "hr"
    assert away.is_home_park is False
    assert away.observed_outcome is None


def test_batch_matches_reference_within_tolerance() -> None:
    """The fused CPU batch (no weather -> still air per park) should track the
    float64 reference ``retrodict_bip_at_all_parks`` (also still air, same
    per-BIP seed -> same jitter). float32 + the fence-crossing-spray
    approximation can flip the odd MC draw, so we require close probabilities
    and matching dominant outcomes, not bit-equality."""
    bbips = [_hr_bbip("NYY"), _liner_bbip("STL")]
    parks = ["NYY", "COL", "SF", "BOS", "STL"]
    batched = retrodict_bips_batch(bbips, parks, seed_offset=999, device="cpu")
    by_key = {(r.bbip.bbip_key, r.park_id): r for r in batched}
    max_abs = 0.0
    dominant_agree = 0
    total = 0
    for bbip in bbips:
        for r_ref in retrodict_bip_at_all_parks(bbip, parks, seed_offset=999):
            r_bat = by_key[(bbip.bbip_key, r_ref.park_id)]
            ref_vec = (r_ref.prob_out, r_ref.prob_1b, r_ref.prob_2b, r_ref.prob_3b, r_ref.prob_hr)
            bat_vec = (r_bat.prob_out, r_bat.prob_1b, r_bat.prob_2b, r_bat.prob_3b, r_bat.prob_hr)
            max_abs = max(max_abs, max(abs(a - b) for a, b in zip(ref_vec, bat_vec, strict=False)))
            if ref_vec.index(max(ref_vec)) == bat_vec.index(max(bat_vec)):
                dominant_agree += 1
            total += 1
    assert max_abs <= 0.3, f"max per-class prob gap {max_abs:.2f} too large"
    assert dominant_agree / total >= 0.9, f"dominant-class agreement {dominant_agree}/{total}"
