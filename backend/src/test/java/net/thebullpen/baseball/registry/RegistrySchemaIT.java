package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Integration test for the Phase 3a.1 SQLite registry schema (migrations V010-V013).
 *
 * <p>Runs against a temp SQLite created by the {@code registry-it} profile (see {@code
 * src/test/resources/application-registry-it.yml}) so the test doesn't touch the dev DB at {@code
 * ./data/registry.sqlite}. Flyway applies every migration in the classpath on context start; this
 * test then queries the schema + tries deliberately-invalid inserts to verify each CHECK constraint
 * fires.
 *
 * <p>Acceptance criteria for 3a.1: all 4 tables exist after migration, CHECK constraints reject
 * invalid values, indexes are created. Each of those gets its own test method below.
 *
 * <p>Constraint-violation assertions use {@link DataAccessException} rather than {@code
 * DataIntegrityViolationException} because sqlite-jdbc doesn't map SQLite's CHECK / UNIQUE error
 * codes to Spring's per-engine codes — they come through as the generic {@code
 * UncategorizedSQLException}. {@code DataAccessException} is the parent of both.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class RegistrySchemaIT {

  private static final String INSERT_MODEL_VERSION =
      """
      INSERT INTO model_versions (model_name, version, artifact_path, metadata_path,
          training_data_hash, training_data_window, feature_schema_hash, eval_metrics,
          trained_at, stage)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  @Autowired private JdbcTemplate jdbc;

  /**
   * Set the SQLite path to a stable per-JVM temp file. Doing it here rather than via {@code
   * application-registry-it.yml} avoids the {@code ${random.uuid}} re-resolution gotcha — a YAML
   * placeholder containing {@code random.uuid} resolves fresh on every property access, which would
   * point Flyway and the datasource at different temp files.
   */
  @DynamicPropertySource
  static void registryItDataSource(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-registry-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
  }

  /**
   * Isolate each method: the per-JVM SQLite is shared across tests, so without a reset they
   * accumulate (e.g. several 'champion' rows for the same model_name across methods, which V016's
   * partial unique index correctly forbids). Children (FK -> model_versions.id) first.
   */
  @BeforeEach
  void resetRegistryTables() {
    jdbc.execute("DELETE FROM model_routing");
    jdbc.execute("DELETE FROM experiment_results");
    jdbc.execute("DELETE FROM retraining_queue");
    jdbc.execute("DELETE FROM model_versions");
  }

  // --- existence -----------------------------------------------------------

  @Test
  void all_four_registry_tables_exist() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", String.class);
    assertThat(tables)
        .contains("model_versions", "model_routing", "experiment_results", "retraining_queue");
  }

  @Test
  void expected_indexes_are_created() {
    List<String> indexes =
        jdbc.queryForList(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"
                + " ORDER BY name",
            String.class);
    assertThat(indexes)
        .contains(
            "idx_mv_model_stage",
            "idx_er_model_status",
            "idx_rq_model_status",
            "idx_mv_one_champion_per_model");
  }

  // --- model_versions stage CHECK ------------------------------------------

  @Test
  void model_versions_stage_accepts_valid_values() {
    for (String stage : List.of("candidate", "shadow", "champion", "archived")) {
      insertModelVersion("pitch_outcome", "v-stage-" + stage, stage);
    }
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM model_versions WHERE model_name = 'pitch_outcome'",
            Integer.class);
    assertThat(count).isEqualTo(4);
  }

  @Test
  void model_versions_stage_rejects_garbage() {
    assertThatThrownBy(() -> insertModelVersion("pitch_outcome", "v-bad-stage", "garbage"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void model_versions_unique_constraint_rejects_duplicate_name_version_pair() {
    insertModelVersion("pitch_outcome", "v-unique-1", "candidate");
    assertThatThrownBy(() -> insertModelVersion("pitch_outcome", "v-unique-1", "candidate"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void model_versions_partial_unique_index_rejects_a_second_champion_for_one_model() {
    // V016 invariant (rule 9): at most one champion per model_name. A candidate/shadow + a champion
    // is fine; a SECOND champion for the same model_name is rejected by
    // idx_mv_one_champion_per_model.
    insertModelVersion("batted_ball", "v-champ-1", "champion");
    insertModelVersion(
        "batted_ball", "v-shadow-1", "shadow"); // non-champion stage is unconstrained
    assertThatThrownBy(() -> insertModelVersion("batted_ball", "v-champ-2", "champion"))
        .isInstanceOf(DataAccessException.class);
  }

  // --- model_routing CHECKs ------------------------------------------------

  @Test
  void model_routing_traffic_pct_rejects_above_100() {
    long championId = insertModelVersion("batted_ball", "v-routing-1", "champion");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing (model_name, champion_version_id,"
                        + " challenger_traffic_pct, mode) VALUES (?, ?, ?, ?)",
                    "batted_ball",
                    championId,
                    101.0,
                    "ab"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void model_routing_traffic_pct_rejects_below_zero() {
    long championId = insertModelVersion("batted_ball", "v-routing-2", "champion");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing (model_name, champion_version_id,"
                        + " challenger_traffic_pct, mode) VALUES (?, ?, ?, ?)",
                    "batted_ball",
                    championId,
                    -1.0,
                    "shadow"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void model_routing_mode_rejects_invalid_value() {
    long championId = insertModelVersion("batted_ball", "v-routing-3", "champion");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO model_routing (model_name, champion_version_id, mode)"
                        + " VALUES (?, ?, ?)",
                    "batted_ball",
                    championId,
                    "live"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void model_routing_defaults_to_shadow_mode_zero_traffic() {
    long championId = insertModelVersion("pitch_outcome", "v-routing-default", "champion");
    jdbc.update(
        "INSERT INTO model_routing (model_name, champion_version_id) VALUES (?, ?)",
        "pitch_outcome_default",
        championId);
    String mode =
        jdbc.queryForObject(
            "SELECT mode FROM model_routing WHERE model_name = 'pitch_outcome_default'",
            String.class);
    Double pct =
        jdbc.queryForObject(
            "SELECT challenger_traffic_pct FROM model_routing"
                + " WHERE model_name = 'pitch_outcome_default'",
            Double.class);
    assertThat(mode).isEqualTo("shadow");
    assertThat(pct).isEqualTo(0.0);
  }

  // --- experiment_results CHECK --------------------------------------------

  @Test
  void experiment_results_status_rejects_invalid_value() {
    long champ = insertModelVersion("pitch_outcome", "v-exp-champ", "champion");
    long chall = insertModelVersion("pitch_outcome", "v-exp-chall", "shadow");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO experiment_results (model_name, champion_version_id,"
                        + " challenger_version_id, started_at, primary_metric, primary_threshold,"
                        + " guardrails, sample_size_target, status) VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?)",
                    "pitch_outcome",
                    champ,
                    chall,
                    Timestamp.from(Instant.now()),
                    "brier",
                    0.02,
                    "{}",
                    10000,
                    "in_progress"))
        .isInstanceOf(DataAccessException.class);
  }

  // --- retraining_queue CHECKs --------------------------------------------

  @Test
  void retraining_queue_trigger_type_rejects_garbage() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type)"
                        + " VALUES (?, ?, ?)",
                    "trigger-bad-1",
                    "pitch_outcome",
                    "auto"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void retraining_queue_status_rejects_garbage() {
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type, status)"
                        + " VALUES (?, ?, ?, ?)",
                    "trigger-bad-2",
                    "pitch_outcome",
                    "manual",
                    "pending"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void retraining_queue_trigger_id_must_be_unique() {
    jdbc.update(
        "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type) VALUES (?, ?, ?)",
        "trigger-uniq-1",
        "pitch_outcome",
        "manual");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type)"
                        + " VALUES (?, ?, ?)",
                    "trigger-uniq-1",
                    "pitch_outcome",
                    "manual"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void retraining_queue_defaults_to_queued_status() {
    jdbc.update(
        "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type) VALUES (?, ?, ?)",
        "trigger-default-1",
        "pitch_outcome",
        "scheduled");
    String status =
        jdbc.queryForObject(
            "SELECT status FROM retraining_queue WHERE trigger_id = 'trigger-default-1'",
            String.class);
    assertThat(status).isEqualTo("queued");
  }

  // --- helpers -------------------------------------------------------------

  private long insertModelVersion(String modelName, String version, String stage) {
    jdbc.update(
        INSERT_MODEL_VERSION,
        modelName,
        version,
        "/snapshots/" + version + "/model.onnx",
        "/snapshots/" + version + "/metadata.json",
        "hash-" + version,
        "[2024-01-01,2024-12-31]",
        "feature-hash-" + version,
        "{}",
        Timestamp.from(Instant.now()),
        stage);
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
