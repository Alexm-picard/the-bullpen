package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
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
    // pitches_live (V015) is created by ClickHouseMigrationRunner on boot.
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS pitches_live");
    }
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
}
