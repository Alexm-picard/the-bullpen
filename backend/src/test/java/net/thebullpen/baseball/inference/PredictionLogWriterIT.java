package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Real-ClickHouse round-trip for {@link PredictionLogWriter} (F3). The testing-posture rule forbids
 * mocking the ClickHouse boundary - exactly where mock/prod divergence bites - so this exercises
 * {@code writeBatch} against a real ClickHouse Testcontainer with the production migrations applied
 * on context boot (V004 base + V012 {@code model_version_id} + V017 live key). Docker-gated exactly
 * like the sibling CH ITs (Docker Desktop on macOS returns malformed /info responses to
 * Testcontainers; CI sets {@code -Dbullpen.it.docker=true}).
 *
 * <p>Assertions are count-based SQL predicates so ClickHouse itself evaluates the comparisons
 * (Enum8 role, Nullable FK/live-key, Float32 latency) - no reliance on JDBC column type-mapping.
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
class PredictionLogWriterIT {

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
                "bullpen-predlogwriter-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PredictionLogWriter writer;

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
  void writesEveryColumnForBothTheKeyedLivePathAndTheNullKeyRouterPath() throws Exception {
    Instant at = Instant.parse("2026-07-17T18:00:00Z").truncatedTo(ChronoUnit.MILLIS);
    UUID keyedId = UUID.randomUUID();
    UUID routerId = UUID.randomUUID();

    // A live-poller event (full 14-arg ctor): resolved registry FK + the (game_id, at_bat_index,
    // pitch_number) truth-join key populated.
    PredictionLogEvent keyed =
        new PredictionLogEvent(
            keyedId,
            at,
            "pitch_outcome_post",
            "v3",
            42L,
            PredictionLogEvent.Role.CHAMPION,
            "hash-abc",
            "{\"balls\":1}",
            "{\"ball\":0.6}",
            1.5f,
            "corr-1",
            777L,
            3,
            5);
    // An HTTP/router-path event (11-arg ctor): null FK + null live key (a shadow row that matches
    // no
    // pitches_live row in the step-5 LEFT JOIN); null correlation id is coerced to "" by the
    // writer.
    PredictionLogEvent nullKey =
        new PredictionLogEvent(
            routerId,
            at,
            "battedball_outcome",
            "v2",
            null,
            PredictionLogEvent.Role.SHADOW,
            "hash-def",
            "{\"launchSpeedMph\":104.5}",
            "{\"probHr\":0.3}",
            2.5f,
            null);

    writer.writeBatch(List.of(keyed, nullKey));

    // Keyed row: every set column round-trips (comparisons evaluated by ClickHouse). latency 1.5 is
    // exactly representable in Float32 so the equality is safe.
    Long keyedMatch =
        ch.queryForObject(
            "SELECT count() FROM prediction_log WHERE toString(request_id) = ?"
                + " AND model_name = 'pitch_outcome_post' AND model_version = 'v3'"
                + " AND model_version_id = 42 AND role = 'champion' AND feature_hash = 'hash-abc'"
                + " AND features = '{\"balls\":1}' AND prediction = '{\"ball\":0.6}'"
                + " AND latency_ms = 1.5 AND correlation_id = 'corr-1'"
                + " AND game_id = 777 AND at_bat_index = 3 AND pitch_number = 5",
            Long.class,
            keyedId.toString());
    assertThat(keyedMatch).as("keyed live row round-trips every column").isEqualTo(1L);

    // Null-key row: the nullable FK + live key persist as SQL NULL, role=shadow, null corr -> "".
    Long nullMatch =
        ch.queryForObject(
            "SELECT count() FROM prediction_log WHERE toString(request_id) = ?"
                + " AND model_name = 'battedball_outcome' AND role = 'shadow'"
                + " AND model_version_id IS NULL AND game_id IS NULL"
                + " AND at_bat_index IS NULL AND pitch_number IS NULL AND correlation_id = ''",
            Long.class,
            routerId.toString());
    assertThat(nullMatch).as("router-path row persists nulls + empty-string corr").isEqualTo(1L);

    Long total = ch.queryForObject("SELECT count() FROM prediction_log", Long.class);
    assertThat(total).as("exactly the two written rows landed").isEqualTo(2L);
  }

  @Test
  void emptyBatchIsANoOp() throws Exception {
    writer.writeBatch(List.of());
    Long count = ch.queryForObject("SELECT count() FROM prediction_log", Long.class);
    assertThat(count).isZero();
  }
}
