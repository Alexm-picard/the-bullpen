package net.thebullpen.baseball.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated binding for the {@code bullpen.inference.*} namespace (Wave E / M-task 26, slice
 * 4 - the serving-path cluster). Replaces the seven {@code @Value} injections scattered across
 * {@code PitchInferenceService}, {@code PitchPredictionService}, {@code ToyBattedBallInference},
 * {@code InferenceRouter}, and {@code AsyncPredictionLogger}.
 *
 * <p><b>Two deliberate non-migrations in this namespace:</b>
 *
 * <ul>
 *   <li>{@code PitchInferenceService}'s {@code @ConditionalOnExpression} bean-existence gate keeps
 *       its RAW {@code ${bullpen.inference.pitch.artifacts-dir:...}} SpEL - conditions evaluate
 *       before any properties bean exists. Its inline default MUST stay identical to {@link
 *       Pitch#artifactsDir()}'s; the coherence is pinned by a test
 *       (InferencePropertiesTest.spelConditionDefaultMatchesTheRecordDefault).
 *   <li>The legacy SHARED key {@code bullpen.inference.contract-path} was read by TWO classes with
 *       DIFFERENT inline defaults (pre head: {@code feature_pipeline.json}; toy: {@code
 *       feature_pipeline_toy.json}). A record field can carry only one default, so {@link
 *       #contractPath()} binds the key WITHOUT a default (null when unset) and each consumer goes
 *       through {@link #pitchContractPath()} / {@link #toyContractPath()}, which preserve today's
 *       exact semantics: unset means each class uses its own historical default; set means BOTH
 *       read the same value (the legacy behavior, wart and all - prod may set it via env, so the
 *       key is kept rather than renamed; a deliberate rename is future work, not this refactor).
 * </ul>
 */
@ConfigurationProperties("bullpen.inference")
@Validated
public record InferenceProperties(
    String contractPath,
    @DefaultValue("500") @Positive long shadowTimeoutMs,
    @DefaultValue @Valid Pitch pitch,
    @DefaultValue @Valid PitchPost pitchPost,
    @DefaultValue @Valid Toy toy,
    @DefaultValue @Valid Log log) {

  /** The pre head's historical contract default (the shared key's fallback for the pre head). */
  public static final String PITCH_CONTRACT_DEFAULT = "../contracts/feature_pipeline.json";

  /** The toy head's historical contract default (the shared key's fallback for the toy). */
  public static final String TOY_CONTRACT_DEFAULT = "../contracts/feature_pipeline_toy.json";

  /**
   * The default pre-head artifacts dir. Duplicated INTENTIONALLY in {@code PitchInferenceService}'s
   * {@code @ConditionalOnExpression} (which cannot read this record); a test pins the two equal.
   */
  public static final String PITCH_ARTIFACTS_DEFAULT = "../training/artifacts/pitch_outcome_pre/v1";

  /** Pre-head serving knobs ({@code bullpen.inference.pitch.*}). */
  public record Pitch(
      @DefaultValue(PITCH_ARTIFACTS_DEFAULT) @NotBlank String artifactsDir,
      @DefaultValue("false") boolean devDirectServing) {}

  /** Post-head loading knobs ({@code bullpen.inference.pitch-post.*}). */
  public record PitchPost(
      @DefaultValue("../training/artifacts/pitch_outcome_post/v1") @NotBlank String artifactsDir,
      @DefaultValue("../contracts/feature_pipeline_post.json") @NotBlank String contractPath) {}

  /** Toy batted-ball knobs ({@code bullpen.inference.toy.*}). */
  public record Toy(@DefaultValue("../training/artifacts/_toy/v0") @NotBlank String artifactsDir) {}

  /** Async prediction-log queue knobs ({@code bullpen.inference.log.*}). */
  public record Log(@DefaultValue("20000") @Positive int queueCapacity) {}

  /** The pre-head pipeline contract: the shared legacy key when set, else the pre default. */
  public String pitchContractPath() {
    return contractPath != null && !contractPath.isBlank() ? contractPath : PITCH_CONTRACT_DEFAULT;
  }

  /** The toy pipeline contract: the shared legacy key when set, else the toy default. */
  public String toyContractPath() {
    return contractPath != null && !contractPath.isBlank() ? contractPath : TOY_CONTRACT_DEFAULT;
  }
}
