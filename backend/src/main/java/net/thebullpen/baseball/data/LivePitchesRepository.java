package net.thebullpen.baseball.data;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reader + writer for {@code pitches_live} (V015) — backs leaf 4d.1's three game endpoints.
 *
 * <p>The {@code cursor} field is derived as {@code at_bat_index * 100 + pitch_number} so the
 * frontend's "give me pitches added since cursor X" query is a single inequality on a computed
 * column. The 100-factor is safe: MLB at-bats never exceed 30 pitches, so collisions across at-bats
 * are impossible.
 *
 * <p>Active on both {@code api} (reads serve the game page) and {@code worker} (writes happen
 * inside the LivePollingService — to be wired in a follow-up leaf). When ClickHouse isn't available
 * in dev, {@link ConditionalOnBean} keeps the bean out entirely, and the game controller goes with
 * it.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnBean(name = "clickhouseDataSource")
public class LivePitchesRepository {

  private static final String SELECT_PITCH_COLS =
      "SELECT game_id, at_bat_index, pitch_number,"
          + " (at_bat_index * 100 + pitch_number) AS cursor,"
          + " ingested_at, pitcher_id, batter_id, description, pitch_type,"
          + " release_speed_mph, plate_x_in, plate_z_in,"
          + " balls, strikes, outs, inning, home_score, away_score";

  private static final String FIND_PITCHES_SINCE =
      SELECT_PITCH_COLS
          + " FROM pitches_live FINAL"
          + " WHERE game_id = ? AND (at_bat_index * 100 + pitch_number) > ?"
          + " ORDER BY cursor ASC LIMIT 500";

  private static final String FIND_GAMES_FOR_DATE =
      "SELECT game_id, game_date, home_team, away_team,"
          + " max(home_score) AS home_score, max(away_score) AS away_score,"
          + " max(inning) AS inning"
          + " FROM pitches_live FINAL"
          // toDate(?) + a String 'yyyy-MM-dd' param (see findGamesForDate): clickhouse-jdbc 0.7.2
          // inlines a bare java.sql.Date as the unquoted token 2026-06-05, which ClickHouse parses
          // as arithmetic (2026-6-5 = 2015, Int64) -> "Date = Int64" type error. A String param is
          // rendered quoted, so toDate('2026-06-05') yields the right Date.
          + " WHERE game_date = toDate(?)"
          + " GROUP BY game_id, game_date, home_team, away_team"
          + " ORDER BY game_id ASC";

  private static final String FIND_GAME =
      "SELECT game_id, game_date, home_team, away_team,"
          + " max(home_score) AS home_score, max(away_score) AS away_score,"
          + " max(inning) AS inning"
          + " FROM pitches_live FINAL"
          + " WHERE game_id = ?"
          + " GROUP BY game_id, game_date, home_team, away_team";

  private final JdbcTemplate jdbc;

  public LivePitchesRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  public List<LivePitchRow> findPitchesSince(long gameId, long sinceCursor) {
    return jdbc.query(FIND_PITCHES_SINCE, PITCH_MAPPER, gameId, sinceCursor);
  }

  /**
   * Today's games visible in pitches_live. Status is always {@code UNKNOWN} from this read path —
   * the worker decorates summary rows with status when it lands. Until then, the UI shows "UNKNOWN"
   * which is the honest answer.
   */
  public List<GameSummary> findGamesForDate(LocalDate date) {
    return jdbc.query(
        FIND_GAMES_FOR_DATE,
        (ResultSet rs, int n) ->
            new GameSummary(
                rs.getLong("game_id"),
                rs.getDate("game_date").toLocalDate(),
                rs.getString("home_team"),
                rs.getString("away_team"),
                rs.getInt("home_score"),
                rs.getInt("away_score"),
                rs.getInt("inning"),
                "UNKNOWN",
                "Unknown"),
        // ISO-8601 'yyyy-MM-dd' String, not java.sql.Date; see FIND_GAMES_FOR_DATE.
        date.toString());
  }

  public java.util.Optional<GameSummary> findGame(long gameId) {
    List<GameSummary> hits =
        jdbc.query(
            FIND_GAME,
            (ResultSet rs, int n) ->
                new GameSummary(
                    rs.getLong("game_id"),
                    rs.getDate("game_date").toLocalDate(),
                    rs.getString("home_team"),
                    rs.getString("away_team"),
                    rs.getInt("home_score"),
                    rs.getInt("away_score"),
                    rs.getInt("inning"),
                    "UNKNOWN",
                    "Unknown"),
            gameId);
    return hits.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(hits.get(0));
  }

  private static final RowMapper<LivePitchRow> PITCH_MAPPER =
      (ResultSet rs, int n) -> {
        Timestamp ts = rs.getTimestamp("ingested_at");
        return new LivePitchRow(
            rs.getLong("game_id"),
            rs.getInt("at_bat_index"),
            rs.getInt("pitch_number"),
            rs.getLong("cursor"),
            ts == null ? null : ts.toInstant(),
            rs.getLong("pitcher_id"),
            rs.getLong("batter_id"),
            rs.getString("description"),
            rs.getString("pitch_type"),
            nullable(rs, "release_speed_mph"),
            nullable(rs, "plate_x_in"),
            nullable(rs, "plate_z_in"),
            rs.getInt("balls"),
            rs.getInt("strikes"),
            rs.getInt("outs"),
            rs.getInt("inning"),
            rs.getInt("home_score"),
            rs.getInt("away_score"),
            // 4d.2 leaves prediction columns null today; truth-join lands when
            // prediction_log carries traffic keyed on (game_id, at_bat_index, pitch_number).
            null,
            null);
      };

  private static Double nullable(ResultSet rs, String col) throws java.sql.SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }
}
