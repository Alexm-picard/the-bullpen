package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javax.sql.DataSource;
import net.thebullpen.baseball.config.ClickHouseProperties;
import net.thebullpen.baseball.drift.DriftInjectionService.DriftInjectionException;
import net.thebullpen.baseball.drift.DriftInjectionService.InjectionResult;
import net.thebullpen.baseball.drift.algorithms.Psi;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.PredictionLogWriter;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Docker-free proof of the induced-drift injector's core contract (E-2, [175]): the synthetic
 * observed sample it writes, fed through the SAME {@link Psi#computeContinuous} the real
 * PsiFeatureJob runs, clears the 0.25 NOTICE threshold on the shifted feature while the un-shifted
 * features stay quiet - the specific, attributable drill signal - and the [175] hygiene + fail-fast
 * guards all bite. The ClickHouse round-trip + cleanup DELETE are proven separately in the
 * docker-gated {@code DriftInjectionServiceIT}.
 */
class DriftInjectionServiceTest {

  private static final String MODEL = "battedball_outcome";
  private static final long CHAMP_ID = 8L;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * metadata.json baseline: launchSpeedMph ~ N(89,15), launchAngleDeg ~ N(12,25), stand
   * {R:700,L:300}.
   */
  private Path writeBaselineMetadata(Path dir) throws Exception {
    Random r = new Random(1);
    StringBuilder speed = new StringBuilder();
    StringBuilder angle = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      if (i > 0) {
        speed.append(',');
        angle.append(',');
      }
      speed.append(String.format("%.3f", 89.0 + r.nextGaussian() * 15.0));
      angle.append(String.format("%.3f", 12.0 + r.nextGaussian() * 25.0));
    }
    String json =
        "{\"feature_distributions\":{"
            + "\"launchSpeedMph\":{\"kind\":\"continuous\",\"sample\":["
            + speed
            + "]},"
            + "\"launchAngleDeg\":{\"kind\":\"continuous\",\"sample\":["
            + angle
            + "]},"
            + "\"stand\":{\"kind\":\"categorical\",\"counts\":{\"R\":700,\"L\":300}}"
            + "}}";
    Path p = dir.resolve("metadata.json");
    Files.writeString(p, json);
    return p;
  }

  private ModelVersion champion(Path metadataPath) {
    Instant now = Instant.now();
    return new ModelVersion(
        CHAMP_ID,
        MODEL,
        "v2",
        "/tmp/model.onnx",
        metadataPath.toString(),
        "train-hash",
        "[2015,2024]",
        "schema-hash-v2",
        "{}",
        now,
        now,
        Stage.CHAMPION,
        "drill-test",
        null,
        now,
        now);
  }

  /** A syntactically valid CH properties record; nothing in the unit tests ever connects to it. */
  private static ClickHouseProperties chProps() {
    return new ClickHouseProperties(
        "jdbc:ch:http://localhost:8123/default",
        "default",
        "",
        30_000,
        10_000,
        new ClickHouseProperties.Pool(8, 3_000, 2_000, 1_800_000));
  }

  private DriftInjectionService service(
      RegistryRepository registry, PredictionLogWriter writer, String tag) {
    // Cleanup admin creds deliberately blank: the unit tests exercise induce() + the cleanup
    // refusal guard; the real admin-connection mutation path is covered by the docker IT.
    return new DriftInjectionService(
        registry,
        new TrainingDistributionLoader(),
        writer,
        mock(DataSource.class),
        chProps(),
        tag,
        "",
        "");
  }

  @Test
  void injected_sample_moves_launch_speed_psi_past_the_notice_threshold(@TempDir Path dir)
      throws Exception {
    Path metadata = writeBaselineMetadata(dir);
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(metadata)));
    PredictionLogWriter writer = mock(PredictionLogWriter.class);

    DriftInjectionService svc = service(registry, writer, "induced-drill-2026-07");
    InjectionResult result = svc.induce(MODEL, 4000, 1.0, 20, "launchSpeedMph");

    assertThat(result.rowsWritten()).isEqualTo(4000);
    assertThat(result.shiftFeature()).isEqualTo("launchSpeedMph");
    // Self-calibrated to the baseline (~mean 89, std 15) it read from metadata.json.
    assertThat(result.baselineMean()).isBetween(87.0, 91.0);
    assertThat(result.baselineStd()).isBetween(13.0, 17.0);
    assertThat(result.shiftedMean())
        .isBetween(result.baselineMean() + 13.0, result.baselineMean() + 17.0);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<PredictionLogEvent>> cap = ArgumentCaptor.forClass(List.class);
    verify(writer).writeBatch(cap.capture());
    List<PredictionLogEvent> batch = cap.getValue();
    assertThat(batch).hasSize(4000);

    // The [175] contract: every row is drill:-prefixed + carries the champion identity.
    assertThat(batch)
        .allSatisfy(
            ev -> {
              assertThat(ev.correlationId())
                  .startsWith(DriftInjectionService.DRILL_CORRELATION_PREFIX);
              assertThat(ev.modelName()).isEqualTo(MODEL);
              assertThat(ev.modelVersionId()).isEqualTo(CHAMP_ID);
              assertThat(ev.role()).isEqualTo(PredictionLogEvent.Role.CHAMPION);
            });

    // THE load-bearing assertion: the observed launchSpeedMph the injector wrote, through the REAL
    // Psi math against the baseline, clears the 0.25 NOTICE threshold (this is exactly what the 2
    // AM
    // PsiFeatureJob will compute on these rows).
    double[] baseline = extractBaseline(metadata, "launchSpeedMph");
    double[] observedSpeed = extractFeature(batch, "launchSpeedMph");
    double speedPsi = Psi.computeContinuous(baseline, observedSpeed, Psi.DEFAULT_BINS);
    assertThat(speedPsi).as("shifted feature must clear the NOTICE threshold").isGreaterThan(0.25);

    // Specificity: the UN-shifted continuous feature stays quiet (drawn from its own baseline).
    double[] angleBaseline = extractBaseline(metadata, "launchAngleDeg");
    double[] observedAngle = extractFeature(batch, "launchAngleDeg");
    double anglePsi = Psi.computeContinuous(angleBaseline, observedAngle, Psi.DEFAULT_BINS);
    assertThat(anglePsi).as("un-shifted feature must stay below NOTICE").isLessThan(0.1);

    // Every baseline feature key is present in the features JSON (so the fetcher extracts them).
    JsonNode firstFeatures = MAPPER.readTree(batch.get(0).features());
    assertThat(firstFeatures.has("launchSpeedMph")).isTrue();
    assertThat(firstFeatures.has("launchAngleDeg")).isTrue();
    assertThat(firstFeatures.has("stand")).isTrue();
    assertThat(firstFeatures.get("stand").isTextual()).isTrue(); // string token round-trips
  }

  @Test
  void refuses_when_the_drift_tag_is_not_armed(@TempDir Path dir) throws Exception {
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(writeBaselineMetadata(dir))));
    DriftInjectionService svc = service(registry, mock(PredictionLogWriter.class), "");

    assertThatThrownBy(() -> svc.induce(MODEL, 100, 1.0, 20, "launchSpeedMph"))
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("BULLPEN_DRIFT_TAG");
  }

  @Test
  void refuses_when_shift_sigmas_is_out_of_range(@TempDir Path dir) throws Exception {
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(writeBaselineMetadata(dir))));
    DriftInjectionService svc = service(registry, mock(PredictionLogWriter.class), "tag");

    // Direct service call bypasses the DTO's @DecimalMin/@DecimalMax bean validation; the service
    // guards it symmetrically so a 0 (no-op) shift can never slip through.
    assertThatThrownBy(() -> svc.induce(MODEL, 100, 0.0, 20, "launchSpeedMph"))
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("shiftSigmas must be in [0.1, 10.0]");
  }

  @Test
  void refuses_when_no_champion_registered() {
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of());
    DriftInjectionService svc = service(registry, mock(PredictionLogWriter.class), "tag");

    assertThatThrownBy(() -> svc.induce(MODEL, 100, 1.0, 20, "launchSpeedMph"))
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("no active CHAMPION");
  }

  @Test
  void refuses_when_champion_has_no_baseline(@TempDir Path dir) throws Exception {
    Path empty = dir.resolve("metadata.json");
    Files.writeString(empty, "{\"model_name\":\"battedball_outcome\"}"); // no feature_distributions
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(empty)));
    DriftInjectionService svc = service(registry, mock(PredictionLogWriter.class), "tag");

    assertThatThrownBy(() -> svc.induce(MODEL, 100, 1.0, 20, "launchSpeedMph"))
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("feature_distributions");
  }

  @Test
  void refuses_when_shift_feature_is_not_a_continuous_baseline(@TempDir Path dir) throws Exception {
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(writeBaselineMetadata(dir))));
    DriftInjectionService svc = service(registry, mock(PredictionLogWriter.class), "tag");

    assertThatThrownBy(
            () -> svc.induce(MODEL, 100, 1.0, 20, "stand")) // categorical, not continuous
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("not a continuous baseline feature");
  }

  @Test
  void refuses_cleanup_when_the_admin_credentials_are_not_armed() {
    // GAP 1 (E-2 postmortem, 2026-07-16): the app's least-priv CH user has no ALTER DELETE, so
    // cleanup needs the admin identity over a separate connection. With the admin creds unarmed
    // the endpoint must refuse loudly (400 naming the env vars) instead of 500ing on Code 497.
    DriftInjectionService svc =
        service(mock(RegistryRepository.class), mock(PredictionLogWriter.class), "tag");

    assertThatThrownBy(svc::cleanup)
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("BULLPEN_DRIFT_CLEANUP_ADMIN_USER");
  }

  @Test
  void surfaces_the_write_failure_message(@TempDir Path dir) throws Exception {
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champion(writeBaselineMetadata(dir))));
    PredictionLogWriter writer = mock(PredictionLogWriter.class);
    doThrow(new SQLException("clickhouse exploded")).when(writer).writeBatch(anyList());
    DriftInjectionService svc = service(registry, writer, "tag");

    assertThatThrownBy(() -> svc.induce(MODEL, 100, 1.0, 20, "launchSpeedMph"))
        .isInstanceOf(DriftInjectionException.class)
        .hasMessageContaining("clickhouse exploded");
  }

  // --- helpers ---

  private static double[] extractBaseline(Path metadata, String feature) throws Exception {
    JsonNode sample =
        MAPPER
            .readTree(metadata.toFile())
            .path("feature_distributions")
            .path(feature)
            .path("sample");
    double[] out = new double[sample.size()];
    for (int i = 0; i < sample.size(); i++) {
      out[i] = sample.get(i).asDouble();
    }
    return out;
  }

  private static double[] extractFeature(List<PredictionLogEvent> batch, String feature)
      throws Exception {
    List<Double> vals = new ArrayList<>(batch.size());
    for (PredictionLogEvent ev : batch) {
      vals.add(MAPPER.readTree(ev.features()).get(feature).asDouble());
    }
    double[] out = new double[vals.size()];
    for (int i = 0; i < vals.size(); i++) {
      out[i] = vals.get(i);
    }
    return out;
  }
}
