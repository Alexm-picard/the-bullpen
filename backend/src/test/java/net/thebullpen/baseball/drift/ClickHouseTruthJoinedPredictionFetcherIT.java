package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
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
 * Real-ClickHouse round-trip for {@link ClickHouseTruthJoinedPredictionFetcher} (3c). Confirms the
 * {@code @Primary} wiring supersedes the stub; that the live-key truth join returns one {@code
 * (probs, truthClass)} row per scorable prediction with exact round-trips; and that every
 * non-scorable case is dropped: unmatched truth (NULL), out-of-vocab truth ({@code hit_by_pitch} -
 * never silently bucketed), HTTP-path NULL-key rows, non-pitch payloads, and the wrong version.
 * Docker-gated like {@link RealPredictionDistributionFetcherIT}.
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
class ClickHouseTruthJoinedPredictionFetcherIT {

  private static final String MODEL = "pitch_outcome_post";
  private static final long V = 2L;

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
                "bullpen-truthjoin-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private TruthJoinedPredictionFetcher fetcher;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS prediction_log");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  @Test
  void the_real_impl_supersedes_the_stub_when_clickhouse_is_enabled() {
    assertThat(fetcher).isInstanceOf(ClickHouseTruthJoinedPredictionFetcher.class);
  }

  @Test
  void joins_truth_and_drops_unmatched_outofvocab_nullkey_and_nonpitch() {
    // (1,1,1) prediction + truth in_play -> scorable, truthClass 4.
    prediction(1, 1, 1, pitchJson(0.10, 0.10, 0.10, 0.20, 0.50));
    truth(1, 1, 1, "in_play");

    // (1,1,2) prediction + truth ball -> scorable, truthClass 0.
    prediction(1, 1, 2, pitchJson(0.60, 0.10, 0.10, 0.10, 0.10));
    truth(1, 1, 2, "ball");

    // (1,1,3) prediction, NO pitches_live truth -> dropped (NULL truth, join_use_nulls=1).
    prediction(1, 1, 3, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));

    // (1,1,4) prediction + truth hit_by_pitch (outside the 5-class vocab) -> dropped, NOT bucketed.
    prediction(1, 1, 4, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));
    truth(1, 1, 4, "hit_by_pitch");

    // (1,1,5) non-pitch payload (batted-ball {"prob_hr":x}) with truth -> dropped (unparseable).
    prediction(1, 1, 5, "{\"prob_hr\":0.4}");
    truth(1, 1, 5, "ball");

    // HTTP-path NULL-key row -> dropped (game_id IS NOT NULL).
    predictionNoKey(pitchJson(0.30, 0.30, 0.10, 0.10, 0.20));

    List<TruthJoinedRow> rows =
        fetcher.fetch(MODEL, V, Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(TruthJoinedRow::truthClass).containsExactlyInAnyOrder(0, 4);

    TruthJoinedRow inPlay =
        rows.stream().filter(r -> r.truthClass() == 4).findFirst().orElseThrow();
    // OUTCOME_CLASSES order: [ball, called_strike, swinging_strike, foul, in_play].
    assertThat(inPlay.probs())
        .containsExactly(new double[] {0.10, 0.10, 0.10, 0.20, 0.50}, within(1e-9));
  }

  @Test
  void only_returns_the_requested_version() {
    prediction(2, 1, 1, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2)); // version V
    truth(2, 1, 1, "ball");
    insert(99L, 2, 1, 2, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2)); // a different version
    truth(2, 1, 2, "ball");

    List<TruthJoinedRow> rows =
        fetcher.fetch(MODEL, V, Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).truthClass()).isZero();
  }

  // --- seed helpers ----------------------------------------------------------

  private void prediction(long gameId, int atBat, int pitch, String predictionJson) {
    insert(V, gameId, atBat, pitch, predictionJson);
  }

  private void insert(long versionId, long gameId, int atBat, int pitch, String predictionJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, prediction,"
            + "  game_id, at_bat_index, pitch_number)"
            + " VALUES (?, ?, ?, ?, 'shadow', ?, ?, ?, ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        MODEL,
        "v1",
        versionId,
        predictionJson,
        gameId,
        atBat,
        pitch);
  }

  private void predictionNoKey(String predictionJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, prediction)"
            + " VALUES (?, ?, ?, ?, 'shadow', ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        MODEL,
        "v1",
        V,
        predictionJson);
  }

  private void truth(long gameId, int atBat, int pitch, String description) {
    ch.update(
        "INSERT INTO pitches_live"
            + " (game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,"
            + "  description, balls, strikes, outs, inning, home_score, away_score,"
            + "  home_team, away_team)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, 0, 0, 'HOM', 'AWY')",
        gameId,
        atBat,
        pitch,
        java.sql.Date.valueOf("2026-06-13"),
        100,
        200,
        description);
  }

  private static String pitchJson(
      double ball, double calledStrike, double swStrike, double foul, double inPlay) {
    return String.format(
        "{\"probabilities\":{\"ball\":%s,\"called_strike\":%s,\"swinging_strike\":%s,"
            + "\"foul\":%s,\"in_play\":%s},\"winner\":\"ball\"}",
        ball, calledStrike, swStrike, foul, inPlay);
  }
}
