"""Mac-side unit tests for the faithful battedball_outcome rolling-origin CV (Phase 4).

The ClickHouse + GPU end-to-end (load_arrays + training) is box-only; here we inject a synthetic
``FoldRunner`` so the PURE logic - home-park column selection, the carry sanity gate, and the
EvidenceRun -> experiment_results artifact assembly (reused from the driver) - is verified offline.
"""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.battedball.mlp.rolling_cv_eval import (
    CARRY_MAX_FT,
    CARRY_MIN_FT,
    CarryGateResult,
    FoldPrediction,
    build_artifact,
    carry_gate,
    run_faithful_cv,
    select_home_park_proba,
)
from bullpen_training.battedball.mlp.train import CARRY_MEAN_FT, CARRY_STD_FT

# --- select_home_park_proba ------------------------------------------------


def test_select_home_park_proba_picks_each_rows_own_park() -> None:
    park_order = ("AAA", "BBB", "CCC")
    proba = np.zeros((2, 3, 5))
    proba[0, 1] = [0.1, 0.2, 0.3, 0.2, 0.2]  # row 0 home park BBB (index 1)
    proba[1, 2] = [0.5, 0.1, 0.1, 0.1, 0.2]  # row 1 home park CCC (index 2)
    out = select_home_park_proba(proba, ["BBB", "CCC"], park_order)
    assert out.shape == (2, 5)
    np.testing.assert_array_equal(out[0], [0.1, 0.2, 0.3, 0.2, 0.2])
    np.testing.assert_array_equal(out[1], [0.5, 0.1, 0.1, 0.1, 0.2])


def test_select_home_park_proba_unknown_park_fails_loud() -> None:
    with pytest.raises(ValueError, match="not in park_order"):
        select_home_park_proba(np.zeros((1, 2, 5)), ["ZZZ"], ("AAA", "BBB"))


# --- carry_gate (un-standardise + plausibility) ----------------------------


def test_carry_gate_passes_when_every_park_is_plausible() -> None:
    res = carry_gate({"COL": 0.0, "NYY": (400.0 - CARRY_MEAN_FT) / CARRY_STD_FT})
    assert res.passed
    assert res.reasons == ()
    assert res.per_park_ft["COL"] == pytest.approx(CARRY_MEAN_FT)  # standardised 0 -> mean
    assert res.per_park_ft["NYY"] == pytest.approx(400.0)


def test_carry_gate_fails_an_out_of_range_park() -> None:
    # standardised +10 -> 225 + 10*80 = 1025 ft, well past CARRY_MAX_FT.
    res = carry_gate({"COL": 0.0, "ZZZ": 10.0})
    assert not res.passed
    assert any("ZZZ" in r and "outside" in r for r in res.reasons)
    assert res.per_park_ft["ZZZ"] > CARRY_MAX_FT


def test_carry_gate_fails_a_nan_park() -> None:
    res = carry_gate({"COL": float("nan")})
    assert not res.passed
    assert any("not finite" in r for r in res.reasons)


def test_carry_gate_is_inclusive_at_the_bounds() -> None:
    # Exactly CARRY_MIN_FT and CARRY_MAX_FT must PASS (the gate is inclusive [min, max]).
    lo_std = (CARRY_MIN_FT - CARRY_MEAN_FT) / CARRY_STD_FT
    hi_std = (CARRY_MAX_FT - CARRY_MEAN_FT) / CARRY_STD_FT
    res = carry_gate({"LO": lo_std, "HI": hi_std})
    assert res.passed
    assert res.per_park_ft["LO"] == pytest.approx(CARRY_MIN_FT)
    assert res.per_park_ft["HI"] == pytest.approx(CARRY_MAX_FT)


# --- run_faithful_cv -> artifact -------------------------------------------


def _signal_fold_runner(seed: int = 0):
    """A FoldRunner whose challenger captures a real conditional signal the marginal baseline can't,
    so the challenger genuinely beats the baseline on Brier AND stays well-calibrated (ECE guardrail
    passes) -> WOULD_PASS. M > the 2000 sample-size target."""
    rng = np.random.default_rng(seed)
    m = 2500
    dist_a = np.array([0.70, 0.10, 0.10, 0.05, 0.05])
    dist_b = np.array([0.05, 0.05, 0.10, 0.10, 0.70])

    def runner(fold):
        kind = rng.integers(0, 2, size=m)
        true_dist = np.where(kind[:, None] == 0, dist_a, dist_b)  # (m, 5) the conditional truth
        y = np.array([rng.choice(5, p=true_dist[i]) for i in range(m)], dtype=np.int64)
        challenger = true_dist  # perfectly calibrated to the conditional -> low Brier + low ECE
        marginal = 0.5 * dist_a + 0.5 * dist_b
        baseline = np.tile(marginal, (m, 1))  # marginal predictor: ignores the A/B signal
        return FoldPrediction(
            fold_id=fold.fold_id,
            test_year=fold.test_year,
            test_rows=m,
            y_true_int=y,
            challenger_proba=challenger,
            baseline_proba=baseline,
            retro=np.tile(marginal, (m, 1)),
        )

    return runner


def test_run_faithful_cv_builds_a_promotable_artifact() -> None:
    carry = CarryGateResult(passed=True, per_park_ft={"COL": 421.0}, reasons=())
    run, carry_out = run_faithful_cv(_signal_fold_runner(), carry_gate_result=carry)

    # The challenger (true conditional) clearly beats the marginal baseline on the final fold.
    assert run.verdict.outcome.name == "WOULD_PASS"
    # The faithful eval evidences the SERVED architecture, not the PerParkMLP proxy.
    assert run.challenger_name == "battedball_outcome"
    assert run.model_name == "batted_ball_mlp"  # criteria + box reconciliation name
    # retro provided -> the retro-ECE the absolute bar uses is computed (not None).
    assert run.challenger_retro_ece is not None
    assert run.baseline_retro_ece is not None
    # 4 folds scored.
    assert len(run.challenger_cv.per_fold) == 4
    assert run.challenger_cv.summary["brier"][0] < run.baseline_cv.summary["brier"][0]

    art = build_artifact(run, carry_out, data_source="full")
    assert art["status"] in ("passed", "failed")
    assert art["data_source"] == "full"
    fe = art["faithful_eval"]
    assert fe["carry_gate_passed"] is True
    assert "battedball_outcome" in fe["challenger_arch"]
    assert fe["carry_per_park_ft"]["COL"] == pytest.approx(421.0)


def test_run_faithful_cv_carries_a_failed_carry_gate_into_the_artifact() -> None:
    failed = CarryGateResult(
        passed=False,
        per_park_ft={"COL": 1025.0},
        reasons=("COL: carry 1025.0 ft outside [50, 550]",),
    )
    run, carry_out = run_faithful_cv(_signal_fold_runner(1), carry_gate_result=failed)
    art = build_artifact(run, carry_out)
    assert art["faithful_eval"]["carry_gate_passed"] is False
    assert art["faithful_eval"]["carry_gate_reasons"]


def test_run_faithful_cv_rejects_empty_folds() -> None:
    with pytest.raises(ValueError, match="no folds"):
        run_faithful_cv(
            _signal_fold_runner(),
            carry_gate_result=CarryGateResult(True, {}, ()),
            folds=[],
        )
