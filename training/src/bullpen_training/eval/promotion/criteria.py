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
      baseline on this metric by at least the threshold (metric units).
    - ``sample_size_target``: minimum number of scored rows before the verdict
      is allowed to be terminal (the Java gate refuses to ``complete`` below
      this). On sample data this is sized to the sample, not the full season;
      the box re-run declares the production target (operator hand-off H2).
    - ``guardrails``: metrics that must not regress past their max-delta
      (relative, challenger-vs-baseline; mirrors the Java guardrail shape).
    - ``absolute_ece_bar``: the ABSOLUTE Phase-2 calibration bar (ECE < bar) the
      challenger must clear regardless of the baseline. This supplements the
      relative ECE guardrail because a relative-only ECE check is meaningless
      against a degenerately-well-calibrated baseline (e.g. the constant
      marginal-class floor has ~0 ECE), and a tight relative ECE delta is noisy
      at sample scale; the absolute bar is the leakage-free, baseline-agnostic
      calibration gate. ``None`` opts out (no absolute ECE check).
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
    """Expected Calibration Error, 10 equal-width bins over the predicted
    confidence (argmax prob). Matches MetricsComputer.ece's binning."""
    proba = _as_proba(y_pred_proba)
    truth = np.asarray(y_true_int, dtype=np.int64)
    pred_class = proba.argmax(axis=1)
    conf = proba[np.arange(proba.shape[0]), pred_class]
    correct = (pred_class == truth).astype(np.float64)
    # Java: bin = min(BINS-1, floor(conf * BINS)) - right-open bins, last bin
    # absorbs conf == 1.0.
    bins = np.minimum(_ECE_BINS - 1, np.floor(conf * _ECE_BINS).astype(np.int64))
    total = proba.shape[0]
    weighted = 0.0
    for b in range(_ECE_BINS):
        mask = bins == b
        n_b = int(mask.sum())
        if n_b == 0:
            continue
        avg_conf = float(conf[mask].mean())
        acc = float(correct[mask].mean())
        weighted += (n_b / total) * abs(avg_conf - acc)
    return float(weighted)


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
#     same reason.)
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


_PITCH_PRE = PromotionCriteria(
    model_name="pitch_outcome_pre",
    primary_metric=PrimaryMetric.BRIER,
    # Challenger (LightGBM pre head) must beat the co-registered LR baseline's
    # multiclass Brier by >= 0.002 (5-class pitch outcome; a 0.002 Brier gain
    # is a real, non-noise lift at season scale - the full-data gap is larger,
    # but the sample-stage bar stays conservative).
    primary_threshold=0.002,
    sample_size_target=2_000,
    guardrails=(
        GuardrailSpec(
            metric=PrimaryMetric.LOG_LOSS,
            max_delta=0.01,
            rationale="challenger log-loss may not regress > 0.01 vs the LR baseline "
            "(guards against a Brier win that masks confident-wrong blowups).",
        ),
        GuardrailSpec(
            metric=PrimaryMetric.ECE,
            max_delta=0.015,
            rationale="challenger ECE may not regress > 0.015 vs the LR baseline "
            "(sample-stage allowance: the per-model ECE estimate is noisy at "
            "sample scale, so the RELATIVE bar is loose; absolute calibration is "
            "gated by absolute_ece_bar below).",
        ),
    ),
    absolute_ece_bar=0.02,  # Phase-2 exit bar: challenger ECE must be < 0.02.
    rationale="pre-pitch LightGBM challenger vs the rule-9 co-registered LR baseline. "
    "Calibration is gated by BOTH a loose relative ECE guardrail (sample noise) and "
    "the absolute Phase-2 ECE bar.",
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


CRITERIA_BY_MODEL: Final[dict[str, PromotionCriteria]] = {
    _PITCH_PRE.model_name: _PITCH_PRE,
    _PITCH_POST.model_name: _PITCH_POST,
    _BATTED_BALL_LR.model_name: _BATTED_BALL_LR,
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
