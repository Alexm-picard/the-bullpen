package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
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
 * A5 hardening checks for {@link ClickHouseMigrationRunner} against a real ClickHouse: a second run
 * is a no-op, and a recorded-checksum that no longer matches the file body fails loud (an applied
 * migration was edited — forbidden). Same {@code -Dbullpen.it.docker=true} gate as the other
 * ClickHouse ITs (Docker Desktop on macOS returns malformed {@code /info} to Testcontainers; CI
 * sets the flag).
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
class ClickHouseMigrationRunnerIT {

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
                "bullpen-chmigrate-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource ch;

  /**
   * One sequential method to keep state deterministic on the shared container: the migrations have
   * already run on boot, so (1) a manual re-run must add nothing, then (2) after corrupting a
   * recorded checksum, the next run must throw. (Splitting these into two {@code @Test}s would make
   * them order-dependent — the drift row poisons the no-op assertion.)
   */
  @Test
  void reapplyIsNoop_thenChecksumDriftFailsLoud() throws Exception {
    long applied = countMigrations();
    assertThat(applied).isPositive();

    // (1) Boot already applied everything; a second pass is a pure no-op.
    new ClickHouseMigrationRunner(ch).apply();
    assertThat(countMigrations()).isEqualTo(applied);

    // (2) Forge a newer _schema_migrations row for an applied version with a bogus checksum.
    // ReplacingMergeTree FINAL then surfaces the bogus value, which no longer matches the file.
    String version = anyAppliedVersion();
    try (var conn = ch.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO _schema_migrations (version, checksum) VALUES (?, ?)")) {
      ps.setString(1, version);
      ps.setString(2, "0000000000000000000000000000000000000000000000000000000000000000");
      ps.executeUpdate();
    }

    assertThatThrownBy(() -> new ClickHouseMigrationRunner(ch).apply())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("checksum drift")
        .hasMessageContaining(version);
  }

  private long countMigrations() throws Exception {
    try (var conn = ch.getConnection();
        var st = conn.createStatement();
        var rs = st.executeQuery("SELECT count() FROM _schema_migrations FINAL")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String anyAppliedVersion() throws Exception {
    try (var conn = ch.getConnection();
        var st = conn.createStatement();
        var rs = st.executeQuery("SELECT version FROM _schema_migrations FINAL LIMIT 1")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
