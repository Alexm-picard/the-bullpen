package net.thebullpen.baseball.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.api.dto.SimulateRequest;
import net.thebullpen.baseball.api.dto.SimulateResponse;
import net.thebullpen.baseball.inference.InferenceMetrics;
import net.thebullpen.baseball.inference.PitchInferenceService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.Test;

/**
 * Standalone unit tests for {@link SimulateController}'s metric identity + honesty fields. Mocks
 * {@link PitchInferenceService}, so these run WITHOUT the ONNX artifact - unlike the
 * {@code @EnabledIf}-gated {@link SimulateControllerTest}, which is disabled in CI.
 *
 * <p>These pin the actual defect the F1.6 decision fixes: the simulator is an unrouted diagnostic,
 * so its traffic must carry {@code role=simulator} and land on its own latency metric, and a {@code
 * role=champion} fleet query must return ZERO simulate traffic.
 */
class SimulateControllerMetricsTest {

  private final PitchInferenceService inference = mock(PitchInferenceService.class);
  private final RegistryService registryService = mock(RegistryService.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final InferenceMetrics metrics = new InferenceMetrics(meters);
  private final SimulateController controller =
      new SimulateController(inference, metrics, registryService);

  @Test
  void simulate_traffic_is_tagged_simulator_and_never_blends_into_champion_queries()
      throws Exception {
    stubInference();
    when(registryService.findByName("pitch_outcome_pre"))
        .thenReturn(List.of(pitchPreV1(Stage.SHADOW)));

    SimulateResponse resp = controller.analytical(analyticalRequest());

    // Honesty fields (the "served direct on a pinned artifact" sentence the audit wanted).
    assertThat(resp.servingMode()).isEqualTo("unrouted-diagnostic");
    assertThat(resp.registryStage()).isEqualTo("shadow");
    assertThat(resp.modelName()).isEqualTo("pitch_outcome_pre");
    assertThat(resp.modelVersion()).isEqualTo("v1");

    // THE guardrail: the count is tagged role=simulator, and a champion-filtered query is empty.
    assertThat(
            meters
                .get("thebullpen_inference_prediction_total")
                .tag("model_name", "pitch_outcome_pre")
                .tag("role", "simulator")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            meters.find("thebullpen_inference_prediction_total").tag("role", "champion").counter())
        .as("simulate must never emit a champion-role prediction")
        .isNull();

    // Latency lands on the dedicated simulate metric, never the served-prediction histogram.
    assertThat(meters.get("thebullpen_inference_simulate_latency_seconds").timer().count())
        .isEqualTo(1L);
    assertThat(meters.find("thebullpen_inference_prediction_latency_seconds").timer())
        .as("simulate must not record on the served-prediction latency histogram")
        .isNull();
  }

  @Test
  void registry_stage_is_null_when_the_pinned_artifact_has_no_registry_row() throws Exception {
    stubInference();
    when(registryService.findByName("pitch_outcome_pre")).thenReturn(List.of());

    SimulateResponse resp = controller.analytical(analyticalRequest());

    assertThat(resp.registryStage()).isNull();
    assertThat(resp.servingMode()).isEqualTo("unrouted-diagnostic");
  }

  private void stubInference() throws Exception {
    when(inference.classLabels())
        .thenReturn(List.of("ball", "called_strike", "swinging_strike", "foul", "in_play"));
    when(inference.predict(any()))
        .thenReturn(
            Map.of(
                "ball", 0.35,
                "called_strike", 0.15,
                "swinging_strike", 0.12,
                "foul", 0.18,
                "in_play", 0.20));
  }

  private static SimulateRequest analyticalRequest() {
    return new SimulateRequest(
        0, 0, 1, 4, 0, 0, 3, "R", "L", "NYY", 545361L, 605141L, null, null, null, null, null, null,
        null, null, null, null, null, null, null);
  }

  private static ModelVersion pitchPreV1(Stage stage) {
    return new ModelVersion(
        1L,
        "pitch_outcome_pre",
        "v1",
        "/tmp/m.onnx",
        "/tmp/meta.json",
        "trainhash",
        "2015-2025",
        "schemahash",
        "{}",
        Instant.EPOCH,
        Instant.EPOCH,
        stage,
        "test",
        null,
        Instant.EPOCH,
        Instant.EPOCH);
  }
}
