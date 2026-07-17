package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@code role} dimension on the inference error counter (F3 #250, closing decision
 * [176]): a champion erroring (page-worthy) must be distinguishable from a shadow / simulator /
 * unrouted error so a per-role error RATE is computable. Pure {@link SimpleMeterRegistry} unit - no
 * Mockito.
 */
class InferenceMetricsTest {

  private static final String ERROR_METRIC = "thebullpen_inference_prediction_errors_total";

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final InferenceMetrics metrics = new InferenceMetrics(registry);

  @Test
  void errorCounterIsTaggedByModelRoleAndErrorClass() {
    metrics.incrementError("pitch_outcome_post", "champion", "OrtException");
    assertThat(
            registry
                .get(ERROR_METRIC)
                .tag("model_name", "pitch_outcome_post")
                .tag("role", "champion")
                .tag("error_class", "OrtException")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void errorCounterSeparatesRolesUnderTheSameModelAndErrorClass() {
    // Same model + same error_class but different serving roles must NOT collapse into one series -
    // otherwise the per-role error rate (champion errors page, shadow errors do not) is impossible.
    // This pins the (model|role|error_class) cache key, not merely the presence of the tag.
    metrics.incrementError("batted_ball_mlp", "champion", "RuntimeException");
    metrics.incrementError("batted_ball_mlp", "champion", "RuntimeException");
    metrics.incrementError("batted_ball_mlp", "shadow", "RuntimeException");

    assertThat(
            registry
                .get(ERROR_METRIC)
                .tag("model_name", "batted_ball_mlp")
                .tag("role", "champion")
                .tag("error_class", "RuntimeException")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get(ERROR_METRIC)
                .tag("model_name", "batted_ball_mlp")
                .tag("role", "shadow")
                .tag("error_class", "RuntimeException")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void errorCounterAcceptsTheUnknownRoleForPreRoutingFailures() {
    // A non-503 failure before routing resolves a serving role is attributed to role="unknown"
    // (the orchestrator / service holder default) rather than mislabeled as a served role.
    metrics.incrementError("pitch_outcome_pre", "unknown", "IllegalStateException");
    assertThat(registry.get(ERROR_METRIC).tag("role", "unknown").counter().count()).isEqualTo(1.0);
  }
}
