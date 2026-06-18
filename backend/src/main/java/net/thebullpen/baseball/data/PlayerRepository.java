package net.thebullpen.baseball.data;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.PlayerSearchResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reader over the {@code players} ClickHouse table (V014, leaf 4b.1) for the player autocomplete on
 * {@code GET /v1/players/search}.
 *
 * <p>Two ways to match:
 *
 * <ul>
 *   <li>If {@code q} parses as an integer, treat it as a Statcast ID prefix — `LIKE 'q%'` against
 *       {@code toString(id)}. A recruiter pasting "660271" lands on Aaron Judge directly.
 *   <li>Otherwise, case-insensitive substring against {@code name} (ClickHouse {@code ilike}).
 * </ul>
 *
 * <p>Reads use {@code SELECT ... FINAL} so the most-recent ReplacingMergeTree row wins — the
 * nightly roster pull re-writes rows on the same {@code id} and we want fresh values.
 *
 * <p>Active on {@code api} only; {@link ConditionalOnBean} keeps the controller out when ClickHouse
 * isn't wired (dev without docker-compose). Searches against an empty / missing table return an
 * empty list — the frontend renders "no results" rather than erroring.
 */
@Repository
@Profile("api")
@ConditionalOnBean(name = "clickhouseDataSource")
public class PlayerRepository {

  private static final String SEARCH_BY_NAME =
      "SELECT id, name, primary_position, active, team FROM players FINAL"
          + " WHERE positionCaseInsensitive(name, ?) > 0"
          + " ORDER BY active DESC, name ASC"
          + " LIMIT ?";

  private static final String SEARCH_BY_ID_PREFIX =
      "SELECT id, name, primary_position, active, team FROM players FINAL"
          + " WHERE startsWith(toString(id), ?)"
          + " ORDER BY active DESC, id ASC"
          + " LIMIT ?";

  private static final String FIND_BY_ID =
      "SELECT id, name, primary_position, active, team FROM players FINAL WHERE id = ?";

  private static final RowMapper<PlayerSearchResult> MAPPER =
      (ResultSet rs, int n) ->
          new PlayerSearchResult(
              rs.getLong("id"),
              rs.getString("name"),
              rs.getString("primary_position").trim(),
              rs.getInt("active") == 1,
              rs.getString("team").trim());

  private final JdbcTemplate jdbc;

  public PlayerRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /** Up to {@code limit} matches against {@code q} (≥1 char). Empty list if no hits. */
  public List<PlayerSearchResult> search(String q, int limit) {
    String trimmed = q == null ? "" : q.trim();
    if (trimmed.isEmpty()) {
      return List.of();
    }
    String sql = looksLikeId(trimmed) ? SEARCH_BY_ID_PREFIX : SEARCH_BY_NAME;
    return jdbc.query(sql, MAPPER, trimmed, limit);
  }

  /** Single row by id — empty when not found. Used by leaf 4b.2's profile page. */
  public java.util.Optional<PlayerSearchResult> findById(long id) {
    List<PlayerSearchResult> hits = jdbc.query(FIND_BY_ID, MAPPER, id);
    return hits.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(hits.get(0));
  }

  /**
   * Active players filtered by {@code team} and/or {@code position} (the Browse surface). Either
   * filter may be blank to mean "no constraint"; both blank returns the active roster capped by
   * {@code limit}. {@code position} matches the MLB primary abbreviation (C, 1B, SS, P, ...) -
   * {@code primary_position} is FixedString(2) which compares zero-padded, so "P" matches the
   * stored "P\0". Empty list when nothing matches (the UI renders "no players").
   */
  public List<PlayerSearchResult> roster(String team, String position, int limit) {
    String t = team == null ? "" : team.trim();
    String p = position == null ? "" : position.trim();
    StringBuilder sql =
        new StringBuilder(
            "SELECT id, name, primary_position, active, team FROM players FINAL WHERE active = 1");
    List<Object> args = new ArrayList<>();
    if (!t.isEmpty()) {
      sql.append(" AND team = ?");
      args.add(t);
    }
    if (!p.isEmpty()) {
      sql.append(" AND primary_position = ?");
      args.add(p);
    }
    sql.append(" ORDER BY name ASC LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), MAPPER, args.toArray());
  }

  private static boolean looksLikeId(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
