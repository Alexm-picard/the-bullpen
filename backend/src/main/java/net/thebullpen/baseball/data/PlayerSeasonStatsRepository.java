package net.thebullpen.baseball.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.thebullpen.baseball.ingest.PlayerSeasonStat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read/write for {@code player_season_stats} (V021) - the matchup classification's quality source.
 * The ~3:45 ET morning job upserts ERA (pitchers) + computed wOBA (hitters); the classification
 * reads them back per probable pitcher / lineup hitter. Same gating as {@link
 * LivePitchesRepository}: ClickHouse-only, on the api + worker profiles.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnBean(name = "clickhouseDataSource")
public class PlayerSeasonStatsRepository {

  private static final String INSERT =
      "INSERT INTO player_season_stats"
          + " (player_id, season, stat_group, era, woba, sample) VALUES (?,?,?,?,?,?)";

  // Latest row per (player, season, group) - ReplacingMergeTree FINAL - for the requested ids.
  private static final String SELECT_FOR_IDS =
      "SELECT player_id, season, stat_group, era, woba, sample"
          + " FROM player_season_stats FINAL"
          + " WHERE season = ? AND player_id IN (%s)";

  private final JdbcTemplate jdbc;

  public PlayerSeasonStatsRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Upsert a batch of season stats; ReplacingMergeTree dedups re-writes on the next merge/FINAL.
   */
  public void upsert(List<PlayerSeasonStat> stats) {
    if (stats.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        INSERT,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            PlayerSeasonStat s = stats.get(i);
            ps.setLong(1, s.playerId());
            ps.setInt(2, s.season());
            ps.setString(3, s.statGroup());
            setNullableDouble(ps, 4, s.era());
            setNullableDouble(ps, 5, s.woba());
            if (s.sample() == null) {
              ps.setNull(6, Types.INTEGER);
            } else {
              ps.setInt(6, s.sample());
            }
          }

          @Override
          public int getBatchSize() {
            return stats.size();
          }
        });
  }

  /** Latest season stats (both groups) for the requested player ids. */
  public List<PlayerSeasonStat> findForPlayers(Collection<Long> playerIds, int season) {
    if (playerIds.isEmpty()) {
      return List.of();
    }
    // ids are longs we control (not user input) - safe to inline; the season is a bound param.
    String ids = playerIds.stream().map(String::valueOf).collect(Collectors.joining(","));
    return jdbc.query(String.format(SELECT_FOR_IDS, ids), MAPPER, season);
  }

  private static void setNullableDouble(PreparedStatement ps, int idx, Double v)
      throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.DOUBLE);
    } else {
      ps.setDouble(idx, v);
    }
  }

  private static final RowMapper<PlayerSeasonStat> MAPPER =
      (ResultSet rs, int n) ->
          new PlayerSeasonStat(
              rs.getLong("player_id"),
              rs.getInt("season"),
              rs.getString("stat_group"),
              nullableDouble(rs, "era"),
              nullableDouble(rs, "woba"),
              nullableInt(rs, "sample"));

  private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }

  private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }
}
