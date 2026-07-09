package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.thebullpen.baseball.inference.PitchInferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Binding + validation coverage for the typed {@code bullpen.inference.*} config (Wave E / M-task
 * 26, slice 4 - the serving-path cluster). Beyond the standard defaults/overrides/validation
 * checks, this suite pins the two hazards specific to this namespace:
 *
 * <ul>
 *   <li><b>The legacy shared {@code contract-path} key.</b> Two classes historically read it with
 *       DIFFERENT inline defaults. {@code pitchContractPath()}/{@code toyContractPath()} must
 *       preserve those exact semantics: unset means each consumer gets its own historical default;
 *       set means both get the same value.
 *   <li><b>SpEL-default coherence.</b> {@code PitchInferenceService}'s bean-existence gate keeps a
 *       raw {@code ${bullpen.inference.pitch.artifacts-dir:...}} SpEL whose inline default is
 *       duplicated with the record's. If the two ever drift, the bean could materialize against one
 *       directory and load from another - this test fails the build instead.
 * </ul>
 */
class InferencePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

  @Test
  void bindsTheFormerAtValueDefaultsWhenUnset() {
    runner.run(
        ctx -> {
          InferenceProperties p = ctx.getBean(InferenceProperties.class);
          assertThat(p.shadowTimeoutMs()).isEqualTo(500L);
          assertThat(p.pitch().artifactsDir())
              .isEqualTo("../training/artifacts/pitch_outcome_pre/v1");
          assertThat(p.pitch().devDirectServing()).isFalse();
          assertThat(p.pitchPost().artifactsDir())
              .isEqualTo("../training/artifacts/pitch_outcome_post/v1");
          assertThat(p.pitchPost().contractPath())
              .isEqualTo("../contracts/feature_pipeline_post.json");
          assertThat(p.toy().artifactsDir()).isEqualTo("../training/artifacts/_toy/v0");
          assertThat(p.log().queueCapacity()).isEqualTo(20_000);
        });
  }

  @Test
  void sharedContractPathUnset_eachConsumerGetsItsOwnHistoricalDefault() {
    runner.run(
        ctx -> {
          InferenceProperties p = ctx.getBean(InferenceProperties.class);
          assertThat(p.contractPath()).isNull();
          assertThat(p.pitchContractPath()).isEqualTo("../contracts/feature_pipeline.json");
          assertThat(p.toyContractPath()).isEqualTo("../contracts/feature_pipeline_toy.json");
        });
  }

  @Test
  void sharedContractPathSet_bothConsumersReadTheSameValue() {
    // The legacy (pre-record) behavior, preserved deliberately: one key, both readers.
    runner
        .withPropertyValues("bullpen.inference.contract-path=/opt/bullpen/contracts/pipeline.json")
        .run(
            ctx -> {
              InferenceProperties p = ctx.getBean(InferenceProperties.class);
              assertThat(p.pitchContractPath()).isEqualTo("/opt/bullpen/contracts/pipeline.json");
              assertThat(p.toyContractPath()).isEqualTo("/opt/bullpen/contracts/pipeline.json");
            });
  }

  @Test
  void bindsOverridesThroughRelaxedKeys() {
    runner
        .withPropertyValues(
            "bullpen.inference.shadow-timeout-ms=750",
            "bullpen.inference.pitch.artifacts-dir=/var/lib/bullpen/models/pitch_outcome_pre/v1",
            "bullpen.inference.pitch.dev-direct-serving=true",
            "bullpen.inference.pitch-post.artifacts-dir=/var/lib/bullpen/models/pitch_outcome_post/v1",
            "bullpen.inference.toy.artifacts-dir=/var/lib/bullpen/models/_toy/v0",
            "bullpen.inference.log.queue-capacity=40000")
        .run(
            ctx -> {
              InferenceProperties p = ctx.getBean(InferenceProperties.class);
              assertThat(p.shadowTimeoutMs()).isEqualTo(750L);
              assertThat(p.pitch().artifactsDir())
                  .isEqualTo("/var/lib/bullpen/models/pitch_outcome_pre/v1");
              assertThat(p.pitch().devDirectServing()).isTrue();
              assertThat(p.pitchPost().artifactsDir())
                  .isEqualTo("/var/lib/bullpen/models/pitch_outcome_post/v1");
              assertThat(p.toy().artifactsDir()).isEqualTo("/var/lib/bullpen/models/_toy/v0");
              assertThat(p.log().queueCapacity()).isEqualTo(40_000);
            });
  }

  @Test
  void rejectsANonPositiveShadowTimeoutAtStartup() {
    runner
        .withPropertyValues("bullpen.inference.shadow-timeout-ms=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void rejectsANonPositiveQueueCapacityAtStartup() {
    runner
        .withPropertyValues("bullpen.inference.log.queue-capacity=0")
        .run(ctx -> assertThat(ctx).hasFailed());
  }

  @Test
  void spelConditionDefaultMatchesTheRecordDefault() {
    // PitchInferenceService's bean-existence gate cannot read this record (conditions evaluate
    // before properties beans exist), so its SpEL duplicates the artifacts-dir default inline.
    // Drift between the two would let the bean materialize against one directory and load from
    // another - pin them equal.
    ConditionalOnExpression gate =
        PitchInferenceService.class.getAnnotation(ConditionalOnExpression.class);
    assertThat(gate).isNotNull();
    assertThat(gate.value())
        .contains(
            "${bullpen.inference.pitch.artifacts-dir:"
                + InferenceProperties.PITCH_ARTIFACTS_DEFAULT
                + "}");
  }

  @Configuration
  @EnableConfigurationProperties(InferenceProperties.class)
  static class TestConfig {}
}
