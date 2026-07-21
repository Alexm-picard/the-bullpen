package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.GameSummary;
import net.thebullpen.baseball.domain.LivePitch;
import net.thebullpen.baseball.domain.LivePitchRow;
import net.thebullpen.baseball.domain.PagedRows;
import net.thebullpen.baseball.domain.PostPredictionRow;
import net.thebullpen.baseball.domain.ScheduledGame;
import net.thebullpen.baseball.ingest.LiveGameFeed;
import net.thebullpen.baseball.ingest.MlbFeedParser;
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
      stmt.execute("TRUNCATE TABLE IF EXISTS pitches");
      stmt.execute("TRUNCATE TABLE IF EXISTS prediction_log");
      stmt.execute("TRUNCATE TABLE IF EXISTS live_game_status");
      stmt.execute("TRUNCATE TABLE IF EXISTS scheduled_games");
    }
  }

  private void insertPrediction(long gameId, int atBat, int pitch, String predictionJson)
      throws Exception {
    insertPrediction(gameId, atBat, pitch, "pitch_outcome_pre", predictionJson);
  }

  private void insertPrediction(
      long gameId, int atBat, int pitch, String modelName, String predictionJson) throws Exception {
    insertPrediction(gameId, atBat, pitch, modelName, "champion", predictionJson);
  }

  private void insertPrediction(
      long gameId, int atBat, int pitch, String modelName, String role, String predictionJson)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          String.format(
              "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                  + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                  + " game_id, at_bat_index, pitch_number) VALUES (generateUUIDv4(), now64(3),"
                  + " '%s', 'v1', '%s', 'h', '{}', '%s', 1.0, '', %d, %d, %d)",
              modelName, role, predictionJson, gameId, atBat, pitch));
    }
  }

  /**
   * Build a minimal {@link LivePitch} carrying the A5 pre-pitch context (V028) plus enough of the
   * base pre-pitch state to round-trip through {@code insertPitches}. Tier-4 physics is left null
   * (irrelevant to this context); {@code terminal} is false.
   */
  private static LivePitch livePitch(
      long gameId,
      int atBatIndex,
      int pitchNumber,
      String pitchHand,
      String batSide,
      boolean onFirst,
      boolean onSecond,
      boolean onThird) {
    return new LivePitch(
        gameId,
        atBatIndex,
        pitchNumber,
        /* inning= */ 1,
        /* topInning= */ true,
        /* pitcherId= */ 1L,
        /* batterId= */ 2L,
        pitchHand,
        batSide,
        /* preBalls= */ 0,
        /* preStrikes= */ 0,
        /* outs= */ 0,
        onFirst,
        onSecond,
        onThird,
        /* homeScore= */ 0,
        /* awayScore= */ 0,
        /* description= */ "ball",
        /* pitchType= */ "FF",
        /* releaseSpeedMph= */ null,
        /* plateXIn= */ null,
        /* plateZIn= */ null,
        /* pfxXIn= */ null,
        /* pfxZIn= */ null,
        /* spinRateRpm= */ null,
        /* spinAxisDeg= */ null,
        /* releasePosXIn= */ null,
        /* releasePosZIn= */ null,
        /* terminal= */ false);
  }

  private static LivePitchRow pitch(List<LivePitchRow> rows, int atBat, int pitchNumber) {
    return rows.stream()
        .filter(r -> r.atBatIndex() == atBat && r.pitchNumber() == pitchNumber)
        .findFirst()
        .orElseThrow();
  }

  private static PostPredictionRow postRow(List<PostPredictionRow> rows, int atBat, int pitch) {
    return rows.stream()
        .filter(r -> r.atBatIndex() == atBat && r.pitchNumber() == pitch)
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

  /**
   * Insert a row into the canonical {@code pitches} table (V003), mimicking the overnight handoff
   * job that moves an in-play pitch over with its realized batted-ball metrics. Same natural key as
   * {@code pitches_live}, so the FIND_PITCHES_SINCE LEFT JOIN matches on (game_id, at_bat_index,
   * pitch_number).
   */
  private void insertCanonicalPitch(
      long gameId,
      LocalDate date,
      int atBat,
      int pitch,
      double launchSpeed,
      double launchAngle,
      double hitDistance,
      String bbType,
      String event)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(
          String.format(
              Locale.ROOT,
              "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, description,"
                  + " events, launch_speed_mph, launch_angle_deg, hit_distance_ft, bb_type)"
                  + " VALUES (%d, '%s', %d, %d, 'in_play', '%s', %f, %f, %f, '%s')",
              gameId,
              date,
              atBat,
              pitch,
              event,
              launchSpeed,
              launchAngle,
              hitDistance,
              bbType));
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
    // No canonical pitches backfill in this test -> the batted-ball LEFT JOIN misses -> null.
    assertNull(hbp.launchSpeedMph());
    assertNull(hbp.bbType());

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
  void insertPitches_round_trips_the_A5_pre_pitch_context() throws Exception {
    // A5 (V028): the writer now persists the pre-pitch context (pitch_hand / bat_side / base_state)
    // MlbFeedParser already extracted, and the games DTO surfaces it (+ parkId from home_team, +
    // the serving-path constant scoreDiff=0) so the frontend can assemble the A6 next-pitch request
    // mirroring LivePitchPredictor.toRequest. Round-trip a LivePitch with a known handed matchup
    // and
    // a runners-on-corners base state (first + third -> bitmask 1|4 = 5) through real ClickHouse.
    LivePitch p =
        livePitch(
            /* gameId= */ 555L,
            /* atBatIndex= */ 1,
            /* pitchNumber= */ 1,
            /* pitchHand= */ "L",
            /* batSide= */ "S", // switch hitter; resolved L|R downstream (resolveBatSide precedent)
            /* onFirst= */ true,
            /* onSecond= */ false,
            /* onThird= */ true);
    LiveGameFeed feed =
        new LiveGameFeed(
            555L,
            GameStatus.IN_PROGRESS,
            LocalDate.of(2026, 6, 4),
            111,
            222,
            "BOS",
            "NYY",
            List.of(p),
            null);

    assertEquals(1, repo.insertPitches(feed));

    LivePitchRow row = pitch(repo.findPitchesSince(555L, 0L), 1, 1);
    assertEquals("L", row.pitcherThrows(), "pitch_hand round-trips");
    assertEquals(
        "S", row.batterStand(), "bat_side round-trips, 'S' preserved for downstream resolve");
    assertEquals(5, row.baseState(), "base occupancy bitmask first(1)|third(4) = 5");
    assertEquals("BOS", row.parkId(), "parkId is home_team (the park id by project convention)");
    assertEquals(0, row.scoreDiff(), "serving-path constant 0, forwarded verbatim (not a column)");
  }

  @Test
  void findPitchesSince_returns_the_DDL_defaults_for_a_pre_migration_shaped_row() throws Exception {
    // A pre-V028 row never set pitch_hand / bat_side / base_state. The V028 DEFAULT '' (pitch_hand,
    // bat_side, LowCardinality) and Nullable-without-default (base_state) contract must read back
    // as
    // '' and NULL respectively - NOT a false bases-empty 0. insertPitch(...) inserts exactly such a
    // row (its column list omits the three new columns), so it exercises the DDL defaults directly.
    insertPitch(556L, LocalDate.of(2026, 6, 4), 1, 1, "LAD", "SF", 3);

    LivePitchRow row = pitch(repo.findPitchesSince(556L, 0L), 1, 1);
    assertEquals("", row.pitcherThrows(), "LowCardinality DEFAULT '' on a pre-V028 row");
    assertEquals("", row.batterStand(), "LowCardinality DEFAULT '' on a pre-V028 row");
    assertNull(
        row.baseState(), "Nullable(UInt8) with NO default -> NULL, never a false bases-empty 0");
    assertEquals("LAD", row.parkId(), "parkId still resolves from home_team");
    assertEquals(0, row.scoreDiff());
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
  void findPitchesSince_stays_PRE_only_even_when_a_later_POST_champion_row_exists()
      throws Exception {
    // F2.1a: the worker also logs a pitch_outcome_post champion row keyed to the SAME pitch, but
    // AFTER the pitch lands (a later request_at). Without the model_name filter that later POST row
    // would win argMax and surface the post head on the game page - the exact [143]/[154]/ADR-0011
    // violation registry-guard caught. The game page must keep showing the PRE prediction.
    repo.insertPitches(parseFixture());
    insertPrediction(
        824753L,
        1,
        1,
        "pitch_outcome_pre",
        "{\"probabilities\":{\"ball\":0.6},\"winner\":\"ball\"}");
    Thread.sleep(5); // the POST row lands strictly later
    insertPrediction(
        824753L,
        1,
        1,
        "pitch_outcome_post",
        "{\"probabilities\":{\"in_play\":0.95},\"winner\":\"in_play\"}");

    LivePitchRow predicted = pitch(repo.findPitchesSince(824753L, 0L), 1, 1);
    assertEquals(
        "ball",
        predicted.predictedWinner(),
        "the later POST row must NOT override the PRE prediction");
    assertEquals(0.6, predicted.predictedClasses().get("ball"));
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
  void findPitchesSince_left_joins_the_batted_ball_outcome_from_pitches() throws Exception {
    LocalDate date = LocalDate.of(2026, 6, 4);
    // Two live pitches on the same game. The overnight handoff has moved only pitch (1,2) into the
    // canonical pitches table with its realized batted-ball metrics; (1,1) has no pitches row yet.
    insertPitch(900L, date, 1, 1, "BOS", "NYY", 1);
    insertPitch(900L, date, 1, 2, "BOS", "NYY", 1);
    insertCanonicalPitch(900L, date, 1, 2, 102.5, 28.0, 412.0, "fly_ball", "home_run");

    List<LivePitchRow> rows = repo.findPitchesSince(900L, 0L);
    LivePitchRow inPlay = pitch(rows, 1, 2);
    LivePitchRow notBackfilled = pitch(rows, 1, 1);

    // The backfilled in-play pitch carries the batted-ball outcome via the pitches LEFT JOIN.
    assertEquals(102.5, inPlay.launchSpeedMph(), 1e-4);
    assertEquals(28.0, inPlay.launchAngleDeg(), 1e-4);
    assertEquals(412.0, inPlay.hitDistanceFt(), 1e-4);
    assertEquals("fly_ball", inPlay.bbType());
    assertEquals("home_run", inPlay.event());

    // No canonical pitches row -> LEFT JOIN miss -> all five batted-ball fields null (Nullable
    // columns null directly; the LowCardinality '' default collapses to null in the mapper).
    assertNull(notBackfilled.launchSpeedMph(), "no canonical pitches row -> null launch speed");
    assertNull(notBackfilled.launchAngleDeg());
    assertNull(notBackfilled.hitDistanceFt());
    assertNull(notBackfilled.bbType(), "LEFT JOIN miss -> '' -> null");
    assertNull(notBackfilled.event());
  }

  @Test
  void findPostPredictions_returns_only_champion_post_rows_joined_to_the_realized_outcome()
      throws Exception {
    // F2.1b: the retrospective panel serves the logged pitch_outcome_post CHAMPION predictions for
    // a
    // game, joined to each pitch's realized outcome (pitches_live.description).
    LocalDate date = LocalDate.of(2026, 6, 4);
    insertPitch(950L, date, 1, 1, "BOS", "NYY", 4);
    insertPitch(950L, date, 1, 2, "BOS", "NYY", 4);
    insertPitch(950L, date, 2, 1, "BOS", "NYY", 5);

    insertPrediction(
        950L,
        1,
        1,
        "pitch_outcome_post",
        "{\"probabilities\":{\"in_play\":0.7,\"ball\":0.3},\"winner\":\"in_play\"}");
    insertPrediction(
        950L, 1, 2, "pitch_outcome_post", "{\"probabilities\":{\"ball\":0.8},\"winner\":\"ball\"}");
    insertPrediction(
        950L,
        2,
        1,
        "pitch_outcome_post",
        "{\"probabilities\":{\"called_strike\":0.6},\"winner\":\"called_strike\"}");

    // Scoping decoys keyed to the SAME (1,1) pitch: a PRE champion (the game page's next-pitch) and
    // a
    // POST SHADOW row. Neither may surface here - this is the F2.1a model_name + role discipline.
    insertPrediction(
        950L,
        1,
        1,
        "pitch_outcome_pre",
        "{\"probabilities\":{\"ball\":0.9},\"winner\":\"ball_pre\"}");
    insertPrediction(
        950L,
        1,
        1,
        "pitch_outcome_post",
        "shadow",
        "{\"probabilities\":{\"in_play\":0.99},\"winner\":\"shadow_win\"}");

    PagedRows<PostPredictionRow> page = repo.findPostPredictions(950L, 0, 50);

    assertEquals(3, page.rows().size(), "only the three champion POST predictions, not the decoys");
    assertFalse(page.hasNext());

    // at_bat/pitch order.
    assertEquals(1, page.rows().get(0).atBatIndex());
    assertEquals(1, page.rows().get(0).pitchNumber());
    assertEquals(1, page.rows().get(1).atBatIndex());
    assertEquals(2, page.rows().get(1).pitchNumber());
    assertEquals(2, page.rows().get(2).atBatIndex());
    assertEquals(1, page.rows().get(2).pitchNumber());

    PostPredictionRow first = postRow(page.rows(), 1, 1);
    assertEquals(
        "in_play",
        first.postWinner(),
        "the POST champion winner, NOT the PRE champion or the POST shadow");
    assertEquals(0.7, first.postClasses().get("in_play"), 1e-9);
    assertEquals("ball", first.realizedOutcome(), "pitches_live.description via the LEFT JOIN");
    assertEquals(4, first.inning());
    assertEquals("v1", first.modelVersion());
  }

  @Test
  void findPostPredictions_paginates_with_over_fetch_hasNext() throws Exception {
    LocalDate date = LocalDate.of(2026, 6, 4);
    for (int p = 1; p <= 3; p++) {
      insertPitch(960L, date, 1, p, "BOS", "NYY", 1);
      insertPrediction(
          960L,
          1,
          p,
          "pitch_outcome_post",
          "{\"probabilities\":{\"ball\":0.5},\"winner\":\"ball\"}");
    }

    PagedRows<PostPredictionRow> p0 = repo.findPostPredictions(960L, 0, 2);
    assertEquals(2, p0.rows().size(), "page 0 fills to size");
    assertTrue(p0.hasNext(), "a third row exists -> hasNext");
    assertEquals(1, p0.rows().get(0).pitchNumber());
    assertEquals(2, p0.rows().get(1).pitchNumber());

    PagedRows<PostPredictionRow> p1 = repo.findPostPredictions(960L, 1, 2);
    assertEquals(1, p1.rows().size(), "last page has the remaining row");
    assertFalse(p1.hasNext(), "no rows beyond the last page");
    assertEquals(3, p1.rows().get(0).pitchNumber());
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
