package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.alerting.AlertHistoryRepository;
import net.thebullpen.baseball.drift.alerting.DriftAlertEvaluator;
import net.thebullpen.baseball.drift.jobs.PsiFeatureJob;
import net.thebullpen.baseball.registry.DiscordNotifier;
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
 * Real-ClickHouse round-trip for {@link RealFeatureDistributionFetcher}, plus the end-to-end proof
 * that the per-feature drift lane is alive: {@code prediction_log} traffic -> this fetcher -> a
 * real {@link PsiFeatureJob} writing PSI_FEATURE rows into a real {@code drift_metrics} -> {@link
 * DriftAlertEvaluator#runOnce()} reading those rows and firing the feature-drift NOTICE. With the
 * stub, every link after the first was dead (empty observed sample -> zero rows -> nothing to
 * evaluate). Docker-gated exactly like {@link RealPredictionDistributionFetcherIT}.
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
class RealFeatureDistributionFetcherIT {

  private static final ZoneId ET = ZoneId.of("America/New_York");

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
                "bullpen-featdist-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private FeatureDistributionFetcher fetcher;
  @Autowired private DriftMetricsRepository driftRepo;
  @Autowired private TrainingDistributionLoader trainingLoader;
  @Autowired private AlertHistoryRepository historyRepo;
  @Autowired private JdbcTemplate sqlite;

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
    sqlite.update("DELETE FROM alert_history");
  }

  @Test
  void the_real_impl_supersedes_the_stub_when_clickhouse_is_enabled() {
    assertThat(fetcher).isInstanceOf(RealFeatureDistributionFetcher.class);
  }

  @Test
  void continuous_fetch_returns_numeric_values_only_for_the_requested_version_and_window() {
    Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
    insert("battedball_outcome", 7L, oneHourAgo, "{\"launch_speed_mph\":95.5,\"park_id\":\"BOS\"}");
    insert(
        "battedball_outcome", 7L, oneHourAgo, "{\"launch_speed_mph\":101.0,\"park_id\":\"NYY\"}");
    // Missing key, JSON null, and string-typed value must NOT surface as fake 0.0 samples.
    insert("battedball_outcome", 7L, oneHourAgo, "{\"park_id\":\"BOS\"}");
    insert("battedball_outcome", 7L, oneHourAgo, "{\"launch_speed_mph\":null}");
    insert("battedball_outcome", 7L, oneHourAgo, "{\"launch_speed_mph\":\"fast\"}");
    // Other version and outside-window rows are excluded.
    insert("battedball_outcome", 99L, oneHourAgo, "{\"launch_speed_mph\":88.0}");
    insert(
        "battedball_outcome",
        7L,
        Instant.now().minus(3, ChronoUnit.DAYS),
        "{\"launch_speed_mph\":70.0}");

    List<Double> sample =
        fetcher.fetchContinuous(
            "battedball_outcome",
            7L,
            "launch_speed_mph",
            Instant.now().minus(24, ChronoUnit.HOURS),
            Instant.now());

    assertThat(sample).containsExactlyInAnyOrder(95.5, 101.0);
  }

  @Test
  void categorical_fetch_counts_string_and_int_coded_categories() {
    Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
    insert("battedball_outcome", 7L, oneHourAgo, "{\"park_id\":\"BOS\",\"stand_int\":1}");
    insert("battedball_outcome", 7L, oneHourAgo, "{\"park_id\":\"BOS\",\"stand_int\":2}");
    insert("battedball_outcome", 7L, oneHourAgo, "{\"park_id\":\"NYY\",\"stand_int\":1}");
    insert("battedball_outcome", 7L, oneHourAgo, "{\"park_id\":null}");
    insert("battedball_outcome", 99L, oneHourAgo, "{\"park_id\":\"COL\"}");

    Instant start = Instant.now().minus(24, ChronoUnit.HOURS);
    Instant end = Instant.now();

    Map<String, Integer> parks =
        fetcher.fetchCategorical("battedball_outcome", 7L, "park_id", start, end);
    Map<String, Integer> stands =
        fetcher.fetchCategorical("battedball_outcome", 7L, "stand_int", start, end);

    assertThat(parks).containsExactlyInAnyOrderEntriesOf(Map.of("BOS", 2, "NYY", 1));
    // Int-coded categoricals key by the bare numeric token, matching training's string keys.
    assertThat(stands).containsExactlyInAnyOrderEntriesOf(Map.of("1", 2, "2", 1));
  }

  @Test
  void empty_for_a_version_with_no_logged_features() {
    Instant start = Instant.now().minus(24, ChronoUnit.HOURS);
    assertThat(fetcher.fetchContinuous("battedball_outcome", 7L, "x", start, Instant.now()))
        .isEmpty();
    assertThat(fetcher.fetchCategorical("battedball_outcome", 7L, "x", start, Instant.now()))
        .isEmpty();
  }

  @Test
  void end_to_end_traffic_to_psi_rows_to_feature_drift_notice() throws Exception {
    // Training-time reference: launch speeds centered ~90, parks 60/40 BOS/NYY.
    StringBuilder refSample = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      if (i > 0) {
        refSample.append(',');
      }
      refSample.append(88.0 + (i % 5));
    }
    Path metadata = tempDir.resolve("metadata.json");
    Files.writeString(
        metadata,
        "{\"feature_distributions\":{"
            + "\"launch_speed_mph\":{\"kind\":\"continuous\",\"sample\":["
            + refSample
            + "]},"
            + "\"park_id\":{\"kind\":\"categorical\",\"counts\":{\"BOS\":60,\"NYY\":40}}}}");
    ModelVersion champ = champion("battedball_outcome", 42L, metadata);
    RegistryRepository registryRepo = mock(RegistryRepository.class);
    when(registryRepo.findActiveServingVersions()).thenReturn(List.of(champ));
    when(registryRepo.findActiveChampions()).thenReturn(List.of(champ));

    // The REAL job wired with the REAL fetcher + REAL drift repo (ClickHouse) + REAL loader.
    PsiFeatureJob job =
        new PsiFeatureJob(
            registryRepo,
            trainingLoader,
            fetcher,
            driftRepo,
            mock(JobLockRepository.class),
            new DriftHealthMetrics(new SimpleMeterRegistry()));

    // Seven days of drifted traffic (launch speeds ~105 vs the ~90 reference), one job run per
    // day. Noon-ET anchor so the per-day collapse cannot straddle an ET midnight (DEF-M3 idiom).
    Instant anchor = ZonedDateTime.now(ET).with(LocalTime.NOON).toInstant();
    for (int day = 0; day < 7; day++) {
      Instant computedAt = anchor.minus(day, ChronoUnit.DAYS);
      for (int i = 0; i < 8; i++) {
        insert(
            "battedball_outcome",
            42L,
            computedAt.minus(1, ChronoUnit.HOURS),
            "{\"launch_speed_mph\":" + (104.0 + i * 0.5) + ",\"park_id\":\"NYY\"}");
      }
      int written = job.runOnce(computedAt);
      assertThat(written).as("PSI rows written for day -" + day).isGreaterThan(0);
    }

    List<DriftMetric> psiRows =
        driftRepo.findAllForModel("battedball_outcome").stream()
            .filter(m -> m.metricType() == MetricType.PSI_FEATURE)
            .toList();
    assertThat(psiRows).as("job wrote PSI_FEATURE rows into drift_metrics").isNotEmpty();
    assertThat(psiRows.stream().filter(m -> m.featureOrSegment().equals("launch_speed_mph")))
        .hasSizeGreaterThanOrEqualTo(7);

    // The evaluator's real read path over the real drift_metrics: sustained 7-day feature PSI
    // fires the NOTICE and records alert history.
    DiscordNotifier discord = mock(DiscordNotifier.class);
    DriftAlertEvaluator evaluator =
        new DriftAlertEvaluator(
            registryRepo,
            driftRepo,
            historyRepo,
            discord,
            mock(JobLockRepository.class),
            0.10,
            0.25,
            7); // feature-PSI notice sustain window: prod default (7 days)
    int fired = evaluator.runOnce();

    assertThat(fired).as("feature-drift NOTICE fired from job-written rows").isGreaterThan(0);
    verify(discord)
        .send(
            eq(DiscordNotifier.Severity.NOTICE),
            eq("NOTICE: battedball_outcome feature drift on launch_speed_mph"),
            any());
    assertThat(historyRepo.countFor("drift/battedball_outcome/psi_feature/launch_speed_mph"))
        .isEqualTo(1L);
  }

  // --- helpers ----------------------------------------------------------

  private void insert(String model, long versionId, Instant requestAt, String featuresJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, features, prediction)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        Timestamp.from(requestAt),
        model,
        "v1",
        versionId,
        featuresJson,
        "{\"prob_hr\":0.1}");
  }

  private static ModelVersion champion(String name, long id, Path metadataPath) {
    return new ModelVersion(
        id,
        name,
        "v1",
        "/tmp/" + name + "/v1/model.onnx",
        metadataPath.toString(),
        "train-hash",
        "[2024,2024]",
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
