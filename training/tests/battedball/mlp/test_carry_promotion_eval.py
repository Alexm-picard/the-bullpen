"""Mac-side unit tests for the carry-promotion NON-INFERIORITY eval (Phase 4, decision [166]).

The ClickHouse + GPU per-fold ablation training is box-only; here we inject a synthetic FoldRunner
so the PURE logic - the non-inferiority criteria, the verdict, and the carry HARD-gate status
override in the artifact - is verified offline. Mirrors test_rolling_cv_eval.py.
"""

from __future__ import annotations

import numpy as np

from bullpen_training.battedball.mlp.carry_promotion_eval import (
    BASELINE_NAME,
    CHALLENGER_NAME,
    PROMOTION_CRITERIA_NAME,
    build_carry_promotion_artifact,
    run_carry_promotion_cv,
)
from bullpen_training.battedball.mlp.rolling_cv_eval import CarryGateResult, FoldPrediction
from bullpen_training.eval.promotion.criteria import PrimaryMetric, criteria_for

_M = 2500  # > the 2000 sample-size target
_TRUE = np.array([0.60, 0.12, 0.12, 0.08, 0.08])  # a non-degenerate conditional outcome dist


def _noninferior_runner(seed: int = 0):
    """Carry (challenger) and no-carry (baseline) recipes make IDENTICAL home-park predictions, so
    the carry objective did not regress the outcome head, and the challenger is non-inferior (Brier
    delta 0 <= the 0.002 margin; no guardrail regression) -> WOULD_PASS."""
    rng = np.random.default_rng(seed)

    def runner(fold):
        y = rng.choice(5, size=_M, p=_TRUE).astype(np.int64)
        proba = np.tile(_TRUE, (_M, 1))  # both recipes predict the true conditional
        return FoldPrediction(
            fold_id=fold.fold_id,
            test_year=fold.test_year,
            test_rows=_M,
            y_true_int=y,
            challenger_proba=proba,
            baseline_proba=proba.copy(),
            retro=None,
        )

    return runner


def _regression_runner(seed: int = 1):
    """The carry recipe (challenger) REGRESSES the outcome head: it predicts a near-uniform dist
    while the no-carry baseline predicts the true conditional. The challenger Brier is far worse
    than the baseline's (well past the 0.002 margin) -> the outcome verdict FAILS."""
    rng = np.random.default_rng(seed)

    def runner(fold):
        y = rng.choice(5, size=_M, p=_TRUE).astype(np.int64)
        baseline = np.tile(_TRUE, (_M, 1))
        challenger = np.tile(np.full(5, 0.2), (_M, 1))  # uniform: ignores the signal
        return FoldPrediction(
            fold_id=fold.fold_id,
            test_year=fold.test_year,
            test_rows=_M,
            y_true_int=y,
            challenger_proba=challenger,
            baseline_proba=baseline,
            retro=None,
        )

    return runner


def test_carry_promotion_criteria_is_noninferiority() -> None:
    c = criteria_for(PROMOTION_CRITERIA_NAME)
    assert c.primary_metric is PrimaryMetric.BRIER
    # NEGATIVE threshold = a non-inferiority margin (challenger may be up to 0.002 WORSE), NOT a
    # beats-the-baseline margin. This is the load-bearing distinction of decision [166].
    assert c.primary_threshold == -0.002
    # No absolute ECE bar: the served per-park isotonic is the absolute-calibration gate (a raw
    # head-to-head here would fail an absolute raw-ECE bar by construction).
    assert c.absolute_ece_bar is None


def test_run_carry_promotion_cv_labels_the_recipes() -> None:
    run, _carry = run_carry_promotion_cv(
        _noninferior_runner(), carry_gate_result=CarryGateResult(True, {"COL": 421.0}, ())
    )
    assert run.model_name == PROMOTION_CRITERIA_NAME == "battedball_outcome"
    assert run.baseline_name == BASELINE_NAME
    assert run.challenger_name == CHALLENGER_NAME
    assert len(run.challenger_cv.per_fold) == 4  # 4 rolling-origin folds


def test_noninferior_outcome_and_passing_carry_promotes() -> None:
    carry = CarryGateResult(passed=True, per_park_ft={"COL": 421.0, "NYY": 404.0}, reasons=())
    run, carry_out = run_carry_promotion_cv(_noninferior_runner(), carry_gate_result=carry)
    assert run.verdict.outcome.name == "WOULD_PASS"

    art = build_carry_promotion_artifact(run, carry_out, data_source="full")
    assert art["status"] == "passed"
    assert art["model_name"] == "battedball_outcome"
    assert art["carry_promotion"]["outcome_noninferiority_passed"] is True
    assert art["carry_promotion"]["carry_gate_passed"] is True
    assert art["carry_gate"]["passed"] is True
    assert art["carry_gate"]["hard_gate"] is True
    # the realized-vs-LR reality gap is documented as NON-gating
    assert "NOT a beats-the-LR-baseline gate" in art["carry_promotion"]["realized_vs_lr_gap"]


def test_carry_gate_failure_blocks_even_when_outcome_is_noninferior() -> None:
    # Outcome is non-inferior (identical recipes) but the carry head is broken: the HARD carry gate
    # must drag the artifact status to 'failed'. This is the whole point of the separate carry gate.
    failed_carry = CarryGateResult(
        passed=False,
        per_park_ft={"COL": 1025.0},
        reasons=("COL: carry 1025.0 ft outside [50, 550]",),
    )
    run, carry_out = run_carry_promotion_cv(_noninferior_runner(), carry_gate_result=failed_carry)
    assert run.verdict.outcome.name == "WOULD_PASS"  # outcome itself is fine

    art = build_carry_promotion_artifact(run, carry_out)
    assert art["status"] == "failed"  # carry gate is HARD
    assert art["carry_promotion"]["outcome_noninferiority_passed"] is True
    assert art["carry_promotion"]["carry_gate_passed"] is False
    assert art["carry_gate"]["passed"] is False
    assert art["carry_gate"]["reasons"]


def test_outcome_regression_fails_even_when_carry_passes() -> None:
    carry = CarryGateResult(passed=True, per_park_ft={"COL": 421.0}, reasons=())
    run, carry_out = run_carry_promotion_cv(_regression_runner(), carry_gate_result=carry)
    assert run.verdict.outcome.name != "WOULD_PASS"  # the carry recipe regressed the outcome

    art = build_carry_promotion_artifact(run, carry_out)
    assert art["status"] == "failed"
    assert art["carry_promotion"]["outcome_noninferiority_passed"] is False
    assert art["carry_promotion"]["carry_gate_passed"] is True
