package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Insert + windowed-read smoke test for {@link DriftMetricsRepository} against a real ClickHouse
 * via Testcontainers. Same {@code -Dbullpen.it.docker=true} gate as {@code SnapshotStorageIT} —
 * Docker Desktop on macOS 29.x returns malformed {@code /info} responses to Testcontainers; CI runs
 * with the flag set.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + " — set -Dbullpen.it.docker=true to force-run in CI.")
class DriftMetricsRepositoryIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("bullpen.clickhouse.enabled", () -> "true");
    registry.add("bullpen.clickhouse.url", CH::getJdbcUrl);
    registry.add("bullpen.clickhouse.user", CH::getUsername);
    registry.add("bullpen.clickhouse.password", CH::getPassword);
    // Reuse the registry-it SQLite isolation so the SQLite-backed beans bring up cleanly.
    String sqliteUrl =
        "jdbc:sqlite:"
            + java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"),
                "bullpen-drift-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private DriftMetricsRepository repo;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    // Migrations run via ClickHouseMigrationRunner on boot — table is present.
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS drift_metrics");
    }
  }

  @Test
  void insertBatch_persists_rows_and_findLatest_returns_newest() throws Exception {
    Instant now = Instant.now();
    List<DriftMetric> batch = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      Instant computedAt = now.minus(50 - i, ChronoUnit.MINUTES);
      batch.add(
          new DriftMetric(
              computedAt,
              "_toy_batted_ball",
              1L,
              MetricType.PSI_FEATURE,
              "launch_speed_mph",
              0.05 + (i / 1000.0),
              5000L,
              computedAt.minus(7, ChronoUnit.DAYS),
              computedAt));
    }
    repo.insertBatch(batch);

    var latest = repo.findLatest("_toy_batted_ball", MetricType.PSI_FEATURE, "launch_speed_mph");
    assertThat(latest).isPresent();
    assertThat(latest.orElseThrow().metricValue()).isCloseTo(0.05 + (49 / 1000.0), within(1e-9));
  }

  @Test
  void findRecent_filters_by_window() throws Exception {
    Instant now = Instant.now();
    List<DriftMetric> batch = new ArrayList<>();
    // 5 rows now, 5 rows 60 days ago.
    for (int i = 0; i < 5; i++) {
      batch.add(metric(now.minus(i, ChronoUnit.MINUTES)));
    }
    for (int i = 0; i < 5; i++) {
      batch.add(metric(now.minus(60, ChronoUnit.DAYS).minus(i, ChronoUnit.MINUTES)));
    }
    repo.insertBatch(batch);

    var recent =
        repo.findRecent(
            "_toy_batted_ball", MetricType.PSI_FEATURE, "launch_speed_mph", Duration.ofDays(30));
    assertThat(recent).hasSize(5);
  }

  private static DriftMetric metric(Instant computedAt) {
    return new DriftMetric(
        computedAt,
        "_toy_batted_ball",
        1L,
        MetricType.PSI_FEATURE,
        "launch_speed_mph",
        0.05,
        5000L,
        computedAt.minus(7, ChronoUnit.DAYS),
        computedAt);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
