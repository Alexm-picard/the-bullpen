package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * Real-ClickHouse round-trip for {@link RealPredictionDistributionFetcher} (WS2-ii). Confirms
 * the @Primary wiring supersedes the stub when ClickHouse is enabled, that the fetcher pivots
 * per-class probabilities for exactly the requested model version in the window, and that non-pitch
 * payloads are skipped. Docker-gated exactly like {@link
 * net.thebullpen.baseball.data.LivePitchesRepositoryIT}.
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
class RealPredictionDistributionFetcherIT {

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
    String sqliteUrl =
        "jdbc:sqlite:"
            + java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"),
                "bullpen-preddist-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PredictionDistributionFetcher fetcher;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS prediction_log");
  }

  @Test
  void the_real_impl_supersedes_the_stub_when_clickhouse_is_enabled() {
    assertThat(fetcher).isInstanceOf(RealPredictionDistributionFetcher.class);
  }

  @Test
  void pivots_per_class_probabilities_for_the_version_and_skips_non_pitch_rows() {
    long v = 7L;
    insert("pitch_outcome_pre", v, pitchJson(0.30, 0.20, 0.10, 0.15, 0.25));
    insert("pitch_outcome_pre", v, pitchJson(0.40, 0.25, 0.05, 0.10, 0.20));
    insert("pitch_outcome_pre", v, "{\"prob_hr\":0.12}"); // no probabilities object -> skipped
    insert("pitch_outcome_pre", 99L, pitchJson(0.9, 0.025, 0.025, 0.025, 0.025)); // other version

    Map<String, List<Double>> out =
        fetcher.fetchPerClassProbabilities(
            "pitch_outcome_pre", v, Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());

    assertThat(out.keySet())
        .containsExactlyInAnyOrder("ball", "called_strike", "swinging_strike", "foul", "in_play");
    // Both v7 rows; the non-pitch row and the v99 row are excluded. Order across rows is not
    // guaranteed by ClickHouse without ORDER BY (and PSI does not care), so assert as a multiset.
    assertThat(out.get("ball")).containsExactlyInAnyOrder(0.30, 0.40);
    assertThat(out.get("in_play")).containsExactlyInAnyOrder(0.25, 0.20);
  }

  @Test
  void empty_for_a_version_with_no_logged_predictions() {
    Map<String, List<Double>> out =
        fetcher.fetchPerClassProbabilities(
            "pitch_outcome_pre", 7L, Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());
    assertThat(out).isEmpty();
  }

  // --- helpers ----------------------------------------------------------

  private void insert(String model, long versionId, String predictionJson) {
    // request_at bound as a Timestamp, mirroring PredictionLogWriter; omitted columns take CH
    // defaults. Stamp it 1h ago so it falls inside the 24h fetch window.
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, prediction)"
            + " VALUES (?, ?, ?, ?, ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        model,
        "v1",
        versionId,
        predictionJson);
  }

  private static String pitchJson(
      double ball, double calledStrike, double swStrike, double foul, double inPlay) {
    return String.format(
        "{\"probabilities\":{\"ball\":%s,\"called_strike\":%s,\"swinging_strike\":%s,"
            + "\"foul\":%s,\"in_play\":%s},\"winner\":\"ball\"}",
        ball, calledStrike, swStrike, foul, inPlay);
  }
}
