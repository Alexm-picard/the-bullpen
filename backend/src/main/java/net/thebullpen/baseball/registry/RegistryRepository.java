package net.thebullpen.baseball.registry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate access to {@code model_versions} + {@code model_routing}. Lives in the registry
 * package (not {@code data/}) because it's a single-table aggregate and the CLAUDE.md "five
 * explicit aggregates under data/" formalisation lands in Phase 3 hardening — registry stays
 * package-local until then.
 *
 * <p>Pure CRUD; the service ({@link RegistryService}) owns validation + state-machine + atomicity.
 */
@Repository
public class RegistryRepository {

  private static final String INSERT_MODEL_VERSION =
      """
      INSERT INTO model_versions (model_name, version, artifact_path, metadata_path,
          training_data_hash, training_data_window, feature_schema_hash, eval_metrics,
          trained_at, promoted_at, stage, created_by, notes)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String SELECT_ALL_COLUMNS =
      "SELECT id, model_name, version, artifact_path, metadata_path, training_data_hash,"
          + " training_data_window, feature_schema_hash, eval_metrics, trained_at, promoted_at,"
          + " stage, created_by, notes, created_at, updated_at FROM model_versions";

  private final JdbcTemplate jdbc;

  public RegistryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // --- writes -------------------------------------------------------------

  /** Insert a candidate row. Returns the row as persisted (with id + timestamps populated). */
  public ModelVersion insert(
      String modelName,
      String version,
      String artifactPath,
      String metadataPath,
      String trainingDataHash,
      String trainingDataWindow,
      String featureSchemaHash,
      String evalMetricsJson,
      Instant trainedAt,
      Stage stage,
      String createdBy,
      String notes) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement ps =
              connection.prepareStatement(INSERT_MODEL_VERSION, Statement.RETURN_GENERATED_KEYS);
          ps.setString(1, modelName);
          ps.setString(2, version);
          ps.setString(3, artifactPath);
          ps.setString(4, metadataPath);
          ps.setString(5, trainingDataHash);
          ps.setString(6, trainingDataWindow);
          ps.setString(7, featureSchemaHash);
          ps.setString(8, evalMetricsJson);
          ps.setTimestamp(9, Timestamp.from(trainedAt));
          ps.setTimestamp(10, null); // promoted_at unset on insert
          ps.setString(11, stage.dbValue());
          ps.setString(12, createdBy);
          ps.setString(13, notes);
          return ps;
        },
        keyHolder);
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("INSERT into model_versions returned no generated key");
    }
    return findById(key.longValue())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "model_versions row vanished immediately after insert: id=" + key));
  }

  /**
   * Update the stage of a single row. Bumps {@code updated_at} unconditionally; sets {@code
   * promoted_at = CURRENT_TIMESTAMP} only on the first transition into SHADOW or CHAMPION
   * (idempotent — repeated promotions don't move the original promoted_at).
   */
  public int updateStage(long id, Stage newStage) {
    boolean isPromotion = newStage == Stage.SHADOW || newStage == Stage.CHAMPION;
    String sql =
        isPromotion
            ? "UPDATE model_versions SET stage = ?, updated_at = CURRENT_TIMESTAMP,"
                + " promoted_at = COALESCE(promoted_at, CURRENT_TIMESTAMP) WHERE id = ?"
            : "UPDATE model_versions SET stage = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
    return jdbc.update(sql, newStage.dbValue(), id);
  }

  // --- reads --------------------------------------------------------------

  public Optional<ModelVersion> findById(long id) {
    try {
      ModelVersion mv =
          jdbc.queryForObject(SELECT_ALL_COLUMNS + " WHERE id = ?", MODEL_VERSION_MAPPER, id);
      return Optional.ofNullable(mv);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<ModelVersion> findByNameAndVersion(String modelName, String version) {
    try {
      ModelVersion mv =
          jdbc.queryForObject(
              SELECT_ALL_COLUMNS + " WHERE model_name = ? AND version = ?",
              MODEL_VERSION_MAPPER,
              modelName,
              version);
      return Optional.ofNullable(mv);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /**
   * Every registered version of one model, newest-first by {@code created_at}. Includes archived
   * rows — callers that want only live versions filter with {@code WHERE stage != 'archived'}.
   */
  public List<ModelVersion> findByName(String modelName) {
    // id DESC as tiebreaker — SQLite's CURRENT_TIMESTAMP has 1-second resolution, so two
    // back-to-back inserts share created_at and ORDER BY alone wouldn't define the order.
    // id is monotonic via AUTOINCREMENT, so it's the right newest-first tiebreaker.
    return jdbc.query(
        SELECT_ALL_COLUMNS + " WHERE model_name = ? ORDER BY created_at DESC, id DESC",
        MODEL_VERSION_MAPPER,
        modelName);
  }

  public Optional<ModelVersion> findChampion(String modelName) {
    return findByNameAndStage(modelName, Stage.CHAMPION);
  }

  public Optional<ModelVersion> findChallenger(String modelName) {
    return findByNameAndStage(modelName, Stage.SHADOW);
  }

  private Optional<ModelVersion> findByNameAndStage(String modelName, Stage stage) {
    // The (model_name, stage) composite index from V010's idx_mv_model_stage carries this query;
    // there should be at most one row per (model_name, stage) for SHADOW + CHAMPION (enforced by
    // RegistryService.transitionStage, not by a DB constraint).
    List<ModelVersion> hits =
        jdbc.query(
            SELECT_ALL_COLUMNS + " WHERE model_name = ? AND stage = ?",
            MODEL_VERSION_MAPPER,
            modelName,
            stage.dbValue());
    if (hits.size() > 1) {
      throw new IllegalStateException(
          "registry invariant violation: "
              + hits.size()
              + " rows at stage "
              + stage
              + " for model "
              + modelName
              + " (should be at most 1)");
    }
    return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
  }

  // --- mapping ------------------------------------------------------------

  private static final RowMapper<ModelVersion> MODEL_VERSION_MAPPER =
      (ResultSet rs, int rowNum) ->
          new ModelVersion(
              rs.getLong("id"),
              rs.getString("model_name"),
              rs.getString("version"),
              rs.getString("artifact_path"),
              rs.getString("metadata_path"),
              rs.getString("training_data_hash"),
              rs.getString("training_data_window"),
              rs.getString("feature_schema_hash"),
              rs.getString("eval_metrics"),
              toInstant(rs.getTimestamp("trained_at")),
              toInstant(rs.getTimestamp("promoted_at")),
              Stage.fromDbValue(rs.getString("stage")),
              rs.getString("created_by"),
              rs.getString("notes"),
              toInstant(rs.getTimestamp("created_at")),
              toInstant(rs.getTimestamp("updated_at")));

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
