package net.thebullpen.baseball.registry.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * The committed OFFLINE promotion-gate evidence JSON (e.g. a carry-champion non-inferiority
 * ablation, decision [166] / ADR-0012) under {@code training/data/eval/promotion/
 * *_promotion_gate.json}, bundled into the JAR at {@code classpath:offline-gate-evidence/} and read
 * by {@link net.thebullpen.baseball.registry.OfflineGateEvidenceRepository}.
 *
 * <p>Unlike the online experiment lifecycle (start -&gt; evaluate-from-prediction_log -&gt;
 * complete, positive-threshold superiority), this evidence is produced OFFLINE - a rolling-origin
 * ablation, not a live shadow comparison, scored offline because the challenger is not serving. The
 * {@code import-offline} admin path turns it into a terminal {@code passed} experiment_results row
 * (the row {@code RegistryService.assertPromotionCriteriaMet} reads). {@code
 * JsonIgnoreProperties(ignoreUnknown = true)} so the per-park carry table, folds, rationale, etc.
 * never break deserialization.
 *
 * <p>The importer NEVER trusts {@code status} blindly: it re-derives the pass from {@code verdict}
 * + {@code guardrailsViolated} + {@code carryGate} (see {@code OfflineGateImportService}), so a
 * JSON whose declared status disagrees with its own verdict is rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OfflineGateEvidence(
    @JsonProperty("model_name") String modelName,
    @JsonProperty("champion_model_name") String championModelName,
    @JsonProperty("challenger_model_name") String challengerModelName,
    @JsonProperty("primary_metric") String primaryMetric,
    @JsonProperty("primary_threshold") Double primaryThreshold,
    @JsonProperty("sample_size_target") Long sampleSizeTarget,
    @JsonProperty("sample_size_observed") Long sampleSizeObserved,
    @JsonProperty("champion_metric") Double championMetric,
    @JsonProperty("challenger_metric") Double challengerMetric,
    Map<String, Double> guardrails,
    @JsonProperty("guardrails_observed") Map<String, Double> guardrailsObserved,
    @JsonProperty("guardrails_violated") Map<String, Double> guardrailsViolated,
    String status,
    Verdict verdict,
    @JsonProperty("carry_gate") CarryGate carryGate,
    Provenance provenance) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Verdict(Boolean passed, @JsonProperty("sample_size_met") Boolean sampleSizeMet) {}

  /** Optional per-model extra HARD gate (e.g. the carry head's per-park feet plausibility). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CarryGate(Boolean passed) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Provenance(
      @JsonProperty("git_commit") String gitCommit,
      @JsonProperty("generated_at") String generatedAt) {}
}
