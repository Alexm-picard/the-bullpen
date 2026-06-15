"""Tests for the pre-declared promotion criteria + the challenger-vs-baseline
verdict (W5). The verdict math is pinned to the Java gate semantics
(MetricsComputer + ExperimentService.evaluate)."""

from __future__ import annotations

import numpy as np
import pytest

from bullpen_training.eval.promotion.criteria import (
    CRITERIA_BY_MODEL,
    GuardrailSpec,
    PrimaryMetric,
    PromotionCriteria,
    VerdictOutcome,
    brier,
    criteria_for,
    ece,
    evaluate_challenger_vs_baseline,
    log_loss,
)

# --- metric kernels match the Java MetricsComputer contract ----------------


def test_brier_perfect_is_zero() -> None:
    y = np.array([0, 1, 2])
    proba = np.eye(3)[y]
    assert brier(y, proba) == pytest.approx(0.0)


def test_brier_matches_hand_computation() -> None:
    # One row, 2 classes, truth=0, pred=[0.7, 0.3].
    # squared err = (0.7-1)^2 + (0.3-0)^2 = 0.09 + 0.09 = 0.18; /(N*K)=/2 = 0.09.
    y = np.array([0])
    proba = np.array([[0.7, 0.3]])
    assert brier(y, proba) == pytest.approx(0.09)


def test_log_loss_perfect_is_zero() -> None:
    y = np.array([0, 1])
    proba = np.array([[1.0, 0.0], [0.0, 1.0]])
    assert log_loss(y, proba) == pytest.approx(0.0, abs=1e-12)


def test_log_loss_clamps_zero_prob() -> None:
    # truth prob exactly 0 -> clamps at 1e-15, no -inf.
    y = np.array([0])
    proba = np.array([[0.0, 1.0]])
    val = log_loss(y, proba)
    assert np.isfinite(val)
    assert val == pytest.approx(-np.log(1e-15), rel=1e-9)


def test_ece_perfectly_calibrated_confident_correct_is_zero() -> None:
    # All rows confident (1.0) and correct -> conf==acc==1 in the top bin.
    y = np.array([0, 1, 0, 1])
    proba = np.array([[1.0, 0.0], [0.0, 1.0], [1.0, 0.0], [0.0, 1.0]])
    assert ece(y, proba) == pytest.approx(0.0)


# --- verdict: challenger wins on primary, no guardrail violation -----------


def _two_class_proba(p_class0: np.ndarray) -> np.ndarray:
    p0 = np.asarray(p_class0, dtype=np.float64)
    return np.stack([p0, 1.0 - p0], axis=1)


def test_verdict_would_pass_when_challenger_beats_primary_and_no_guardrail() -> None:
    rng = np.random.default_rng(0)
    n = 400
    y = rng.integers(0, 2, n)
    # Challenger is sharper toward truth (lower Brier); baseline is fuzzier.
    base_p0 = np.where(y == 0, 0.6, 0.4)
    chal_p0 = np.where(y == 0, 0.8, 0.2)
    criteria = PromotionCriteria(
        model_name="t",
        primary_metric=PrimaryMetric.BRIER,
        primary_threshold=0.01,
        sample_size_target=10,
        guardrails=(GuardrailSpec(PrimaryMetric.LOG_LOSS, 0.5),),
    )
    v = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y,
        baseline_proba=_two_class_proba(base_p0),
        challenger_proba=_two_class_proba(chal_p0),
    )
    assert v.outcome is VerdictOutcome.WOULD_PASS
    assert v.passed
    assert v.challenger_metrics.brier + criteria.primary_threshold <= v.baseline_metrics.brier


def test_verdict_fails_primary_when_margin_not_met() -> None:
    rng = np.random.default_rng(1)
    n = 400
    y = rng.integers(0, 2, n)
    base_p0 = np.where(y == 0, 0.8, 0.2)
    chal_p0 = np.where(y == 0, 0.81, 0.19)  # barely better, below the margin
    criteria = PromotionCriteria(
        model_name="t",
        primary_metric=PrimaryMetric.BRIER,
        primary_threshold=0.05,  # demands a big margin
        sample_size_target=10,
    )
    v = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y,
        baseline_proba=_two_class_proba(base_p0),
        challenger_proba=_two_class_proba(chal_p0),
    )
    assert v.outcome is VerdictOutcome.WOULD_FAIL_PRIMARY
    assert not v.passed


