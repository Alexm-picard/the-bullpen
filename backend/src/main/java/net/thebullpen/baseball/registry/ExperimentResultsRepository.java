package net.thebullpen.baseball.registry;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.dto.ExperimentResult.Status;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate access to {@code experiment_results} — backs the rule-5 promotion gate enforced by
 * {@link RegistryService#transitionStage(long, net.thebullpen.baseball.registry.dto.Stage)} for any
 * {@code -> CHAMPION} transition. Writes are intentionally out of scope here in 3a.4: the 3c drift
 * job is what flips a row from RUNNING to PASSED / FAILED. This repository only needs to answer "is
 * there a passing row for this challenger?"
 */
@Repository
public class ExperimentResultsRepository {

  private static final String SELECT_ALL_COLUMNS =
      "SELECT id, model_name, champion_version_id, challenger_version_id, started_at, ended_at,"
          + " primary_metric, primary_threshold, guardrails, sample_size_target,"
          + " sample_size_observed, champion_metric, challenger_metric, guardrails_observed,"
          + " status, notes, created_at FROM experiment_results";

  private final JdbcTemplate jdbc;

  public ExperimentResultsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Latest {@link Status#PASSED} row for the given {@code (modelName, challengerVersionId)} — empty
   * if no such row exists yet. Ordered by {@code ended_at DESC, id DESC} so the freshest pass wins
   * (matters when the same challenger has been re-evaluated multiple times against different
   * champions over its lifetime).
   */
  public Optional<ExperimentResult> findLatestPassing(String modelName, long challengerVersionId) {
    try {
      ExperimentResult row =
          jdbc.queryForObject(
              SELECT_ALL_COLUMNS
                  + " WHERE model_name = ? AND challenger_version_id = ? AND status = 'passed'"
                  + " ORDER BY ended_at DESC, id DESC LIMIT 1",
              EXPERIMENT_MAPPER,
              modelName,
              challengerVersionId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<ExperimentResult> findById(long id) {
    try {
      ExperimentResult row =
          jdbc.queryForObject(SELECT_ALL_COLUMNS + " WHERE id = ?", EXPERIMENT_MAPPER, id);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** All experiment rows for one model, newest-first by {@code created_at}. */
  public List<ExperimentResult> findByModel(String modelName) {
    return jdbc.query(
        SELECT_ALL_COLUMNS + " WHERE model_name = ? ORDER BY created_at DESC, id DESC",
        EXPERIMENT_MAPPER,
        modelName);
  }

  // --- mapping ------------------------------------------------------------

  private static final RowMapper<ExperimentResult> EXPERIMENT_MAPPER =
      (ResultSet rs, int rowNum) ->
          new ExperimentResult(
              rs.getLong("id"),
              rs.getString("model_name"),
              rs.getLong("champion_version_id"),
              rs.getLong("challenger_version_id"),
              toInstant(rs.getTimestamp("started_at")),
              toInstant(rs.getTimestamp("ended_at")),
              rs.getString("primary_metric"),
              rs.getDouble("primary_threshold"),
              rs.getString("guardrails"),
              rs.getLong("sample_size_target"),
              getNullableLong(rs, "sample_size_observed"),
              getNullableDouble(rs, "champion_metric"),
              getNullableDouble(rs, "challenger_metric"),
              rs.getString("guardrails_observed"),
              Status.fromDbValue(rs.getString("status")),
              rs.getString("notes"),
              toInstant(rs.getTimestamp("created_at")));

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private static Long getNullableLong(ResultSet rs, String col) throws java.sql.SQLException {
    long v = rs.getLong(col);
    return rs.wasNull() ? null : v;
  }

  private static Double getNullableDouble(ResultSet rs, String col) throws java.sql.SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }
}
