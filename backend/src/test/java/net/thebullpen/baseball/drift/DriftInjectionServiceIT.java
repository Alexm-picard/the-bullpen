package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.config.ClickHouseProperties;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.DriftInjectionService.InjectionResult;
import net.thebullpen.baseball.drift.jobs.PsiFeatureJob;
import net.thebullpen.baseball.inference.PredictionLogWriter;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-ClickHouse end-to-end proof of the E-2 live-path injector ([175]): {@link
 * DriftInjectionService#induce} writes synthetic {@code prediction_log} rows through the REAL
 * {@link PredictionLogWriter}, the REAL {@link RealFeatureDistributionFetcher} reads the shifted
 * feature back out of them, a REAL {@link PsiFeatureJob} run over the injected window writes a
 * PSI_FEATURE row past the 0.25 NOTICE threshold, and {@link DriftInjectionService#cleanup} removes
 * every {@code drill:}-prefixed row. This is the SQL half the docker-free {@code
 * DriftInjectionServiceTest} cannot cover. Docker-gated exactly like {@link
 * RealFeatureDistributionFetcherIT}.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class DriftInjectionServiceIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12")
          .withUsername("default")
          .withPassword("test");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("bullpen.clickhouse.enabled", () -> "true");
    registry.add("bullpen.clickhouse.url", CH::getJdbcUrl);
    registry.add("bullpen.clickhouse.user", CH::getUsername);
    registry.add("bullpen.clickhouse.password", CH::getPassword);
    String sqliteUrl =
        "jdbc:sqlite:"
            + Path.of(
                System.getProperty("java.io.tmpdir"),
                "bullpen-drift-inject-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private FeatureDistributionFetcher fetcher;
  @Autowired private DriftMetricsRepository driftRepo;
  @Autowired private TrainingDistributionLoader trainingLoader;
  @Autowired private PredictionLogWriter predictionLogWriter;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  @TempDir Path tempDir;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS prediction_log");
    ch.execute("TRUNCATE TABLE IF EXISTS drift_metrics");
  }

  @Test
  void inject_then_real_psi_job_fires_then_cleanup_removes_the_rows() throws Exception {
    Path metadata = writeBaselineMetadata();
    ModelVersion champ = champion("battedball_outcome", 42L, metadata);
    RegistryRepository registry = mock(RegistryRepository.class);
    when(registry.findActiveChampions()).thenReturn(List.of(champ));
    when(registry.findActiveServingVersions()).thenReturn(List.of(champ));

    DriftInjectionService injector =
        new DriftInjectionService(
            registry,
            trainingLoader,
            predictionLogWriter,
            clickhouseDs,
            new ClickHouseProperties(
                CH.getJdbcUrl(),
                CH.getUsername(),
                CH.getPassword(),
                30_000,
                10_000,
                new ClickHouseProperties.Pool(8, 3_000, 2_000, 1_800_000)),
            "induced-drill-it",
            // Cleanup admin creds (GAP 1): the mutation runs over a SEPARATE one-shot connection
            // as the CH admin identity - here the container's user, which is admin; on the box it
            // is `default`, never the least-priv app user (no ALTER DELETE by design, [171]).
            CH.getUsername(),
            CH.getPassword());

    // --- inject ---
    InjectionResult result = injector.induce("battedball_outcome", 3000, 1.0, 20, "launchSpeedMph");
    assertThat(result.rowsWritten()).isEqualTo(3000);

    // The rows are in prediction_log, all drill:-prefixed.
    Long total = ch.queryForObject("SELECT count() FROM prediction_log", Long.class);
    Long drill =
        ch.queryForObject(
            "SELECT count() FROM prediction_log WHERE correlation_id LIKE 'drill:%'", Long.class);
    assertThat(total).isEqualTo(3000L);
    assertThat(drill).isEqualTo(3000L);

    // The REAL fetcher reads the shifted launchSpeedMph sample back out of the features JSON.
    List<Double> observed =
        fetcher.fetchContinuous(
            "battedball_outcome",
            42L,
            "launchSpeedMph",
            Instant.now().minus(24, ChronoUnit.HOURS),
            Instant.now());
    assertThat(observed).hasSize(3000);
    double observedMean =
        observed.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    assertThat(observedMean).as("observed mean is ~1σ above the ~89 baseline").isGreaterThan(100.0);

    // The REAL PsiFeatureJob over the injected window writes a PSI_FEATURE row past NOTICE.
    trainingLoader.invalidate(42L); // fresh baseline read
    PsiFeatureJob job =
        new PsiFeatureJob(
            registry,
            trainingLoader,
            fetcher,
            driftRepo,
            mock(JobLockRepository.class),
            new DriftHealthMetrics(new SimpleMeterRegistry()));
    int written = job.runOnce(Instant.now());
    assertThat(written).isGreaterThan(0);

    List<DriftMetric> speedPsi =
        driftRepo.findAllForModel("battedball_outcome").stream()
            .filter(m -> m.metricType() == MetricType.PSI_FEATURE)
            .filter(m -> m.featureOrSegment().equals("launchSpeedMph"))
            .toList();
    assertThat(speedPsi).hasSize(1);
    assertThat(speedPsi.get(0).metricValue())
        .as("injected drift clears the 0.25 NOTICE threshold on launchSpeedMph")
        .isGreaterThan(0.25);

    // --- cleanup ---
    long removed = injector.cleanup();
    assertThat(removed).isEqualTo(3000L);
    // The async mutation settles within seconds on a tiny table; poll until the drill rows are
    // gone.
    awaitDrillRowsGone();
    List<Double> afterCleanup =
        fetcher.fetchContinuous(
            "battedball_outcome",
            42L,
            "launchSpeedMph",
            Instant.now().minus(24, ChronoUnit.HOURS),
            Instant.now());
    assertThat(afterCleanup).as("cleanup removed the synthetic observed rows").isEmpty();
  }

  private void awaitDrillRowsGone() throws InterruptedException {
    for (int i = 0; i < 60; i++) {
      Long remaining =
          ch.queryForObject(
              "SELECT count() FROM prediction_log WHERE correlation_id LIKE 'drill:%'", Long.class);
      if (remaining != null && remaining == 0L) {
        return;
      }
      Thread.sleep(500);
    }
    throw new AssertionError("drill rows not deleted within 30s");
  }

  private Path writeBaselineMetadata() throws Exception {
    Random r = new Random(3);
    StringBuilder speed = new StringBuilder();
    for (int i = 0; i < 2000; i++) {
      if (i > 0) {
        speed.append(',');
      }
      speed.append(String.format("%.3f", 89.0 + r.nextGaussian() * 15.0));
    }
    Path p = tempDir.resolve("metadata.json");
    Files.writeString(
        p,
        "{\"feature_distributions\":{"
            + "\"launchSpeedMph\":{\"kind\":\"continuous\",\"sample\":["
            + speed
            + "]},"
            + "\"stand\":{\"kind\":\"categorical\",\"counts\":{\"R\":700,\"L\":300}}}}");
    return p;
  }

  private static ModelVersion champion(String name, long id, Path metadataPath) {
    return new ModelVersion(
        id,
        name,
        "v2",
        "/tmp/" + name + "/v2/model.onnx",
        metadataPath.toString(),
        "train-hash",
        "[2015,2024]",
        "schema-hash",
        "{}",
        Instant.now(),
        Instant.now(),
        Stage.CHAMPION,
        "test",
        null,
        Instant.now(),
        Instant.now());
  }
}
