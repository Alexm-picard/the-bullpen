package net.thebullpen.baseball.registry.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
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
 * Real-ClickHouse round-trip for {@link ClickHousePairedPredictionFetcher} (3c). Confirms the
 * {@code @Primary} wiring supersedes the stub when ClickHouse is enabled; that the champion-vs-
 * challenger pivot on the live key returns scorable pairs with exact probability round-trips; and
 * that every non-scorable case is dropped: unpaired champion (challenger missing), truth-missing
 * pair, HTTP-path NULL-key row, out-of-vocab truth, and the wrong version. The last test pins the
 * {@code join_use_nulls = 1} contract directly. Docker-gated exactly like {@link
 * net.thebullpen.baseball.drift.RealPredictionDistributionFetcherIT}.
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
class ClickHousePairedPredictionFetcherIT {

  private static final String MODEL = "pitch_outcome_post";
  private static final long CHAMP_V = 1L;
  private static final long CHALL_V = 2L;

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
                "bullpen-paired-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PairedPredictionFetcher fetcher;

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
    assertThat(fetcher).isInstanceOf(ClickHousePairedPredictionFetcher.class);
  }

  @Test
  void pairs_scorable_predictions_and_drops_every_non_scorable_case() {
    // Pitch A (1,1,1): champion + shadow + truth in_play -> scorable, truthClass 4.
    champion(1, 1, 1, pitchJson(0.10, 0.10, 0.10, 0.20, 0.50));
    shadow(1, 1, 1, pitchJson(0.05, 0.15, 0.10, 0.20, 0.50));
    truth(1, 1, 1, "in_play");

    // Pitch B (1,1,2): champion + shadow + truth ball -> scorable, truthClass 0.
    champion(1, 1, 2, pitchJson(0.60, 0.10, 0.10, 0.10, 0.10));
    shadow(1, 1, 2, pitchJson(0.55, 0.15, 0.10, 0.10, 0.10));
    truth(1, 1, 2, "ball");

    // Pitch C (1,1,3): champion ONLY (challenger missing) -> dropped by the INNER JOIN.
    champion(1, 1, 3, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));
    truth(1, 1, 3, "foul");

    // Pitch D (1,2,1): champion + shadow but NO pitches_live truth -> dropped (NULL truth).
    champion(1, 2, 1, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));
    shadow(1, 2, 1, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));

    // Pitch E: HTTP-path NULL-key rows (no live key) -> dropped (game_id IS NOT NULL).
    championNoKey(pitchJson(0.30, 0.30, 0.10, 0.10, 0.20));
    shadowNoKey(pitchJson(0.30, 0.30, 0.10, 0.10, 0.20));

    // Pitch F (1,2,2): champion + shadow + truth hit_by_pitch (out of the 5-class vocab) ->
    // dropped.
    champion(1, 2, 2, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));
    shadow(1, 2, 2, pitchJson(0.20, 0.20, 0.20, 0.20, 0.20));
    truth(1, 2, 2, "hit_by_pitch");

    List<PairedPrediction> pairs =
        fetcher.fetch(
            MODEL,
            String.valueOf(CHAMP_V),
            String.valueOf(CHALL_V),
            Instant.now().minus(24, ChronoUnit.HOURS),
            Instant.now());

    assertThat(pairs).hasSize(2);
    assertThat(pairs).allSatisfy(p -> assertThat(p.championProbs()).hasSize(5));
    assertThat(pairs).extracting(PairedPrediction::truthClass).containsExactlyInAnyOrder(0, 4);

    PairedPrediction inPlay =
        pairs.stream().filter(p -> p.truthClass() == 4).findFirst().orElseThrow();
    // OUTCOME_CLASSES order: [ball, called_strike, swinging_strike, foul, in_play].
    assertThat(inPlay.championProbs())
        .containsExactly(new double[] {0.10, 0.10, 0.10, 0.20, 0.50}, within(1e-9));
    assertThat(inPlay.challengerProbs())
        .containsExactly(new double[] {0.05, 0.15, 0.10, 0.20, 0.50}, within(1e-9));
  }

  @Test
  void respects_the_champion_and_challenger_version_ids() {
    // A pitch whose champion row is the WRONG version (99) - even with a correct-version shadow and
    // truth, it must not pair, because the champion side filters model_version_id = CHAMP_V.
    insert(MODEL, 99L, "champion", 2L, 1, 1, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));
    shadow(2, 1, 1, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));
    truth(2, 1, 1, "ball");

    List<PairedPrediction> pairs =
        fetcher.fetch(
            MODEL,
            String.valueOf(CHAMP_V),
            String.valueOf(CHALL_V),
            Instant.now().minus(24, ChronoUnit.HOURS),
            Instant.now());

    assertThat(pairs).isEmpty();
  }

  @Test
  void join_use_nulls_keeps_unmatched_truth_null_not_zero_filled() {
    // A champion+shadow pair with no pitches_live row. With join_use_nulls=1 the LEFT-JOINed
    // description must come back NULL; without the setting ClickHouse would zero-fill it to '' (the
    // String type default), which the fetcher would then drop as out-of-vocab rather than as
    // unmatched truth - a silent reclassification. This pins the setting at the SQL level.
    champion(3, 1, 1, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));
    shadow(3, 1, 1, pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));

    // Not stream().findFirst(): the single returned value IS null (the whole point), and
    // Stream.findFirst() throws NPE trying to box a null element into an Optional. Read row 0.
    List<String> truth =
        ch.query(
            "SELECT t.description AS d"
                + " FROM (SELECT game_id, at_bat_index, pitch_number FROM prediction_log"
                + "       WHERE role = 'champion' AND game_id IS NOT NULL) AS c"
                + " LEFT JOIN (SELECT game_id, at_bat_index, pitch_number, description"
                + "            FROM pitches_live FINAL) AS t"
                + "   ON c.game_id = t.game_id AND c.at_bat_index = t.at_bat_index"
                + "      AND c.pitch_number = t.pitch_number"
                + " SETTINGS join_use_nulls = 1",
            (rs, n) -> rs.getString("d"));

    assertThat(truth).hasSize(1);
    assertThat(truth.get(0)).as("unmatched LEFT JOIN truth must be NULL, not ''").isNull();
  }

  // --- seed helpers ----------------------------------------------------------

  private void champion(long gameId, int atBat, int pitch, String predictionJson) {
    insert(MODEL, CHAMP_V, "champion", gameId, atBat, pitch, predictionJson);
  }

  private void shadow(long gameId, int atBat, int pitch, String predictionJson) {
    insert(MODEL, CHALL_V, "shadow", gameId, atBat, pitch, predictionJson);
  }

  private void insert(
      String model,
      long versionId,
      String role,
      long gameId,
      int atBat,
      int pitch,
      String predictionJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, prediction,"
            + "  game_id, at_bat_index, pitch_number)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        model,
        "v1",
        versionId,
        role,
        predictionJson,
        gameId,
        atBat,
        pitch);
  }

  /** HTTP-path row: the live-key columns are left out, so they default to NULL (V017 Nullable). */
  private void championNoKey(String predictionJson) {
    insertNoKey(CHAMP_V, "champion", predictionJson);
  }

  private void shadowNoKey(String predictionJson) {
    insertNoKey(CHALL_V, "shadow", predictionJson);
  }

  private void insertNoKey(long versionId, String role, String predictionJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, prediction)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        MODEL,
        "v1",
        versionId,
        role,
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
