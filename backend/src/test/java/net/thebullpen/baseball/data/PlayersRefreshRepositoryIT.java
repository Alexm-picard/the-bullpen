package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.ingest.MlbPlayer;
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
 * Real-ClickHouse round-trip for {@link PlayersRefreshRepository} (DP3 / WS3): writes land in
 * {@code players} (V014), a re-pull replaces rows on FINAL reads via the ReplacingMergeTree, and
 * the clamped FixedString widths round-trip. Docker-gated exactly like {@link
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
class PlayersRefreshRepositoryIT {

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

  @Autowired private PlayersRefreshRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS players");
  }

  @Test
  void upsert_writes_rows_and_a_repull_replaces_them_on_final_reads() throws InterruptedException {
    int written =
        repo.upsertAll(
            List.of(
                new MlbPlayer(660271, "Shohei Ohtani", "TW", "L", "R", true, "LAD"),
                new MlbPlayer(671096, "Andrew Abbott", "P", "L", "L", true, "CIN")));
    assertThat(written).isEqualTo(2);
    assertThat(repo.countAll()).isEqualTo(2);

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT name, primary_position, bats, throws, active, team FROM players FINAL"
                + " WHERE id = 660271");
    assertThat(row.get("name")).isEqualTo("Shohei Ohtani");
    // FixedString zero-pads short values; trim like the read side (PlayerRepository) does.
    assertThat(str(row, "primary_position")).isEqualTo("TW");
    assertThat(str(row, "bats")).isEqualTo("L");
    assertThat(str(row, "throws")).isEqualTo("R");
    assertThat(((Number) row.get("active")).intValue()).isEqualTo(1);
    assertThat(row.get("team")).isEqualTo("LAD");

    // updated_at is DateTime (second granularity): make the re-pull's version strictly newer so
    // the ReplacingMergeTree pick is deterministic.
    Thread.sleep(1100);
    repo.upsertAll(List.of(new MlbPlayer(660271, "Shohei Ohtani", "DH", "L", "R", false, "LAD")));

    Map<String, Object> replaced =
        ch.queryForMap(
            "SELECT name, primary_position, active FROM players FINAL WHERE id = 660271");
    assertThat(str(replaced, "primary_position")).isEqualTo("DH");
    assertThat(((Number) replaced.get("active")).intValue()).isZero();
    assertThat(repo.countAll()).isEqualTo(2); // replaced, not duplicated
  }

  @Test
  void empty_table_counts_zero_and_an_empty_upsert_is_a_noop() {
    assertThat(repo.countAll()).isZero();
    assertThat(repo.upsertAll(List.of())).isZero();
    assertThat(repo.countAll()).isZero();
  }

  private static String str(Map<String, Object> row, String col) {
    return ((String) row.get(col)).trim();
  }
}
