package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
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
 * Integration test for {@link PlayerRepository} against real ClickHouse. Same {@code
 * -Dbullpen.it.docker=true} gate as the other {@code *IT} suites.
 *
 * <p>Seeds five rows directly (1 active pitcher with id 660271 "Aaron Judge", 1 active hitter with
 * id 545361 "Mike Trout", 1 retired player with id 100001 "Babe Ruth", 1 with a name that shares a
 * substring "Trouton" to exercise positionCaseInsensitive(), and 1 distinct id-only entry to
 * exercise the id-prefix branch).
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + " — set -Dbullpen.it.docker=true to force-run in CI.")
class PlayerRepositoryIT {

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
                "bullpen-players-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PlayerRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouse;

  @BeforeEach
  void seed() throws Exception {
    try (var conn = clickhouse.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS players");
      stmt.execute(
          "INSERT INTO players (id, name, primary_position, bats, throws, active) VALUES"
              + " (660271, 'Aaron Judge',   'RF', 'R', 'R', 1),"
              + " (545361, 'Mike Trout',    'CF', 'R', 'R', 1),"
              + " (100001, 'Babe Ruth',     'LF', 'L', 'L', 0),"
              + " (700001, 'Trouton Smith', 'SS', 'R', 'R', 1),"
              + " (660272, 'Other Judge',   'C',  'R', 'R', 0)");
    }
  }

  @Test
  void search_byName_caseInsensitive_substring() {
    var hits = repo.search("judge", 10);
    assertThat(hits)
        .extracting(p -> p.name())
        .containsExactlyInAnyOrder("Aaron Judge", "Other Judge");
  }

  @Test
  void search_byName_orders_activeFirst_thenAlpha() {
    var hits = repo.search("judge", 10);
    // Aaron Judge (active=1) before Other Judge (active=0).
    assertThat(hits.get(0).name()).isEqualTo("Aaron Judge");
    assertThat(hits.get(0).active()).isTrue();
    assertThat(hits.get(1).active()).isFalse();
  }

  @Test
  void search_byName_matchesSubstring_notJustPrefix() {
    var hits = repo.search("trout", 10);
    // "Mike Trout" + "Trouton Smith" both contain "trout" case-insensitive.
    assertThat(hits).hasSize(2);
  }

  @Test
  void search_byIdPrefix_when_q_isNumeric() {
    var hits = repo.search("6602", 10);
    assertThat(hits).extracting(p -> p.id()).containsExactlyInAnyOrder(660271L, 660272L);
  }

  @Test
  void search_byId_exact_returns_one() {
    var hits = repo.search("545361", 10);
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).name()).isEqualTo("Mike Trout");
  }

  @Test
  void search_emptyQuery_returns_empty() {
    assertThat(repo.search("", 10)).isEmpty();
    assertThat(repo.search("   ", 10)).isEmpty();
  }

  @Test
  void search_respects_limit() {
    var hits = repo.search("judge", 1);
    assertThat(hits).hasSize(1);
  }

  @Test
  void search_noMatch_returns_empty() {
    assertThat(repo.search("zzzzz", 10)).isEmpty();
  }

  @Test
  void findById_present_and_absent() {
    assertThat(repo.findById(660271L)).isPresent();
    assertThat(repo.findById(660271L).orElseThrow().name()).isEqualTo("Aaron Judge");
    assertThat(repo.findById(9_999_999L)).isEmpty();
  }
}
