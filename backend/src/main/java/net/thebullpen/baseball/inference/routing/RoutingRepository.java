package net.thebullpen.baseball.inference.routing;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.data.JdbcTimes;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate access to {@code model_routing} (V011). One row per model_name (UNIQUE on the table
 * — the upsert here mirrors that). Writes always bump {@code updated_at} so the cache eviction in
 * {@link RoutingService} pairs with a fresh persisted timestamp the next reader picks up.
 */
@Repository
public class RoutingRepository {

  /**
   * One routing row paired with its champion's stage, for the task-#94 integrity scan. {@code
   * stage} is the literal found ({@code 'champion'} for a healthy row), or {@code "<no matching
   * model_versions row>"} when the reference is dangling OR points at a version of a different
   * model (the scan's JOIN binds model_name - rule 9).
   */
  public record ChampionStageRow(String modelName, long championVersionId, String stage) {

    /** The invariant: a routing row may only reference a {@code 'champion'}-stage version. */
    public boolean violates() {
      return !"champion".equals(stage);
    }
  }

  private static final String SELECT_ALL_COLUMNS =
      "SELECT id, model_name, champion_version_id, challenger_version_id,"
          + " challenger_traffic_pct, mode, updated_at FROM model_routing";

  private static final RowMapper<ChampionStageRow> CHAMPION_STAGE_MAPPER =
      (ResultSet rs, int rowNum) -> {
        String stage = rs.getString("stage");
        return new ChampionStageRow(
            rs.getString("model_name"),
            rs.getLong("champion_version_id"),
            stage == null ? "<no matching model_versions row>" : stage);
      };

  private final JdbcTemplate jdbc;

  public RoutingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<RoutingConfig> findByModelName(String modelName) {
    try {
      RoutingConfig row =
          jdbc.queryForObject(
              SELECT_ALL_COLUMNS + " WHERE model_name = ?", ROUTING_MAPPER, modelName);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public List<RoutingConfig> findAll() {
    return jdbc.query(SELECT_ALL_COLUMNS + " ORDER BY model_name", ROUTING_MAPPER);
  }

  /**
   * The read behind {@link RoutingChampionIntegrityCheck}: EVERY routing row joined to its
   * champion's stage, violators and passers alike, so the caller's checked-count and its violation
   * scan come from the same read (a WHERE-filtered query would force a second count query that
   * could see a different table state). LEFT JOIN so a dangling reference surfaces as a NULL stage
   * instead of the row silently vanishing from the scan - should be impossible with {@code
   * foreign_keys=true} plus the V020 insert trigger, but the check is total rather than trusting
   * that both survived every connection.
   */
  public List<ChampionStageRow> findChampionStageRows() {
    // The JOIN binds model_name as well as id (rule 9): a routing row referencing another model's
    // champion must read as "no matching version" (NULL stage -> violation), not as healthy.
    return jdbc.query(
        "SELECT r.model_name, r.champion_version_id, v.stage"
            + " FROM model_routing r"
            + " LEFT JOIN model_versions v"
            + " ON v.id = r.champion_version_id AND v.model_name = r.model_name",
        CHAMPION_STAGE_MAPPER);
  }

  /**
   * Delete the routing row for {@code modelName}. Used by the champion-rollback path (INC-1):
   * {@code champion_version_id} is NOT NULL, so a demoted champion's row can't be emptied - it must
   * be removed, leaving no routing row -> {@code InferenceRouter} finds none -> legacy fallback.
   * Returns the number of rows deleted (0 or 1, since {@code model_name} is UNIQUE).
   */
  public int deleteByModelName(String modelName) {
    return jdbc.update("DELETE FROM model_routing WHERE model_name = ?", modelName);
  }

  /**
   * Insert-or-update the routing row for {@code modelName}. SQLite supports {@code INSERT ... ON
   * CONFLICT (...) DO UPDATE SET ...} which mirrors the UNIQUE constraint on {@code model_name} —
   * single SQL statement, atomic against concurrent writes (though concurrent admin writes are not
   * a real scenario for this project, the discipline is free).
   *
   * <p>Returns the persisted row by reading it back — the trigger-style {@code updated_at} default
   * fires inside the same statement so the returned record carries the fresh timestamp.
   */
  public RoutingConfig upsert(
      String modelName,
      long championVersionId,
      Long challengerVersionId,
      double challengerTrafficPct,
      RoutingMode mode) {
    jdbc.update(
        "INSERT INTO model_routing (model_name, champion_version_id, challenger_version_id,"
            + " challenger_traffic_pct, mode, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            + " ON CONFLICT(model_name) DO UPDATE SET"
            + " champion_version_id = excluded.champion_version_id,"
            + " challenger_version_id = excluded.challenger_version_id,"
            + " challenger_traffic_pct = excluded.challenger_traffic_pct,"
            + " mode = excluded.mode,"
            + " updated_at = CURRENT_TIMESTAMP",
        modelName,
        championVersionId,
        challengerVersionId,
        challengerTrafficPct,
        mode.dbValue());
    return findByModelName(modelName)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "model_routing row vanished immediately after upsert: " + modelName));
  }

  // --- mapping ----------------------------------------------------------

  private static final RowMapper<RoutingConfig> ROUTING_MAPPER =
      (ResultSet rs, int rowNum) -> {
        long challengerRaw = rs.getLong("challenger_version_id");
        Long challengerId = rs.wasNull() ? null : challengerRaw;
        return new RoutingConfig(
            rs.getLong("id"),
            rs.getString("model_name"),
            rs.getLong("champion_version_id"),
            challengerId,
            rs.getDouble("challenger_traffic_pct"),
            RoutingMode.fromDbValue(rs.getString("mode")),
            // updated_at is UTC (CURRENT_TIMESTAMP); read tz-explicitly so the box's ET JVM does
            // not shift it +4h.
            JdbcTimes.utcInstant(rs, "updated_at"));
      };
}
