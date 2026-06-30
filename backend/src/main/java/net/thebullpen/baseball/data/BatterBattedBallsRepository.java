package net.thebullpen.baseball.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.BattedBallRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads a batter's in-play batted balls (Phase 2.2/2.3) from {@code pitches} ({@code description =
 * 'in_play'}), newest first, with optional filters: hit type ({@code bb_type}), at-bat result
 * ({@code events}, e.g. {@code home_run} for the all-HRs view), and a {@code [from, to]} game-date
 * range. Backs the filterable hitter view + the per-batter HR list.
 *
 * <p>Same posture as {@link PitcherArsenalRepository}: no {@code FINAL} (rare duplicate pitches do
 * not change a hitter's batted-ball history materially), {@code api} profile, gated on {@code
 * bullpen.clickhouse.enabled}.
 */
@Repository
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class BatterBattedBallsRepository {

  private static final RowMapper<BattedBallRow> MAPPER =
      (rs, i) ->
          new BattedBallRow(
              rs.getString("game_date"),
              rs.getString("events"),
              rs.getString("bb_type"),
              nullableDouble(rs, "launch_speed_mph"),
              nullableDouble(rs, "launch_angle_deg"),
              nullableDouble(rs, "hit_distance_ft"),
              rs.getString("park_id"),
              rs.getString("stand"));

  private final JdbcTemplate jdbc;

  public BatterBattedBallsRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * In-play batted balls for a batter, newest first. {@code bbType} / {@code event} / {@code from}
   * / {@code to} are each optional (null/blank = no filter). {@code limit} is a pre-validated bound
   * inlined into the SQL (not a placeholder) because clickhouse-jdbc does not parameterize {@code
   * LIMIT}; the controller clamps it. The {@code description = 'in_play'} literal leads the WHERE,
   * before any {@code ?}, so no literal sits among the bound placeholders (a clickhouse-jdbc
   * gotcha).
   */
  public List<BattedBallRow> findBattedBalls(
      long batterId, String bbType, String event, LocalDate from, LocalDate to, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT toString(game_date) AS game_date, events AS events, bb_type AS bb_type,"
                + " launch_speed_mph AS launch_speed_mph, launch_angle_deg AS launch_angle_deg,"
                + " hit_distance_ft AS hit_distance_ft, park_id AS park_id,"
                + " toString(stand) AS stand"
                + " FROM pitches"
                + " WHERE description = 'in_play' AND batter_id = ?");
    List<Object> args = new ArrayList<>();
    args.add(batterId);
    if (bbType != null && !bbType.isBlank()) {
      sql.append(" AND bb_type = ?");
      args.add(bbType);
    }
    if (event != null && !event.isBlank()) {
      sql.append(" AND events = ?");
      args.add(event);
    }
    // Date filters are INLINED as toDate('yyyy-MM-dd') literals, not bound placeholders: a date
    // PARAMETER trips clickhouse-jdbc with DB::Exception 386 NO_COMMON_TYPE against the Date column
    // however it is typed (observed: java.sql.Date -> "String, Int64"; a String/Long via toDate(?)
    // -> "String, Date"). Inlining is the same clickhouse-jdbc workaround already used for LIMIT
    // below, and is injection-safe: a java.time.LocalDate renders only as ISO [0-9-] via
    // toString(),
    // so the literal can never carry a quote or SQL. With no date placeholder the bound-param
    // profile matches the (passing) no-filter call.
    if (from != null) {
      sql.append(" AND game_date >= toDate('").append(from).append("')");
    }
    if (to != null) {
      sql.append(" AND game_date <= toDate('").append(to).append("')");
    }
    sql.append(
            " ORDER BY game_date DESC, game_id DESC, at_bat_index DESC, pitch_number DESC LIMIT ")
        .append(limit);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }
}