def test_verdict_fails_guardrail_takes_precedence_over_primary() -> None:
    # Guardrail precedence: the PRIMARY metric (Brier) is MET by the challenger,
    # but a guardrail metric (log-loss) regresses -> the outcome is
    # WOULD_FAIL_GUARDRAIL, NOT WOULD_PASS. This exercises the Java rule that the
    # guardrail check fires before the primary-threshold check.
    #
    # Two groups make Brier and log-loss diverge distributionally (per-row they
    # always move together; across a heavy tail they need not):
    #   group A (truth 0, 200 rows): challenger MUCH better (0.99 vs 0.55) -> big
    #     bounded Brier gain.
    #   group B (truth 1, 30 rows): challenger confidently WRONG (0.999 toward
    #     class 0) -> unbounded log-loss blow-up, only a bounded Brier hit.
    # Net: challenger wins Brier overall (primary met) yet its mean log-loss is
    # WORSE than baseline (guardrail violated).
    n_a, n_b = 200, 30
    y = np.concatenate([np.zeros(n_a), np.ones(n_b)]).astype(np.int64)
    base_p0 = np.concatenate([np.full(n_a, 0.55), np.full(n_b, 1.0 - 0.6)])
    chal_p0 = np.concatenate([np.full(n_a, 0.99), np.full(n_b, 0.999)])
    criteria = PromotionCriteria(
        model_name="t",
        primary_metric=PrimaryMetric.BRIER,
        primary_threshold=0.0,
        sample_size_target=2,
        guardrails=(GuardrailSpec(PrimaryMetric.LOG_LOSS, 0.001),),  # tiny allowance
    )
    v = evaluate_challenger_vs_baseline(
        criteria=criteria,
        y_true_int=y,
        baseline_proba=_two_class_proba(base_p0),
        challenger_proba=_two_class_proba(chal_p0),
    )
    # sanity: primary (Brier) is met by the challenger, yet log-loss regresses.
    assert v.challenger_metrics.brier <= v.baseline_metrics.brier
    assert v.challenger_metrics.log_loss > v.baseline_metrics.log_loss + 0.001
    assert v.outcome is VerdictOutcome.WOULD_FAIL_GUARDRAIL
    assert "log-loss" in v.guardrails_violated


def test_guardrail_violation_rule_is_strictly_greater_than() -> None:
    """delta == max_delta is NOT a violation (matches Java `delta > max`)."""
    y = np.array([0, 1, 0, 1])
    base = _two_class_proba(np.where(y == 0, 0.6, 0.4))
    # Equal predictions -> delta 0 on every metric; max_delta 0.0 -> not violated.
    criteria = PromotionCriteria(
        model_name="t",
        primary_metric=PrimaryMetric.BRIER,
        primary_threshold=0.0,
        sample_size_target=2,
        guardrails=(GuardrailSpec(PrimaryMetric.ECE, 0.0),),
    )
    v = evaluate_challenger_vs_baseline(
        criteria=criteria, y_true_int=y, baseline_proba=base, challenger_proba=base
    )
    assert v.guardrails_violated == {}


def test_paired_row_mismatch_raises() -> None:
    criteria = criteria_for("pitch_outcome_pre")
    y = np.array([0, 1, 2, 3, 4])
    proba = np.full((5, 5), 0.2)
    with pytest.raises(ValueError, match="row mismatch"):
        evaluate_challenger_vs_baseline(
            criteria=criteria,
            y_true_int=y,
            baseline_proba=proba,
            challenger_proba=np.full((4, 5), 0.2),
        )


# --- the pre-declared criteria are well-formed -----------------------------


def test_all_registered_models_have_criteria() -> None:
    assert set(CRITERIA_BY_MODEL) == {
        "pitch_outcome_pre",
        "pitch_outcome_post",
        "batted_ball_lr_baseline",
        "batted_ball_mlp",
    }


def test_criteria_are_pre_declared_with_required_rule5_fields() -> None:
    for name, c in CRITERIA_BY_MODEL.items():
        assert c.model_name == name
        assert c.primary_threshold >= 0.0  # margin in metric units
        assert c.sample_size_target > 0
        assert c.guardrails, f"{name} declares no guardrails"
        # guardrails map uses Java db metric names.
        gmap = c.guardrails_as_map()
        for k in gmap:
            assert k in {"brier", "log-loss", "ece"}


def test_primary_metric_db_value_matches_java_encoding() -> None:
    assert PrimaryMetric.BRIER.db_value == "brier"
    assert PrimaryMetric.LOG_LOSS.db_value == "log-loss"
    assert PrimaryMetric.ECE.db_value == "ece"
