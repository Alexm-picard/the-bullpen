package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One row of the public model-accuracy scorecard ({@code GET /v1/ops/accuracy}), built from a
 * committed promotion-evidence JSON.
 *
 * <p>HONESTY: every number here is OFFLINE held-out evaluation (rolling-origin CV / full-box gate
 * evidence captured at training time), NOT live production accuracy - {@code evaluation} states
 * this verbatim and is non-null on every row. {@code gateStatus} + {@code verdictOutcome} ride
 * through from the gate so a FAILED model (e.g. {@code pitch_outcome_pre} would_fail_primary,
 * {@code battedball_outcome} would_fail_guardrail) is never implied to be accurate-and-serving.
 * {@code stage} is a static hint reflecting decisions [165]/[154]/[163] (the live registry stage is
 * shown on the Ops fleet table). Every metric is nullable so a missing field renders as an em-dash
 * in the UI, never as 0 - the {@code @Schema(nullable = true)} markers keep the generated contract
 * honest about that (Schemathesis response-schema conformance, C-33).
 *
 * <p>{@code modelName} is the registry/serving name; {@code evidenceModelName} is the training
 * /challenger name (e.g. {@code batted_ball_mlp} -> {@code battedball_outcome}) - both are surfaced
 * so the reconciliation is explicit, not hidden. For the batted-ball row, {@code ece} is the
 * mediocre REALITY label-ECE and {@code eceVsRetro} is SELF-REFERENTIAL (calibrated TO the physics
 * retrodiction, decision [163]); {@code calibrationNote} explains this and must be surfaced.
 */
public record ModelAccuracyScorecard(
    String modelName,
    String evidenceModelName,
    String stage,
    String baselineModelName,
    String primaryMetric,
    String evaluation,
    String gateStatus,
    String verdictOutcome,
    long sampleSize,
    @Schema(nullable = true) Double brier,
    @Schema(nullable = true) Double ece,
    @Schema(nullable = true) Double logLoss,
    @Schema(nullable = true) Double eceVsRetro,
    @Schema(nullable = true) Double vsBaselineMargin,
    @Schema(nullable = true) Double brierCvMean,
    @Schema(nullable = true) Double brierCvStd,
    @Schema(nullable = true) Double eceCvMean,
    @Schema(nullable = true) Double eceCvStd,
    @Schema(nullable = true) String calibrationNote,
    String generatedAt,
    String gitCommit) {}
