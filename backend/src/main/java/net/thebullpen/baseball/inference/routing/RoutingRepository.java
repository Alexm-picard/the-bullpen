package net.thebullpen.baseball.inference.routing;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

  private static final String SELECT_ALL_COLUMNS =
      "SELECT id, model_name, champion_version_id, challenger_version_id,"
          + " challenger_traffic_pct, mode, updated_at FROM model_routing";

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
            toInstant(rs.getTimestamp("updated_at")));
      };

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
