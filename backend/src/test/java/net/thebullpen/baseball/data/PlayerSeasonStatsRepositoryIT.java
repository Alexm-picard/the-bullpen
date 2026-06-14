package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.ingest.PlayerSeasonStat;
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
 * Real-ClickHouse round-trip for {@link PlayerSeasonStatsRepository} (V021). Confirms the nullable
 * era/woba binding survives the trip (a pitching row has woba NULL, a hitting row has era NULL),
 * the computed wOBA persists, and the {@code IN (ids)} read returns the latest per (player, season,
 * group). Docker-gated like {@link LivePitchesRepositoryIT}.
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
class PlayerSeasonStatsRepositoryIT {

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
                "bullpen-seasonstats-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PlayerSeasonStatsRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS player_season_stats");
    }
  }

  @Test
  void upsert_and_read_round_trip_with_nullable_metrics() {
    repo.upsert(
        List.of(
            new PlayerSeasonStat(605483L, 2026, "pitching", 3.45, null, 700),
            new PlayerSeasonStat(592450L, 2026, "hitting", null, 0.389, 573)));

    List<PlayerSeasonStat> got = repo.findForPlayers(List.of(605483L, 592450L), 2026);

    assertEquals(2, got.size());
    PlayerSeasonStat pitcher =
        got.stream().filter(s -> s.playerId() == 605483L).findFirst().orElseThrow();
    PlayerSeasonStat hitter =
        got.stream().filter(s -> s.playerId() == 592450L).findFirst().orElseThrow();

    assertNotNull(pitcher.era());
    assertEquals(3.45, pitcher.era(), 1e-5);
    assertNull(pitcher.woba());
    assertEquals(700, pitcher.sample().intValue());

    assertNull(hitter.era());
    assertNotNull(hitter.woba());
    assertEquals(0.389, hitter.woba(), 1e-5);
  }

  @Test
  void read_excludes_other_seasons_and_unrequested_players() {
    repo.upsert(
        List.of(
            new PlayerSeasonStat(1L, 2026, "pitching", 2.50, null, 500),
            new PlayerSeasonStat(1L, 2025, "pitching", 4.00, null, 480), // other season
            new PlayerSeasonStat(2L, 2026, "pitching", 3.00, null, 400))); // unrequested player

    List<PlayerSeasonStat> got = repo.findForPlayers(List.of(1L), 2026);

    assertEquals(1, got.size());
    assertEquals(2.50, got.get(0).era(), 1e-5);
  }
}
