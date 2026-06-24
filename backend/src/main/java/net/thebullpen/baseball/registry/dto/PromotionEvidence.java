package net.thebullpen.baseball.registry.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The committed promotion-evidence JSON (one per model x calibration variant) under {@code
 * training/data/eval/promotion/ *_experiment_results_full*.json}, bundled into the JAR as a
 * classpath resource and read by {@link
 * net.thebullpen.baseball.registry.AccuracyEvidenceRepository}.
 *
 * <p>These carry the REAL rolling-origin-CV / full-box gate numbers - the only source that is both
 * real AND present on the Mac (so the public scorecard renders live in dev). {@code
 * JsonIgnoreProperties(ignoreUnknown = true)} so the many gate fields we do not surface
 * (guardrails, supplementary_checks, folds, ...) never break deserialization, and future fields are
 * tolerated.
 *
 * <p>Only the fields the scorecard surfaces are mapped. {@code calibrationNote} is load-bearing for
 * the batted-ball row (its {@code ece} is the mediocre REALITY label-ECE; {@code eceVsRetro} is
 * SELF-REFERENTIAL to the physics retrodiction - decision [163]).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromotionEvidence(
    @JsonProperty("model_name") String modelName,
    @JsonProperty("champion_model_name") String championModelName,
    @JsonProperty("primary_metric") String primaryMetric,
    @JsonProperty("sample_size_observed") Long sampleSizeObserved,
    String status,
    Verdict verdict,
    @JsonProperty("champion_full_metrics") Metrics championFullMetrics,
    @JsonProperty("challenger_full_metrics") Metrics challengerFullMetrics,
    @JsonProperty("calibration_note") String calibrationNote,
    @JsonProperty("rolling_origin_cv") RollingOriginCv rollingOriginCv,
    Provenance provenance) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Verdict(
      String outcome,
      Boolean passed,
      @JsonProperty("primary_margin_observed") Double primaryMarginObserved) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Metrics(
      Double brier,
      @JsonProperty("log_loss") Double logLoss,
      Double ece,
      @JsonProperty("ece_vs_retro") Double eceVsRetro) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record RollingOriginCv(@JsonProperty("challenger_summary") Summary challengerSummary) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Summary(
        @JsonProperty("multiclass_brier") Stat multiclassBrier,
        @JsonProperty("multiclass_log_loss") Stat multiclassLogLoss,
        @JsonProperty("expected_calibration_error") Stat expectedCalibrationError) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stat(Double mean, Double std) {}
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provenance(
      @JsonProperty("generated_at") String generatedAt,
      @JsonProperty("git_commit") String gitCommit,
      @JsonProperty("mlp_calibration") String mlpCalibration) {}
}
