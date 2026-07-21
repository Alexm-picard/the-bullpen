package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.BattedBallRow;
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
 * Real-ClickHouse round-trip for {@link BatterBattedBallsRepository} (Phase 2.2/2.3). Inserts a
 * batter's in-play balls (plus a non-in-play pitch that must be excluded) and asserts {@code
 * findBattedBalls} returns them newest-first and honors the hit-type, event (all-HRs), date-range,
 * and limit filters. Docker-gated exactly like {@link PitcherFormRepositoryIT}.
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
class BatterBattedBallsRepositoryIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test")
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
                "bullpen-battedballs-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private BatterBattedBallsRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS pitches");
  }

  @Test
  void returns_in_play_only_newest_first_and_honors_the_filters() {
    // Batter 200: 3 in-play balls across 3 seasons + 1 non-in-play pitch (must be excluded).
    insertBattedBall(200, 1, "2023-03-01", "ground_ball", "field_out", 88.0, 5.0, 30.0);
    insertBattedBall(200, 2, "2024-05-01", "line_drive", "single", 99.0, 12.0, 210.0);
    insertBattedBall(200, 3, "2025-07-01", "fly_ball", "home_run", 108.0, 30.0, 420.0);
    insertNonInPlay(200, 4, "2025-07-02"); // description='ball' -> excluded

    List<BattedBallRow> all = repo.findBattedBalls(200, null, null, null, null, 100);
    assertThat(all).hasSize(3); // the 'ball' is excluded
    assertThat(all.get(0).gameDate()).isEqualTo("2025-07-01"); // newest first
    assertThat(all.get(0).events()).isEqualTo("home_run");
    assertThat(all.get(0).bbType()).isEqualTo("fly_ball");
    assertThat(all.get(0).hitDistanceFt()).isEqualTo(420.0);

    // Hit-type filter.
    assertThat(repo.findBattedBalls(200, "fly_ball", null, null, null, 100)).hasSize(1);

    // Event filter = the all-HRs view.
    List<BattedBallRow> hrs = repo.findBattedBalls(200, null, "home_run", null, null, 100);
    assertThat(hrs).hasSize(1);
    assertThat(hrs.get(0).bbType()).isEqualTo("fly_ball");

    // Date-range filter excludes the 2023 ball.
    assertThat(repo.findBattedBalls(200, null, null, LocalDate.parse("2024-01-01"), null, 100))
        .hasSize(2);

    // Limit caps the result.
    assertThat(repo.findBattedBalls(200, null, null, null, null, 1)).hasSize(1);
  }

  @Test
  void is_empty_for_a_batter_with_no_batted_balls() {
    assertThat(repo.findBattedBalls(999, null, null, null, null, 100)).isEmpty();
  }

  // --- helpers (bound values only; distinct pitch_number per row for the FINAL key) -------------

  private void insertBattedBall(
      int batterId,
      int pitchNo,
      String gameDate,
      String bbType,
      String events,
      double launchSpeed,
      double launchAngle,
      double distance) {
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, batter_id,"
            + " description, bb_type, events, launch_speed_mph, launch_angle_deg, hit_distance_ft,"
            + " park_id, stand) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        1L,
        gameDate,
        1,
        pitchNo,
        batterId,
        "in_play",
        bbType,
        events,
        launchSpeed,
        launchAngle,
        distance,
        "NYY",
        "R");
  }

  private void insertNonInPlay(int batterId, int pitchNo, String gameDate) {
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, batter_id, description)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        1L,
        gameDate,
        1,
        pitchNo,
        batterId,
        "ball");
  }
}
