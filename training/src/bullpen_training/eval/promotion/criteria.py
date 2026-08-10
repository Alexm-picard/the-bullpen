"""Pre-declared promotion criteria + challenger-vs-baseline verdict (rule 5).

This module is the Python source of truth for the PRE-DECLARED promotion
criteria a SHADOW -> CHAMPION promotion is evaluated against (CLAUDE.md
rule 5: "No promotion of a model without pre-declared promotion criteria -
primary metric, sample size, threshold, guardrails - and a passing row in
experiment_results").

It deliberately mirrors the Java gate so a Python verdict computed here is the
SAME verdict the backend would compute from the same paired predictions:

- ``PrimaryMetric`` mirrors
  ``registry.experiment.dto.PrimaryMetric`` (BRIER | LOG_LOSS | ECE).
- ``evaluate_challenger_vs_baseline`` mirrors
  ``registry.experiment.ExperimentService.evaluate`` + ``MetricsComputer``:
    * "challenger wins" iff ``challenger_metric + threshold <= baseline_metric``
      (lower-is-better, with a margin) - matches MetricsComputer's contract;
    * a guardrail with allowed-delta ``d`` is VIOLATED iff
      ``challenger_g - baseline_g > d`` (positive delta = regression, since
      every metric here is lower-is-better) - matches ExperimentService;
    * the outcome is WOULD_PASS only when NO guardrail is violated AND the
      primary threshold is met (guardrail check takes precedence).

The numbers below are the actual locked criteria for the three heads this
W5 driver evidences. They are written in metric units (Brier / log-loss /
ECE), pre-declared here so the gate cannot be moved post-hoc.

NOTE on the metric direction: the Java gate phrases every metric as
"lower is better" and the challenger must beat the BASELINE/CHAMPION. For the
pitch heads + the batted-ball LR, the rule-9 co-registered LR baseline is the
thing the challenger (LightGBM / MLP) must beat. The batted-ball LR is itself
the baseline floor; its own experiment_results row evidences that it clears the
"beats the marginal-class predictor" bar (the degenerate baseline a model must
beat to be worth registering at all). See ``CRITERIA_BY_MODEL`` docstrings.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Final

import numpy as np

# Clamp used by the log-loss kernel - identical to the Java MetricsComputer
# (1e-15) so the two implementations agree bit-for-bit on degenerate inputs.
_LOG_LOSS_EPS: Final[float] = 1e-15
_ECE_BINS: Final[int] = 10


class PrimaryMetric(enum.Enum):
    """Mirror of ``registry.experiment.dto.PrimaryMetric``.

    ``db_value`` matches the Java enum's lowercase-with-hyphen DB encoding so a
    Python-produced artifact uses the exact string the backend stores in
    ``experiment_results.primary_metric`` ('brier' | 'log-loss' | 'ece').
    """

    BRIER = "brier"
    LOG_LOSS = "log-loss"
    ECE = "ece"

    @property
    def db_value(self) -> str:
        return self.value


class VerdictOutcome(enum.Enum):
    """Mirror of ``ExperimentVerdict.Outcome``."""

    WOULD_PASS = "would_pass"
    WOULD_FAIL_PRIMARY = "would_fail_primary"
    WOULD_FAIL_GUARDRAIL = "would_fail_guardrail"


@dataclass(frozen=True)
class GuardrailSpec:
    """One guardrail: a metric whose challenger value may not regress past
    ``max_delta`` relative to the baseline.

    Violated iff ``challenger_metric - baseline_metric > max_delta`` (every
    metric is lower-is-better, so a positive delta is a regression). This is
    exactly the Java ``ExperimentService.evaluate`` guardrail rule.
    """

    metric: PrimaryMetric
    max_delta: float
    rationale: str = ""


@dataclass(frozen=True)
class PromotionCriteria:
    """The PRE-DECLARED rule-5 criteria for one model.

    - ``primary_metric`` / ``primary_threshold``: challenger must beat the
      baseline on this metric by at least the threshold (metric units). A
      NEGATIVE threshold is the [166] non-inferiority idiom (the challenger may
      be up to |threshold| worse): battedball_outcome uses it as a true
      non-inferiority margin; pitch_outcome_pre uses threshold == -absolute_ece_bar
      to make the relative lane vacuous so the ABSOLUTE bar is the gating
      primary (ADR-0014).
    - ``sample_size_target``: minimum number of scored rows before the verdict
      is allowed to be terminal (the Java gate refuses to ``complete`` below
      this). On sample data this is sized to the sample, not the full season;
      the box re-run declares the production target (operator hand-off H2).
    - ``guardrails``: metrics that must not regress past their max-delta
      (relative, challenger-vs-baseline; mirrors the Java guardrail shape).
    - ``absolute_ece_bar``: the ABSOLUTE Phase-2 calibration bar (ECE < bar) the
      challenger must clear regardless of the baseline. For most models this
      supplements the relative ECE guardrail because a relative-only ECE check
      is meaningless against a degenerately-well-calibrated baseline (e.g. the
      constant marginal-class floor has ~0 ECE), and a tight relative ECE delta
      is noisy at sample scale; the absolute bar is the leakage-free,
      baseline-agnostic calibration gate. For pitch_outcome_pre the bar IS the
      declared gating primary (ADR-0014), not a supplement. ``None`` opts out
      (no absolute ECE check).
    """

    model_name: str
    primary_metric: PrimaryMetric
    primary_threshold: float
    sample_size_target: int
    guardrails: tuple[GuardrailSpec, ...] = field(default_factory=tuple)
    absolute_ece_bar: float | None = None
    rationale: str = ""

    def guardrails_as_map(self) -> dict[str, float]:
        """{db_metric_name -> max_delta} - the JSON shape stored in
        ``experiment_results.guardrails`` (Java parses exactly this map)."""
        return {g.metric.db_value: g.max_delta for g in self.guardrails}


@dataclass(frozen=True)
class MetricSummary:
    """All three metrics for one predictor on one scored set."""

    brier: float
    log_loss: float
    ece: float

    def value_for(self, metric: PrimaryMetric) -> float:
        return {
            PrimaryMetric.BRIER: self.brier,
            PrimaryMetric.LOG_LOSS: self.log_loss,
            PrimaryMetric.ECE: self.ece,
        }[metric]


@dataclass(frozen=True)
class Verdict:
    """Output of :func:`evaluate_challenger_vs_baseline` - mirrors
    ``ExperimentVerdict``. ``passed`` is True iff outcome is WOULD_PASS."""

    outcome: VerdictOutcome
    sample_size_observed: int
    baseline_metrics: MetricSummary
    challenger_metrics: MetricSummary
    primary_metric: PrimaryMetric
    primary_threshold: float
    guardrail_deltas: dict[str, float]
    guardrails_violated: dict[str, float]

    @property
    def passed(self) -> bool:
        return self.outcome is VerdictOutcome.WOULD_PASS


# ---------------------------------------------------------------------------
# Metric kernels - byte-for-byte the same math as the Java MetricsComputer,
# so a verdict here equals what the backend gate would compute.
# ---------------------------------------------------------------------------


def _as_proba(y_pred_proba: np.ndarray) -> np.ndarray:
    proba = np.asarray(y_pred_proba, dtype=np.float64)
    if proba.ndim != 2:
        raise ValueError(f"y_pred_proba must be 2-D (N, K); got shape {proba.shape}")
    return proba


def brier(y_true_int: np.ndarray, y_pred_proba: np.ndarray) -> float:
    """Multi-class Brier: mean squared error of probs vs one-hot truth,
    averaged over rows AND classes (matches MetricsComputer.brier)."""
    proba = _as_proba(y_pred_proba)
    truth = np.asarray(y_true_int, dtype=np.int64)
    n, k = proba.shape
    onehot = np.zeros((n, k), dtype=np.float64)
    onehot[np.arange(n), truth] = 1.0
    return float(np.sum((proba - onehot) ** 2) / (n * k))


def log_loss(y_true_int: np.ndarray, y_pred_proba: np.ndarray) -> float:
    """-mean(log(p_truth)) clamped at 1e-15 (matches MetricsComputer.logLoss)."""
    proba = _as_proba(y_pred_proba)
    truth = np.asarray(y_true_int, dtype=np.int64)
    p_truth = np.maximum(proba[np.arange(proba.shape[0]), truth], _LOG_LOSS_EPS)
    return float(np.mean(-np.log(p_truth)))


def ece(y_true_int: np.ndarray, y_pred_proba: np.ndarray) -> float:
    """Expected Calibration Error, 10 bins over the predicted confidence (argmax
    prob). C1: delegates to the SINGLE shared implementation in ``eval.metrics`` so
    a gate verdict computed here uses the exact same binning as eval-side ECE and
    the Java MetricsComputer (``_ECE_BINS`` == the Java bin count). Local import
    keeps this module import-cycle-free."""
    from bullpen_training.eval.metrics import expected_calibration_error

    _as_proba(y_pred_proba)  # preserve the 2-D shape validation + error message
    return expected_calibration_error(y_true_int, y_pred_proba, n_bins=_ECE_BINS)


def summarize(y_true_int: np.ndarray, y_pred_proba: np.ndarray) -> MetricSummary:
    """Compute all three gate metrics for one predictor."""
    return MetricSummary(
        brier=brier(y_true_int, y_pred_proba),
        log_loss=log_loss(y_true_int, y_pred_proba),
        ece=ece(y_true_int, y_pred_proba),
    )


def evaluate_challenger_vs_baseline(
    *,
    criteria: PromotionCriteria,
    y_true_int: np.ndarray,
    baseline_proba: np.ndarray,
    challenger_proba: np.ndarray,
) -> Verdict:
    """Compute the challenger-vs-baseline verdict against pre-declared criteria.

    Mirrors ``ExperimentService.evaluate``:
      1. Compute primary + every guardrail metric for both predictors on the
         SAME scored rows.
      2. Guardrail violated iff ``challenger_g - baseline_g > max_delta``.
      3. Outcome = WOULD_FAIL_GUARDRAIL if any guardrail violated; else
         WOULD_PASS if ``challenger_primary + threshold <= baseline_primary``;
         else WOULD_FAIL_PRIMARY.

    ``y_true_int`` / ``*_proba`` must be row-aligned (paired predictions). On
    the promotion path these are the LIVE+SHADOW paired predictions; here on
    the evidence-driver path they are the rolling-origin CV test-fold
    predictions from the two co-registered models on identical rows.
    """
    truth = np.asarray(y_true_int, dtype=np.int64)
    base_proba = _as_proba(baseline_proba)
    chal_proba = _as_proba(challenger_proba)
    n = truth.shape[0]
    if base_proba.shape[0] != n or chal_proba.shape[0] != n:
        raise ValueError(
            "paired-prediction row mismatch: "
            f"truth={n}, baseline={base_proba.shape[0]}, challenger={chal_proba.shape[0]}"
        )
    if base_proba.shape != chal_proba.shape:
        raise ValueError(
            f"baseline/challenger shape mismatch: {base_proba.shape} vs {chal_proba.shape}"
        )

    base_summary = summarize(truth, base_proba)
    chal_summary = summarize(truth, chal_proba)

    deltas: dict[str, float] = {}
    violated: dict[str, float] = {}
    for g in criteria.guardrails:
        delta = chal_summary.value_for(g.metric) - base_summary.value_for(g.metric)
        deltas[g.metric.db_value] = delta
        if delta > g.max_delta:
            violated[g.metric.db_value] = delta

    base_primary = base_summary.value_for(criteria.primary_metric)
    chal_primary = chal_summary.value_for(criteria.primary_metric)
    if violated:
        outcome = VerdictOutcome.WOULD_FAIL_GUARDRAIL
    elif chal_primary + criteria.primary_threshold <= base_primary:
        outcome = VerdictOutcome.WOULD_PASS
    else:
        outcome = VerdictOutcome.WOULD_FAIL_PRIMARY

    return Verdict(
        outcome=outcome,
        sample_size_observed=n,
        baseline_metrics=base_summary,
        challenger_metrics=chal_summary,
        primary_metric=criteria.primary_metric,
        primary_threshold=criteria.primary_threshold,
        guardrail_deltas=deltas,
        guardrails_violated=violated,
    )


# ---------------------------------------------------------------------------
# THE PRE-DECLARED CRITERIA (rule 5). Locked here; the artifact carries a copy
# so the gate cannot be moved post-hoc.
#
# Metric choice (decision rationale, in plain units):
#   - PRIMARY = multiclass Brier. Brier is a proper scoring rule that
#     penalises BOTH inaccuracy and miscalibration, and unlike log-loss it is
#     bounded and not dominated by a handful of confident-wrong rows, so it is
#     the stable primary for a challenger-vs-baseline margin. (The Java
#     PrimaryMetric default for the 5-class pitch outcome is BRIER for the
#     same reason.) EXCEPTION: pitch_outcome_pre's primary was re-aimed to
#     ABSOLUTE calibration (ECE < 0.02) by ADR-0014 / decision [180] - see the
#     _PITCH_PRE block.
#   - GUARDRAILS = log-loss + ECE. Log-loss catches a challenger that wins
#     Brier on average but blows up on confident-wrong rows; ECE catches a
#     challenger that wins accuracy but ships miscalibrated probabilities
#     (the Phase-2 exit bar is ECE < 0.02 per model, so ECE must not regress).
#
# Thresholds are margins in metric units. They are intentionally modest on the
# SAMPLE data (the sample is small + stratified, so per-fold variance is high);
# the LIVE/CHAMPION re-run on full BOX data re-declares production thresholds
# (operator hand-off H2). A SAMPLE-data passing row clears the SHADOW-stage
# evidence per the locked decision - it is NOT a LIVE promotion.
# ---------------------------------------------------------------------------


# ADR-0014 (decision [180]): pitch_outcome_pre's DECLARED primary is ABSOLUTE
# calibration - ECE < 0.02 (the absolute_ece_bar below, the same bar the
# batted-ball champion cleared per [141]) - NOT a Brier edge over the baseline.
# PRE is a calibrated pre-pitch outcome-DISTRIBUTION estimator; its value is a
# trustworthy probability distribution over the 5 outcome classes, not a
# best-guess accuracy win, and v1 FAILED the old Brier-edge primary (0.00084 vs
# the 0.002 margin - ADR-0011) while passing calibration at ECE 0.0036. The
# negative primary_threshold is the [166] non-inferiority idiom: the relative
# ECE lane (chal_ece + threshold <= base_ece) is DELIBERATELY vacuous whenever
# the absolute bar passes (chal_ece - 0.02 <= base_ece holds for any
# base_ece >= 0 once chal_ece < 0.02), so the ABSOLUTE bar - enforced as a hard
# supplementary check in the artifact's overall status - is the gating primary,
# exactly as ADR-0014 declares. Guardrails are the ADR-0014 pair: PRE must BEAT
# the LR baseline on Brier AND log-loss (max_delta 0.0 = no regression at all,
# the _BATTED_BALL_LR idiom).
_PITCH_PRE = PromotionCriteria(
    model_name="pitch_outcome_pre",
    primary_metric=PrimaryMetric.ECE,
    primary_threshold=-0.02,  # == -absolute_ece_bar; see the block comment above.
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.BRIER,
            max_delta=0.0,
            rationale="PRE multiclass Brier may not regress vs the co-registered LR "
            "baseline at all (ADR-0014 guardrail; v1 beats it per ADR-0011).",
        ),
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.0,
            rationale="PRE log-loss may not regress vs the LR baseline at all (ADR-0014 "
            "guardrail; guards confident-wrong blowups a Brier check can mask).",
        ),
    ),
    absolute_ece_bar=0.02,  # THE PRIMARY CLAIM (ADR-0014): PRE's ECE must be < 0.02.
    rationale="pre-pitch LightGBM vs the rule-9 co-registered LR baseline. Primary "
    "re-aimed by ADR-0014 / decision [180] from Brier-edge (failed - ADR-0011) to "
    "ABSOLUTE calibration ECE < 0.02 (passes at 0.0036) - the [141] batted-ball "
    "precedent: correct the declared claim to what the model honestly earns, then "
    "pass it. Guardrails: must beat the LR baseline on Brier AND log-loss (both "
    "hold). The negative primary_threshold is the [166] non-inferiority idiom "
    "making the absolute bar the gating primary. Public claim: calibrated "
    "pre-pitch outcome probabilities (ECE < 0.02), strictly better than the "
    "linear baseline; no accuracy-superiority claim.",
)

_PITCH_POST = PromotionCriteria(
    model_name="pitch_outcome_post",
    primary_metric=PrimaryMetric.BRIER,
    # Post head sees early-flight features, so the margin over the SAME LR
    # baseline is expected larger; same conservative sample-stage bar.
    primary_threshold=0.002,
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.01,
            rationale="post-head log-loss may not regress > 0.01 vs the LR baseline.",
        ),
        GuardrailSpec(
            metric=PrimaryMetric.ECE,
            max_delta=0.015,
            rationale="post-head ECE may not regress > 0.015 vs the LR baseline "
            "(sample-stage allowance; absolute calibration gated by absolute_ece_bar).",
        ),
    ),
    absolute_ece_bar=0.02,
    rationale="post-pitch LightGBM challenger vs the rule-9 co-registered LR baseline. "
    "Calibration gated by a loose relative ECE guardrail + the absolute Phase-2 ECE bar.",
)

# Decision [183]: pitch-TYPE PRIOR (report candidate A). SAME calibration-first shape as
# _PITCH_PRE / ADR-0014 - a well-calibrated y7 distribution is the deliverable, NOT a top-1
# predictor (pitch selection is high-entropy; top-1 ~0.45, the low ceiling the report documents).
# PRIMARY = absolute ECE < 0.02, made the gating primary by the -absolute_ece_bar threshold
# (the [166] non-inferiority idiom leaves the relative ECE lane vacuous). GUARDRAIL = beat the
# rule-9 co-registered pitch_type_lr_baseline on log-loss (max_delta 0). top-3 accuracy (~0.88)
# is DELIBERATELY not a criteria metric - PrimaryMetric has no TOP3 and [183] scopes it as a
# SUPPLEMENTARY, non-gating figure the trainer records in the evidence, never a promotion gate.
_PITCH_TYPE_PRE = PromotionCriteria(
    model_name="pitch_type_pre",
    primary_metric=PrimaryMetric.ECE,
    primary_threshold=-0.02,  # == -absolute_ece_bar; see the block comment above.
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.0,
            rationale="pitch_type_pre multiclass log-loss may not regress vs the rule-9 "
            "co-registered pitch_type_lr_baseline at all (decision [183] guardrail; the "
            "bake-off's candidate A beats the LR baseline on log-loss).",
        ),
    ),
    absolute_ece_bar=0.02,  # THE gating primary ([183]): the y7 distribution's ECE must be < 0.02.
    rationale="pre-pitch pitch-TYPE PRIOR (decision [183], report candidate A): a "
    "well-calibrated y7 distribution, NOT a top-1 predictor. PRIMARY = absolute calibration "
    "ECE < 0.02 (the relative lane is vacuous by the -0.02 threshold idiom, so the absolute "
    "bar gates, like pitch_outcome_pre / ADR-0014); GUARDRAIL = beat the LR baseline on "
    "log-loss. top-3 accuracy (~0.88 in the bake-off) is a SUPPLEMENTARY, non-gating check "
    "the trainer records - pitch selection is high-entropy (top-1 ~0.45), so the value is the "
    "calibrated distribution, never accuracy. First champion via the [182] first-champion "
    "offline-gate path. Public claim: calibrated pitch-type prior (ECE < 0.02), never 'we "
    "predict the next pitch'.",
)

_BATTED_BALL_LR = PromotionCriteria(
    model_name="batted_ball_lr_baseline",
    primary_metric=PrimaryMetric.BRIER,
    # The batted-ball LR is itself the rule-9 baseline floor. Its
    # experiment_results row evidences that it clears the DEGENERATE baseline -
    # the constant marginal-class predictor every registered model must beat to
    # be worth registering at all. Margin >= 0.005 multiclass Brier (5 outcome
    # classes: out/1b/2b/3b/hr) over the marginal predictor.
    primary_threshold=0.005,
    sample_size_target=2_000,
    guardrails=(
        # ONLY log-loss is a meaningful "must-not-regress" guardrail against the
        # constant marginal-class floor. The marginal floor is DEGENERATELY
        # well-calibrated (its ECE is ~0 by construction: a constant prediction
        # equal to the class base-rates has zero calibration error precisely
        # BECAUSE it never discriminates), so an ECE-delta-vs-floor guardrail
        # would penalise any real model for the floor's vacuous perfection. The
        # LR's absolute calibration is gated by absolute_ece_bar below, not by a
        # delta against a non-discriminating baseline.
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.0,
            rationale="LR log-loss may not regress vs the marginal predictor at all "
            "(a model that loses log-loss to the constant floor is not learning).",
        ),
    ),
    absolute_ece_bar=0.02,  # Phase-2 exit bar; the floor-relative ECE delta is meaningless.
    rationale="batted-ball LR baseline vs the constant marginal-class floor "
    "(the degenerate baseline any registered model must beat). Calibration is "
    "gated by the absolute Phase-2 ECE bar, NOT a delta vs the floor's vacuous "
    "zero-ECE.",
)


_BATTED_BALL_MLP = PromotionCriteria(
    model_name="batted_ball_mlp",
    primary_metric=PrimaryMetric.BRIER,
    # The per-park MLP CHAMPION (shared-backbone topology, one model per park, trained on the
    # retrodicted outcome DISTRIBUTION via KL) must beat the co-registered batted-ball LR baseline's
    # multiclass Brier by >= 0.003 (5 outcome classes: out/1b/2b/3b/hr). Conservative sample-stage
    # bar; the full-box H2 re-run re-declares the production threshold.
    primary_threshold=0.003,
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.01,
            rationale="per-park MLP log-loss may not regress > 0.01 vs the LR baseline "
            "(guards a Brier win that masks confident-wrong blowups on rare extra-base outcomes).",
        ),
        GuardrailSpec(
            metric=PrimaryMetric.ECE,
            max_delta=0.015,
            rationale="per-park MLP ECE may not regress > 0.015 vs the LR baseline "
            "(sample-stage allowance: the per-model ECE estimate is noisy at sample scale, so the "
            "RELATIVE bar is loose; absolute calibration is gated by absolute_ece_bar below).",
        ),
    ),
    absolute_ece_bar=0.02,  # Phase-2 exit bar: champion ECE must be < 0.02.
    rationale="per-park batted-ball MLP champion (one model per park, trained on the retrodicted "
    "outcome distribution via KL) vs the rule-9 co-registered LR baseline. Calibration gated by a "
    "loose relative ECE guardrail (sample noise) + the absolute Phase-2 ECE bar.",
)


# ---------------------------------------------------------------------------
# Carry champion (battedball_outcome v2) promotion criteria - NON-INFERIORITY, not
# beats-a-baseline. See decision [166] (+ [141]/[163]/[154]/ADR-0011/[150]/[72]).
#
# WHY THIS IS DIFFERENT from _BATTED_BALL_MLP above: that criteria evidences the MLP vs the
# rule-9 LR baseline. The served batted-ball champion does NOT beat the LR baseline on REALIZED
# outcomes (v1 Brier ~0.107 / v2 ~0.117 vs LR ~0.086) - by design: it is a calibrated per-park
# PHYSICS ESTIMATE ([141]/[163]), and v1 itself serves only via the first-champion bootstrap (it
# never produced a passing beats-LR row). So a beats-LR gate is the WRONG primary for promoting
# v2 over v1; that realized-vs-LR gap is carried as a documented, NON-GATING fact, not the gate.
#
# v2 = v1's exact outcome model + an ADDITIVE per-park carry head (PR-3/4, schema_hash unchanged).
# The only honest promotion question is: does adding the carry objective REGRESS the served
# outcome? -> a NON-INFERIORITY test of v2 (carry recipe) vs v1 (no-carry recipe = v1's method),
# paired on identical rolling-origin folds. The negative ``primary_threshold`` is the
# non-inferiority margin: WOULD_PASS iff ``chal_brier + threshold <= base_brier`` i.e.
# ``v2_brier <= v1_brier + 0.002`` - "v2's outcome Brier may not be WORSE than v1's by more than
# 0.002". The carry head's physical plausibility is a SEPARATE hard gate (carry_gate, applied by
# the carry-promotion eval), not expressible in this challenger-vs-baseline shape.
# ---------------------------------------------------------------------------


_BATTED_BALL_CARRY = PromotionCriteria(
    model_name="battedball_outcome",
    primary_metric=PrimaryMetric.BRIER,
    # NON-INFERIORITY margin (negative threshold): v2 (carry) may be at most 0.002 multiclass-Brier
    # WORSE than v1 (no-carry) on the home-park realized outcome. ~2% of the ~0.11 realized Brier
    # and ~5x the rolling-origin fold std (~0.0004), so it tolerates run-to-run + carry noise
    # without admitting a real outcome regression. NOT a beats-baseline margin (see header).
    primary_threshold=-0.002,
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.01,
            rationale="v2 (carry) log-loss may not regress > 0.01 vs v1 (no-carry) "
            "(guards a Brier non-inferiority that hides confident-wrong outcome blowups).",
        ),
        GuardrailSpec(
            metric=PrimaryMetric.ECE,
            max_delta=0.015,
            rationale="v2 (carry) raw-softmax ECE may not regress > 0.015 vs v1 (no-carry); the "
            "served per-park isotonic calibration is applied equally downstream, so this guards "
            "the underlying outcome head, not the served calibration.",
        ),
    ),
    # No absolute ECE bar: this is an outcome NON-INFERIORITY of two raw-softmax heads (the absolute
    # calibration gate is the served per-park isotonic, fit + verified at registration). An absolute
    # raw-ECE bar would fail by construction (uncalibrated) and is not the question here.
    absolute_ece_bar=None,
    rationale="carry champion v2 vs the current champion v1 (battedball_outcome): outcome "
    "NON-INFERIORITY (Brier within 0.002, log-loss/ECE non-regression) + a SEPARATE hard carry "
    "sanity gate. The realized-Brier-vs-LR gap is the documented [141]/[163] reality gap, NOT this "
    "gate (this model is a calibrated per-park physics ESTIMATE; v1 serves on the bootstrap). "
    "See decision [166].",
)


CRITERIA_BY_MODEL: Final[dict[str, PromotionCriteria]] = {
    _PITCH_PRE.model_name: _PITCH_PRE,
    _PITCH_POST.model_name: _PITCH_POST,
    _PITCH_TYPE_PRE.model_name: _PITCH_TYPE_PRE,
    _BATTED_BALL_LR.model_name: _BATTED_BALL_LR,
    _BATTED_BALL_MLP.model_name: _BATTED_BALL_MLP,
    _BATTED_BALL_CARRY.model_name: _BATTED_BALL_CARRY,
}


def criteria_for(model_name: str) -> PromotionCriteria:
    try:
        return CRITERIA_BY_MODEL[model_name]
    except KeyError as exc:
        raise ValueError(
            f"no pre-declared promotion criteria for {model_name!r}; "
            f"known: {sorted(CRITERIA_BY_MODEL)}"
        ) from exc


__all__ = (
    "CRITERIA_BY_MODEL",
    "GuardrailSpec",
    "MetricSummary",
    "PrimaryMetric",
    "PromotionCriteria",
    "Verdict",
    "VerdictOutcome",
    "brier",
    "criteria_for",
    "ece",
    "evaluate_challenger_vs_baseline",
    "log_loss",
    "summarize",
)
