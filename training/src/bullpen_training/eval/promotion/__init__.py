"""Promotion-evidence drivers (W5 - the rule-5 evidence path, Mac side).

This subpackage produces the ``experiment_results`` evidence a SHADOW ->
CHAMPION promotion reads (CLAUDE.md rule 5). It is the *evidence* half of
promotion only: it computes a challenger-vs-baseline verdict against
PRE-DECLARED criteria and writes an ``experiment_results``-shaped artifact.
It performs NO promotion (rule 6 - promotion stays human-gated) and writes
nothing to the registry SQLite. A human reads the artifact, then promotes.

Three pieces:

- ``criteria``    - the pre-declared promotion criteria per model + the
                    challenger-vs-baseline verdict math, mirroring the Java
                    gate (``MetricsComputer`` + ``ExperimentService.evaluate``)
                    so the Python verdict equals what Java would compute.
- ``sample_loader`` - a parquet-directory ``FeatureLoader`` over the
                    ``samples/dev/`` mirror layout (ADR-0007), plus a
                    deterministic sample generator so the gate can be proven
                    end-to-end on the Mac without ClickHouse / MinIO.
- ``driver``      - the rolling-origin CV + evidence driver, CLI-runnable for
                    ``pitch_outcome_pre``, ``pitch_outcome_post``, and
                    ``batted_ball_lr_baseline``.

Rolling-origin temporal CV ONLY (it reuses ``eval.cv_harness.run``); 2026 is
holdout-only and never appears in any split (rule 13); no ``random_state`` on
any split (the per-fold subsets are pure date windows).
"""

from bullpen_training.eval.promotion.criteria import (
    CRITERIA_BY_MODEL,
    GuardrailSpec,
    MetricSummary,
    PrimaryMetric,
    PromotionCriteria,
    Verdict,
    VerdictOutcome,
    evaluate_challenger_vs_baseline,
)

__all__ = (
    "CRITERIA_BY_MODEL",
    "GuardrailSpec",
    "MetricSummary",
    "PrimaryMetric",
    "PromotionCriteria",
    "Verdict",
    "VerdictOutcome",
    "evaluate_challenger_vs_baseline",
)
