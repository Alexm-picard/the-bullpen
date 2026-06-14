package net.thebullpen.baseball.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.domain.GameMatchup;
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
 * Real-ClickHouse round-trip for {@link GameMatchupsRepository} (V022). Confirms a matchup
 * round-trips and that {@link GameMatchupsRepository#findForDate} returns best battle first (the
 * Featured panel takes row 0). Docker-gated like {@link LivePitchesRepositoryIT}.
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
class GameMatchupsRepositoryIT {

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
                "bullpen-matchups-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private GameMatchupsRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS game_matchups");
    }
  }

  @Test
  void upsert_and_findForDate_orders_best_battle_first() {
    LocalDate d = LocalDate.of(2026, 6, 5);
    repo.upsert(
        List.of(
            new GameMatchup(
                1L, d, "pitching", 10L, "A", "pitcher", 11L, "B", "pitcher", 3.0, "default"),
            new GameMatchup(
                2L, d, "pitching", 20L, "C", "pitcher", 21L, "D", "pitcher", 8.0, "default"),
            new GameMatchup(
                3L, d, "hitters", 30L, "E", "hitter", 31L, "F", "hitter", 5.0, "lineup")));

    List<GameMatchup> got = repo.findForDate(d);

    assertEquals(3, got.size());
    assertEquals(2L, got.get(0).gameId(), "highest battle_score (8.0) first");
    assertEquals(3L, got.get(1).gameId());
    assertEquals(1L, got.get(2).gameId());
    assertEquals(8.0, got.get(0).battleScore(), 1e-5);
    assertEquals("hitters", got.get(1).lean());
    assertEquals("hitter", got.get(1).homeRole());
    assertEquals("lineup", got.get(1).stage());
  }
}
