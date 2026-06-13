package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Real-ClickHouse round-trip for {@link PitcherFormRepository} (DP2 / WS3). Inserts synthetic
 * pitches across the 28-day boundary and asserts {@code refreshCurrentForm()} materialises one
 * current-form row per ACTIVE pitcher with rates matching compute_tier3.sql's definitions, and
 * excludes a pitcher whose only pitches fall outside the window. Docker-gated exactly like {@link
 * LivePitchesRepositoryIT}.
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
class PitcherFormRepositoryIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test")
          // Pin the server TZ so CH today() matches the JVM's LocalDate.now(UTC) the fixture dates
          // use (the days_since_last_appearance == 3 assertion depends on it); removes the reliance
          // on the base image's undocumented UTC default and the midnight-crossing flake.
          .withEnv("TZ", "UTC");

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
                "bullpen-pitcherform-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PitcherFormRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS pitches");
    ch.execute("TRUNCATE TABLE IF EXISTS pitcher_form_current");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  @Test
  void refresh_materialises_current_form_for_active_pitchers_only() {
    // Pitcher 100, 3 days ago: 10 pitches -> 4 strikes (1 called + 2 swinging + 1 foul),
    // 2 swinging, 3 in_play. Pitcher 200's only pitch is 40 days ago -> outside the 28-day window.
    insertPitches(
        100,
        daysAgo(3),
        "called_strike",
        "swinging_strike",
        "swinging_strike",
        "foul",
        "ball",
        "ball",
        "ball",
        "in_play",
        "in_play",
        "in_play");
    insertPitches(200, daysAgo(40), "ball");

    long refreshed = repo.refreshCurrentForm();
    assertThat(refreshed).isEqualTo(1); // only pitcher 100 is active in the window

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, strike_rate_28d, swstrike_rate_28d, inplay_rate_28d,"
                + " days_since_last_appearance"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 100");
    assertThat(num(row, "pitches_last_28d")).isEqualTo(10.0);
    assertThat(num(row, "strike_rate_28d")).isCloseTo(0.4, within(1e-6)); // 4/10
    assertThat(num(row, "swstrike_rate_28d")).isCloseTo(0.2, within(1e-6)); // 2/10
    assertThat(num(row, "inplay_rate_28d")).isCloseTo(0.3, within(1e-6)); // 3/10
    assertThat(num(row, "days_since_last_appearance")).isEqualTo(3.0); // today - (today-3)

    // The out-of-window pitcher has no current-form row.
    Long n200 =
        ch.queryForObject(
            "SELECT count() FROM pitcher_form_current FINAL WHERE pitcher_id = 200", Long.class);
    assertThat(n200).isZero();
  }

  @Test
  void findCurrent_reads_the_refreshed_form_and_is_empty_for_an_unknown_pitcher() {
    insertPitches(100, daysAgo(3), "called_strike", "swinging_strike", "ball", "in_play");
    repo.refreshCurrentForm();

    PitcherForm form = repo.findCurrent(100).orElseThrow();
    assertThat(form.pitchesLast28d()).isEqualTo(4.0);
    assertThat(form.daysSinceLastAppearance()).isEqualTo(3.0);
    assertThat(form.pitchesInGame()).isZero(); // nightly snapshot: in-game count is intra-day only

    assertThat(repo.findCurrent(999)).isEmpty(); // a pitcher with no current-form row
  }

  @Test
  void intra_day_upsert_sets_in_game_count_and_zeroes_dsla_carrying_28d_forward() {
    // Nightly snapshot: 3 days ago, 10 pitches -> pitches_in_game=0, dsla=3, 28d rates set.
    insertPitches(
        100,
        daysAgo(3),
        "called_strike",
        "swinging_strike",
        "swinging_strike",
        "foul",
        "ball",
        "ball",
        "ball",
        "in_play",
        "in_play",
        "in_play");
    repo.refreshCurrentForm();
    assertThat(repo.findCurrent(100).orElseThrow().pitchesInGame()).isZero();

    // Tonight: 5 live pitches for pitcher 100 in game 555.
    long gameId = 555L;
    for (int i = 1; i <= 5; i++) {
      insertLivePitch(gameId, 100, i);
    }

    repo.upsertIntraDayForm(100, gameId);

    PitcherForm after = repo.findCurrent(100).orElseThrow();
    assertThat(after.pitchesInGame()).isEqualTo(5.0); // live in-game count
    assertThat(after.daysSinceLastAppearance()).isZero(); // appearing today
    assertThat(after.pitchesLast28d()).isEqualTo(10.0); // 28-day window carried forward unchanged
    assertThat(after.strikeRate28d()).isCloseTo(0.4, within(1e-6)); // 4/10, unchanged

    // Idempotent: a second upsert with the same live count leaves the FINAL read stable.
    repo.upsertIntraDayForm(100, gameId);
    PitcherForm again = repo.findCurrent(100).orElseThrow();
    assertThat(again.pitchesInGame()).isEqualTo(5.0);
    assertThat(again.daysSinceLastAppearance()).isZero();
    assertThat(again.pitchesLast28d()).isEqualTo(10.0);
  }

  @Test
  void intra_day_upsert_is_a_noop_for_a_pitcher_with_no_current_row() {
    insertLivePitch(555L, 777, 1); // pitcher 777 has live pitches but no nightly form row
    repo.upsertIntraDayForm(777, 555L);
    assertThat(repo.findCurrent(777)).isEmpty(); // no row fabricated
  }

  // --- helpers ----------------------------------------------------------

  private void insertPitches(int pitcherId, String gameDate, String... descriptions) {
    for (int i = 0; i < descriptions.length; i++) {
      // Distinct pitch_number per row so the ReplacingMergeTree ORDER BY key
      // (game_date, game_id, at_bat_index, pitch_number) keeps each pitch under FINAL.
      ch.update(
          "INSERT INTO pitches"
              + " (game_id, game_date, at_bat_index, pitch_number, pitcher_id, description)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          (long) pitcherId,
          gameDate,
          1,
          i + 1,
          pitcherId,
          descriptions[i]);
    }
  }

  /**
   * One live pitch for the pitcher in a game. All bound values lead the VALUES list and the
   * constants trail it: clickhouse-jdbc mishandles a literal interleaved among ? placeholders.
   * game_date is irrelevant to the intra-day count (it keys on game_id + pitcher_id).
   */
  private void insertLivePitch(long gameId, int pitcherId, int pitchNumber) {
    ch.update(
        "INSERT INTO pitches_live"
            + " (game_id, pitch_number, pitcher_id, game_date,"
            + "  at_bat_index, batter_id, description, balls, strikes, outs, inning,"
            + "  home_score, away_score, home_team, away_team)"
            + " VALUES (?, ?, ?, ?, 1, 200, 'ball', 0, 0, 0, 1, 0, 0, 'HOM', 'AWY')",
        gameId,
        pitchNumber,
        pitcherId,
        java.sql.Date.valueOf("2026-06-13"));
  }

  private static String daysAgo(int days) {
    return LocalDate.now(ZoneOffset.UTC).minusDays(days).toString();
  }

  private static double num(Map<String, Object> row, String col) {
    return ((Number) row.get(col)).doubleValue();
  }
}
