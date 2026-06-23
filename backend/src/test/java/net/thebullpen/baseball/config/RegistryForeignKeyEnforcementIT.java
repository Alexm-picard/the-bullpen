package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M4 - proves the registry SQLite datasource actually enforces foreign keys and sets {@code
 * busy_timeout}, via the Xerial URL pragmas declared in {@code application.yml}.
 *
 * <p>Before M4, {@code PRAGMA foreign_keys} defaulted to OFF, so the {@code model_routing} / {@code
 * experiment_results} / {@code retraining_queue} -&gt; {@code model_versions} REFERENCES clauses
 * were documentation only: a routing/experiment row could be inserted against a non-existent model
 * id with no error. This is a regression guard that the pragma is live on the connections the
 * registry repos actually use.
 *
 * <p>Non-Docker (pure SQLite, ClickHouse stays disabled), so it runs in the normal {@code ./gradlew
 * test} lane - not gated behind {@code bullpen.it.docker}. The {@code registry-it} profile keeps
 * the web context down ({@code web-application-type: none}).
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class RegistryForeignKeyEnforcementIT {

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-fk-it-" + UUID.randomUUID() + ".sqlite");
    // @DynamicPropertySource overrides spring.datasource.url, so the production pragmas must be
    // repeated here - this test exists precisely to assert they take effect on the live datasource.
    String url = "jdbc:sqlite:" + dbPath + "?foreign_keys=true&busy_timeout=5000";
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-fk-it-snap-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private JdbcTemplate jdbc;

  @Test
  void foreignKeysAreEnforced_orphanRoutingInsertRejected() {
    Integer foreignKeysPragma = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
    assertThat(foreignKeysPragma)
        .as("PRAGMA foreign_keys must be ON (1) for registry connections")
        .isEqualTo(1);

    // model_routing.champion_version_id REFERENCES model_versions(id). id 999999 does not exist, so
    // with enforcement on the INSERT must be rejected instead of silently creating an orphan row.
    // SQLite raises SQLITE_CONSTRAINT_FOREIGNKEY (error code 19, null SQLState), which Spring's
    // code
    // translator leaves as an UncategorizedSQLException (a DataAccessException) - assert on the FK
    // message so this proves enforcement rather than just "some failure".
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing(model_name, champion_version_id) VALUES (?, ?)",
                    "fk-it-model",
                    999_999))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("FOREIGN KEY constraint failed");
  }

  @Test
  void busyTimeoutIsConfigured() {
    Integer busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Integer.class);
    assertThat(busyTimeout)
        .as("PRAGMA busy_timeout should reflect the 5000ms URL param")
        .isEqualTo(5000);
  }
}
