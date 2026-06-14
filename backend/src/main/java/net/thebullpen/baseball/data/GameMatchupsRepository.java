package net.thebullpen.baseball.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.GameMatchup;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read/write for {@code game_matchups} (V022) - the computed per-game matchup the home Featured
 * panel + Tonight's board read. The matchup jobs upsert (morning default, then lineup-aware
 * re-classification); the {@code /v1/matchups} endpoint reads {@link #findForDate} (ordered best
 * battle first). Same gating as {@link LivePitchesRepository}.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnBean(name = "clickhouseDataSource")
public class GameMatchupsRepository {

  private static final String INSERT =
      "INSERT INTO game_matchups"
          + " (game_id, game_date, lean, home_player_id, home_player_name, home_role,"
          + " away_player_id, away_player_name, away_role, battle_score, stage)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?)";

  // Latest per game (ReplacingMergeTree FINAL), best battle first - the Featured panel takes row 0.
  private static final String FIND_FOR_DATE =
      "SELECT game_id, game_date, lean, home_player_id, home_player_name, home_role,"
          + " away_player_id, away_player_name, away_role, battle_score, stage"
          + " FROM game_matchups FINAL WHERE game_date = toDate(?)"
          + " ORDER BY battle_score DESC, game_id ASC";

  private final JdbcTemplate jdbc;

  public GameMatchupsRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  public void upsert(List<GameMatchup> matchups) {
    if (matchups.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(
        INSERT,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            GameMatchup m = matchups.get(i);
            ps.setLong(1, m.gameId());
            ps.setString(2, m.gameDate().toString());
            ps.setString(3, m.lean());
            ps.setLong(4, m.homePlayerId());
            ps.setString(5, m.homePlayerName());
            ps.setString(6, m.homeRole());
            ps.setLong(7, m.awayPlayerId());
            ps.setString(8, m.awayPlayerName());
            ps.setString(9, m.awayRole());
            ps.setDouble(10, m.battleScore());
            ps.setString(11, m.stage());
          }

          @Override
          public int getBatchSize() {
            return matchups.size();
          }
        });
  }

  /** The day's matchups, best battle first (row 0 is the Featured panel's pick). */
  public List<GameMatchup> findForDate(LocalDate date) {
    return jdbc.query(FIND_FOR_DATE, MAPPER, date.toString());
  }

  private static final RowMapper<GameMatchup> MAPPER =
      (ResultSet rs, int n) ->
          new GameMatchup(
              rs.getLong("game_id"),
              rs.getDate("game_date").toLocalDate(),
              rs.getString("lean"),
              rs.getLong("home_player_id"),
              rs.getString("home_player_name"),
              rs.getString("home_role"),
              rs.getLong("away_player_id"),
              rs.getString("away_player_name"),
              rs.getString("away_role"),
              rs.getDouble("battle_score"),
              rs.getString("stage"));
}
