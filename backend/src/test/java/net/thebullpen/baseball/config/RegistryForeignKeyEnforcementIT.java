package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
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

    // experiment_results.challenger_version_id REFERENCES model_versions(id). id 999999 does not
    // exist, so with enforcement on the INSERT must be rejected instead of silently creating an
    // orphan row. SQLite raises SQLITE_CONSTRAINT_FOREIGNKEY (error code 19, null SQLState),
    // which Spring's code translator leaves as an UncategorizedSQLException (a
    // DataAccessException) - assert on the FK message so this proves enforcement rather than just
    // "some failure".
    //
    // The probe has moved twice, both times chased off by a trigger - once between COLUMNS and
    // once between TABLES: V020 claimed model_routing's champion column (probe moved to its
    // challenger column), then V021 claimed the challenger column too, forcing the probe off the
    // table entirely. model_routing has NO untriggered FK column left, so any probe
    // there surfaces a stage-guard message and would pass with foreign_keys OFF, gutting the
    // test. experiment_results.challenger_version_id carries the same FK to the same target table
    // and has no trigger: the honest probe.
    long championId = insertChampionModelVersion("fk-it-model", "v-fk-1");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO experiment_results (model_name, champion_version_id,"
                        + " challenger_version_id, started_at, primary_metric, primary_threshold,"
                        + " guardrails, sample_size_target, status) VALUES (?, ?, ?,"
                        + " CURRENT_TIMESTAMP, 'brier', 0.2, '{}', 1000, 'running')",
                    "fk-it-model",
                    championId,
                    999_999))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("FOREIGN KEY constraint failed");
  }

  /**
   * Challenger twin of the champion ordering pin below: V021's challenger-stage trigger is
   * NULL-safe and fires BEFORE foreign-key enforcement, so a dangling non-null
   * challenger_version_id surfaces as the stage invariant, never as the FK message. Records which
   * guard owns the case (and would catch an IS NOT -> {@code <>} regression as a message change).
   */
  @Test
  void danglingChallengerVersionIdIsCaughtByTheStageGuardBeforeTheForeignKey() {
    long championId = insertChampionModelVersion("fk-it-model-chal", "v-fk-chal");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing(model_name, champion_version_id,"
                        + " challenger_version_id) VALUES (?, ?, ?)",
                    "fk-it-model-chal",
                    championId,
                    999_999))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("model_routing.challenger_version_id, when non-null, must reference");
  }

  /**
   * Companion to the above, pinning the ordering it depends on: V020's champion-stage trigger fires
   * BEFORE foreign-key enforcement, so a dangling champion_version_id surfaces as the stage
   * invariant rather than as "FOREIGN KEY constraint failed". Either way the orphan row is refused;
   * this records WHICH guard owns that case, so a future change to the trigger's WHEN clause (for
   * instance swapping the NULL-safe {@code IS NOT} for {@code <>}, which would let a dangling id
   * fall through) shows up here as a message change rather than passing silently.
   */
  @Test
  void danglingChampionVersionIdIsCaughtByTheStageGuardBeforeTheForeignKey() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing(model_name, champion_version_id) VALUES (?, ?)",
                    "fk-it-dangling-champion",
                    999_999))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining(
            "model_routing.champion_version_id must reference a model_versions row of the same"
                + " model_name at stage champion");
  }

  @Test
  void busyTimeoutIsConfigured() {
    Integer busyTimeout = jdbc.queryForObject("PRAGMA busy_timeout", Integer.class);
    assertThat(busyTimeout)
        .as("PRAGMA busy_timeout should reflect the 5000ms URL param")
        .isEqualTo(5000);
  }

  /**
   * Insert a champion-stage model_versions row and return its id. Needed since V020: a routing row
   * used as an FK probe must itself name a champion, or the stage trigger rejects it first.
   */
  private long insertChampionModelVersion(String modelName, String version) {
    jdbc.update(
        """
        INSERT INTO model_versions (model_name, version, artifact_path, metadata_path,
            training_data_hash, training_data_window, feature_schema_hash, eval_metrics,
            trained_at, stage)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'champion')
        """,
        modelName,
        version,
        "/snapshots/" + version + "/model.onnx",
        "/snapshots/" + version + "/metadata.json",
        "hash-" + version,
        "[2024-01-01,2024-12-31]",
        "feature-hash-" + version,
        "{}",
        Timestamp.from(Instant.now()));
    Long id =
        jdbc.queryForObject(
            "SELECT id FROM model_versions WHERE model_name = ? AND version = ?",
            Long.class,
            modelName,
            version);
    if (id == null) {
      throw new IllegalStateException(
          "model_versions row not found after insert: " + modelName + "/" + version);
    }
    return id;
  }
}
