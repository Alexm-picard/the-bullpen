package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import net.thebullpen.baseball.ingest.GameStatus;
import net.thebullpen.baseball.ingest.LiveGameFeed;
import net.thebullpen.baseball.ingest.MlbFeedParser;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-ClickHouse round-trip for {@link LivePitchesRepository} (leaf 4d / live-games view).
 *
 * <p>Exists because the date-binding bug fixed alongside this test ({@code findGamesForDate}'s
 * {@code WHERE game_date = ?} inlined a bare {@code java.sql.Date} as {@code 2026-06-05} ->
 * arithmetic {@code 2015}, an Int64, under clickhouse-jdbc 0.7.2) shipped undetected: the only
 * coverage was {@code GameControllerTest}, a MockMvc test that <em>mocks</em> the repository, so
 * the real query never ran. Mocking the ClickHouse boundary is exactly the divergence the testing
 * posture warns against; this IT closes it.
 *
 * <p>Docker-gated like {@link net.thebullpen.baseball.drift.DriftMetricsRepositoryIT}.
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
class LivePitchesRepositoryIT {

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
                "bullpen-livepitches-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private LivePitchesRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    // pitches_live (V015) + prediction_log (V004/V017) are created by ClickHouseMigrationRunner.
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS pitches_live");
      stmt.execute("TRUNCATE TABLE IF EXISTS prediction_log");
      stmt.execute("TRUNCATE TABLE IF EXISTS live_game_status");
      stmt.execute("TRUNCATE TABLE IF EXISTS scheduled_games");
    }
  }

  private void insertPrediction(long gameId, int atBat, int pitch, String predictionJson)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          String.format(
              "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                  + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                  + " game_id, at_bat_index, pitch_number) VALUES (generateUUIDv4(), now64(3),"
                  + " 'pitch_outcome_pre', 'v1', 'champion', 'h', '{}', '%s', 1.0, '', %d, %d, %d)",
              predictionJson, gameId, atBat, pitch));
    }
  }

  private static LivePitchRow pitch(List<LivePitchRow> rows, int atBat, int pitchNumber) {
    return rows.stream()
        .filter(r -> r.atBatIndex() == atBat && r.pitchNumber() == pitchNumber)
        .findFirst()
        .orElseThrow();
  }

  private void insertPitch(
      long gameId, LocalDate date, int atBat, int pitch, String home, String away, int inning)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          String.format(
              "INSERT INTO pitches_live (game_id, at_bat_index, pitch_number, game_date,"
                  + " pitcher_id, batter_id, description, balls, strikes, outs, inning,"
                  + " home_score, away_score, home_team, away_team) VALUES"
                  + " (%d, %d, %d, '%s', 1, 1, 'ball', 0, 0, 0, %d, 2, 3, '%s', '%s')",
              gameId, atBat, pitch, date, inning, home, away));
    }
  }

  @Test
  void findGamesForDate_round_trips_and_binds_the_date_as_a_real_date() throws Exception {
    LocalDate target = LocalDate.of(2026, 6, 5);
    insertPitch(101L, target, 1, 1, "BOS", "NYY", 6);
    insertPitch(101L, target, 1, 2, "BOS", "NYY", 7); // later pitch -> max(inning) = 7
    insertPitch(
        202L, target.minusDays(1), 1, 1, "LAD", "SF", 3); // different date, must be excluded

    // Before the toDate(?)/String fix this threw Code 43 (Date vs Int64); now it returns the game.
    List<GameSummary> games = repo.findGamesForDate(target);

    assertEquals(1, games.size(), "only the target-date game should match the bound date");
    GameSummary g = games.get(0);
    assertEquals(101L, g.gameId());
    assertEquals(target, g.gameDate());
    assertEquals("BOS", g.homeTeam());
    assertEquals("NYY", g.awayTeam());
    assertEquals(7, g.inning(), "max(inning) across the game's pitches");
    assertEquals(2, g.homeScore());
    assertEquals(3, g.awayScore());
  }

  @Test
  void findGamesForDate_surfaces_a_pre_game_scheduled_game_with_no_pitches() throws Exception {
    LocalDate target = LocalDate.of(2026, 6, 5);
    // The slate's whole point: a game with NO pitches yet still shows, from scheduled_games.
    repo.upsertScheduledGames(
        List.of(
            new ScheduledGame(
                303L,
                GameStatus.SCHEDULED,
                "LAD",
                "SF",
                "Los Angeles Dodgers",
                "San Francisco Giants",
                Instant.parse("2026-06-05T20:10:00Z"),
                600001L,
                "Gerrit Cole",
                600002L,
                "Corbin Burnes")),
        target);

    List<GameSummary> games = repo.findGamesForDate(target);

    assertEquals(1, games.size());
    GameSummary g = games.get(0);
    assertEquals(303L, g.gameId());
    assertEquals(target, g.gameDate());
    assertEquals("LAD", g.homeTeam()); // schedule abbreviation (no pitches to override it)
    assertEquals("SF", g.awayTeam());
    assertEquals(0, g.homeScore()); // pre-game: no pitches -> 0
    assertEquals(0, g.inning());
    assertEquals("SCHEDULED", g.status());

    // Phase 2a: the probable pitchers round-trip (no GameSummary field yet - read them directly).
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement();
        var rs =
            stmt.executeQuery(
                "SELECT home_pitcher_name, away_pitcher_id FROM scheduled_games FINAL"
                    + " WHERE game_id = 303")) {
      assertTrue(rs.next());
      assertEquals("Gerrit Cole", rs.getString("home_pitcher_name"));
      assertEquals(600002L, rs.getLong("away_pitcher_id"));
    }
  }

  @Test
  void findGamesForDate_unions_pre_game_and_live_games_on_the_same_date() throws Exception {
    LocalDate target = LocalDate.of(2026, 6, 5);
    insertPitch(401L, target, 1, 1, "BOS", "NYY", 4); // live (pitches), no scheduled_games row
    repo.upsertScheduledGames(
        List.of(
            new ScheduledGame(
                402L, GameStatus.SCHEDULED, "LAD", "SF", "LA", "SF", null, 0L, "", 0L, "")),
        target); // pre-game (scheduled), no pitches

    List<GameSummary> games = repo.findGamesForDate(target);

    assertEquals(
        2, games.size(), "both the live (pitches-only) and pre-game (schedule-only) games");
  }

  @Test
  void findGamesForDate_returns_empty_for_a_date_with_no_games() throws Exception {
    insertPitch(101L, LocalDate.of(2026, 6, 5), 1, 1, "BOS", "NYY", 1);
    assertTrue(repo.findGamesForDate(LocalDate.of(2026, 6, 4)).isEmpty());
  }

  /** Parse the captured real game (BAL @ BOS, gamePk 824753) the same way the worker will. */
  private static LiveGameFeed parseFixture() throws Exception {
    try (InputStream in =
        LivePitchesRepositoryIT.class.getResourceAsStream("/mlb/feed_live_824753.json")) {
      assertNotNull(in, "missing fixture");
      String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return new MlbFeedParser(new ObjectMapper()).parseLiveFeed(json);
    }
  }

  @Test
  void insertPitches_round_trips_the_parsed_feed_through_real_clickhouse() throws Exception {
    LiveGameFeed feed = parseFixture();
    assertEquals(300, repo.insertPitches(feed), "every parsed pitch submitted");

    List<LivePitchRow> rows = repo.findPitchesSince(824753L, 0L);
    assertEquals(300, rows.size(), "every pitch reads back via the cursor query");

    // The HBP that ended at-bat 1 (pitch 6): pre-pitch count 2-2, canonical description, stored
    // through the real INSERT -> ReplacingMergeTree -> FINAL read path.
    LivePitchRow hbp =
        rows.stream()
            .filter(r -> r.atBatIndex() == 1 && r.pitchNumber() == 6)
            .findFirst()
            .orElseThrow();
    assertEquals("hit_by_pitch", hbp.description());
    assertEquals(2, hbp.balls());
    assertEquals(2, hbp.strikes());

    // And the game surfaces on its real date through the same read path the /v1/games API uses
    // (this also re-exercises the toDate(?) String binding from the Code 43 fix, now on real data).
    List<GameSummary> games = repo.findGamesForDate(LocalDate.of(2026, 6, 4));
    assertEquals(1, games.size());
    assertEquals("BOS", games.get(0).homeTeam());
    assertEquals("BAL", games.get(0).awayTeam());
  }

  @Test
  void insertPitches_is_idempotent_under_replacing_merge_tree() throws Exception {
    LiveGameFeed feed = parseFixture();
    repo.insertPitches(feed);
    repo.insertPitches(feed); // a re-poll of the same in-progress game

    // FINAL collapses duplicate (game_id, at_bat_index, pitch_number) keys back to one row each.
    assertEquals(300, repo.findPitchesSince(824753L, 0L).size());
  }

  @Test
  void findPitchesSince_left_joins_the_champion_prediction_for_a_pitch() throws Exception {
    repo.insertPitches(parseFixture()); // 300 pitches, no predictions yet
    insertPrediction(
        824753L,
        1,
        1,
        "{\"probabilities\":{\"ball\":0.6,\"called_strike\":0.4},\"winner\":\"ball\"}");

    List<LivePitchRow> rows = repo.findPitchesSince(824753L, 0L);
    LivePitchRow predicted = pitch(rows, 1, 1);
    LivePitchRow unpredicted = pitch(rows, 1, 2);

    assertEquals("ball", predicted.predictedWinner());
    assertNotNull(predicted.predictedClasses());
    assertEquals(0.6, predicted.predictedClasses().get("ball"));
    assertNull(unpredicted.predictedWinner(), "no prediction logged -> the frontend's n/a path");
    assertNull(unpredicted.predictedClasses());
  }

  @Test
  void findPitchesSince_takes_the_latest_champion_when_a_pitch_is_re_predicted() throws Exception {
    // predict-next re-logs the same upcoming pitch each poll (decision [143]); the join must keep
    // the latest one by request_at (argMax), not double-count or pick an arbitrary row.
    repo.insertPitches(parseFixture());
    insertPrediction(824753L, 1, 1, "{\"probabilities\":{\"ball\":0.9},\"winner\":\"ball\"}");
    Thread.sleep(5); // ensure a strictly later request_at
    insertPrediction(824753L, 1, 1, "{\"probabilities\":{\"in_play\":0.7},\"winner\":\"in_play\"}");

    assertEquals(
        "in_play",
        pitch(repo.findPitchesSince(824753L, 0L), 1, 1).predictedWinner(),
        "argMax(request_at) keeps the latest prediction");
  }

  @Test
  void findGamesForDate_surfaces_the_pollers_upserted_status() throws Exception {
    LocalDate date = LocalDate.of(2026, 6, 6);
    insertPitch(700L, date, 1, 1, "BOS", "NYY", 1);
    repo.upsertGameStatus(700L, date, "IN_PROGRESS");

    GameSummary g = repo.findGamesForDate(date).get(0);
    assertEquals("IN_PROGRESS", g.status());
    assertEquals("In Progress", g.detailedState(), "humanized for display");
  }

  @Test
  void findGamesForDate_defaults_to_unknown_without_a_status_row() throws Exception {
    LocalDate date = LocalDate.of(2026, 6, 6);
    insertPitch(701L, date, 1, 1, "BOS", "NYY", 1);

    assertEquals("UNKNOWN", repo.findGamesForDate(date).get(0).status());
  }

  @Test
  void upsertGameStatus_keeps_the_latest_status_under_replacing_merge_tree() throws Exception {
    LocalDate date = LocalDate.of(2026, 6, 6);
    insertPitch(702L, date, 1, 1, "BOS", "NYY", 1);
    repo.upsertGameStatus(702L, date, "SCHEDULED");
    Thread.sleep(5);
    repo.upsertGameStatus(702L, date, "IN_PROGRESS"); // a transition

    assertEquals("IN_PROGRESS", repo.findGame(702L).orElseThrow().status());
  }

  @Test
  void game_time_utc_round_trips_under_a_non_utc_jvm_timezone() {
    // Regression for the +4h game-time skew. game_time_utc is DateTime('UTC') (V023); the read
    // must NOT depend on the JVM default zone. The bug shipped because CI runs ClickHouse AND the
    // JVM in UTC, so the old getTimestamp() read round-tripped by luck. Pin the JVM to ET (the
    // box's zone) and assert the exact instant survives: a no-Calendar getTimestamp() returns +4h
    // here, whereas the getObject(LocalDateTime).toInstant(UTC) read is zone-independent.
    TimeZone original = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
      LocalDate date = LocalDate.of(2026, 6, 14);
      Instant firstPitch = Instant.parse("2026-06-14T23:20:00Z"); // 7:20 PM ET
      repo.upsertScheduledGames(
          List.of(
              new ScheduledGame(
                  808L,
                  GameStatus.SCHEDULED,
                  "BOS",
                  "NYY",
                  "Boston",
                  "NY",
                  firstPitch,
                  0L,
                  "",
                  0L,
                  "")),
          date);

      List<ScheduledGame> back = repo.findScheduledGames(date);
      assertEquals(1, back.size());
      assertEquals(
          firstPitch,
          back.get(0).gameTimeUtc(),
          "game_time_utc must round-trip exactly under a non-UTC JVM zone (no +4h read skew)");
    } finally {
      TimeZone.setDefault(original);
    }
  }
}
