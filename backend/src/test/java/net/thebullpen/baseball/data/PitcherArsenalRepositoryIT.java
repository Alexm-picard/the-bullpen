package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.ArsenalPitch;
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
 * Real-ClickHouse round-trip for {@link PitcherArsenalRepository} (Phase 2.1). Inserts a pitcher's
 * pitches across a few pitch types + velocities and asserts {@code findArsenal} returns the
 * per-type velocity range (min/avg/max), the throw count, and the usage share, ordered most-thrown
 * first, excluding velocity-unknown pitches. Docker-gated exactly like {@link
 * PitcherFormRepositoryIT}.
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
class PitcherArsenalRepositoryIT {

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
                "bullpen-arsenal-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PitcherArsenalRepository repo;

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
  void arsenal_aggregates_velocity_range_and_usage_per_pitch_type() {
    // Pitcher 100: 3 FF (95/97/99), 2 SL (86/88), 1 CH (85), + 1 FF with NULL velo (excluded).
    insertPitch(100, 1, "FF", 95.0);
    insertPitch(100, 2, "FF", 97.0);
    insertPitch(100, 3, "FF", 99.0);
    insertPitch(100, 4, "SL", 86.0);
    insertPitch(100, 5, "SL", 88.0);
    insertPitch(100, 6, "CH", 85.0);
    insertPitchNoVelo(100, 7, "FF"); // velocity-unknown -> not counted

    List<ArsenalPitch> arsenal = repo.findArsenal(100);

    assertThat(arsenal).hasSize(3);
    ArsenalPitch ff = arsenal.get(0); // most-thrown velocity-known type first
    assertThat(ff.pitchType()).isEqualTo("FF");
    assertThat(ff.count()).isEqualTo(3);
    assertThat(ff.veloMinMph()).isEqualTo(95.0);
    assertThat(ff.veloMaxMph()).isEqualTo(99.0);
    assertThat(ff.veloAvgMph()).isCloseTo(97.0, within(1e-4));
    assertThat(ff.usagePct()).isCloseTo(3.0 / 6.0, within(1e-6)); // 6 velocity-known pitches total

    assertThat(arsenal.get(1).pitchType()).isEqualTo("SL");
    assertThat(arsenal.get(1).count()).isEqualTo(2);
    assertThat(arsenal.get(2).pitchType()).isEqualTo("CH");
    assertThat(arsenal.get(2).count()).isEqualTo(1);
  }

  @Test
  void arsenal_is_empty_for_a_pitcher_with_no_tracked_pitches() {
    assertThat(repo.findArsenal(999)).isEmpty();
  }

  // --- helpers (bound values only; distinct pitch_number per row for the FINAL key) -------------

  private void insertPitch(int pitcherId, int pitchNo, String pitchType, double releaseSpeed) {
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, description, release_speed_mph) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        1L,
        "2025-05-01",
        1,
        pitchNo,
        pitcherId,
        pitchType,
        "called_strike",
        releaseSpeed);
  }

  /**
   * A pitch with release_speed_mph omitted -> NULL (Nullable column), so it is velocity-unknown.
   */
  private void insertPitchNoVelo(int pitcherId, int pitchNo, String pitchType) {
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, description) VALUES (?, ?, ?, ?, ?, ?, ?)",
        1L,
        "2025-05-01",
        1,
        pitchNo,
        pitcherId,
        pitchType,
        "called_strike");
  }
}
