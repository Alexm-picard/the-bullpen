package net.thebullpen.baseball.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import net.thebullpen.baseball.ingest.LiveGameFeed;
import net.thebullpen.baseball.ingest.LivePitch;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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

  private static final String FIND_PITCHES_SINCE =
      "SELECT pl.game_id AS game_id, pl.at_bat_index AS at_bat_index,"
          + " pl.pitch_number AS pitch_number,"
          + " (pl.at_bat_index * 100 + pl.pitch_number) AS cursor,"
          + " pl.ingested_at AS ingested_at, pl.pitcher_id AS pitcher_id,"
          + " pl.batter_id AS batter_id, pl.description AS description,"
          + " pl.pitch_type AS pitch_type, pl.release_speed_mph AS release_speed_mph,"
          + " pl.plate_x_in AS plate_x_in, pl.plate_z_in AS plate_z_in,"
          + " pl.balls AS balls, pl.strikes AS strikes, pl.outs AS outs,"
          + " pl.inning AS inning, pl.home_score AS home_score, pl.away_score AS away_score,"
          + " pred.prediction AS prediction_json"
          + " FROM pitches_live AS pl FINAL"
          // One champion prediction per pitch: predict-next re-logs the same upcoming pitch on
          // every poll (decision [143]), so collapse to the latest by request_at. NULL-keyed
          // HTTP-path rows never match (game_id IS NULL after the equality). Unmatched pitches get
          // '' from the LEFT JOIN -> null predictions in the mapper (the frontend's "n/a" path).
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(prediction, request_at) AS prediction"
          + "   FROM prediction_log"
          + "   WHERE game_id = ? AND role = 'champion'"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pred"
          + " ON pred.game_id = pl.game_id AND pred.at_bat_index = pl.at_bat_index"
          + "    AND pred.pitch_number = pl.pitch_number"
          + " WHERE pl.game_id = ? AND (pl.at_bat_index * 100 + pl.pitch_number) > ?"
          + " ORDER BY cursor ASC LIMIT 500";

  // Latest status per game from the poller (step 7b). argMax over the ReplacingMergeTree gives the
  // current status; a LEFT JOIN miss yields '' -> 'UNKNOWN' (the honest pre-poll answer).
  private static final String LATEST_STATUS_SUBQUERY =
      " LEFT JOIN ( SELECT game_id, argMax(status, updated_at) AS status"
          + " FROM live_game_status GROUP BY game_id ) AS s ON s.game_id = g.game_id";

  // The slate UNIONs both sources so a game appears whether it is pre-game (in scheduled_games,
  // persisted by the poller's schedule refresh before first pitch) OR already throwing pitches (in
  // pitches_live). The old query was pitches_live-driven, so a game was invisible until ~first
  // pitch (~11:30+), never at its scheduled time. Now: union the game_ids, then LEFT JOIN both for
  // detail - team abbreviation/score/inning come from pitches_live when live, else the schedule's
  // abbreviation then full name; status from live_game_status, else the schedule's SCHEDULED, else
  // UNKNOWN; game_date is whichever side matched (greatest skips the 1970 default of the unmatched
  // side). The invariant that matters: a live game with pitches NEVER vanishes from the slate.
  // toDate(?) takes a String 'yyyy-MM-dd' (a bare java.sql.Date inlines as arithmetic) - bound four
  // times here, once per dated subquery (the ids union x2, sg, p).
  private static final String SLATE_DETAIL_SELECT =
      "SELECT ids.game_id AS game_id, greatest(sg.game_date, p.game_date) AS game_date,"
          + " if(p.home_team != '', p.home_team,"
          + "    if(sg.home_team != '', sg.home_team, sg.home_name)) AS home_team,"
          + " if(p.away_team != '', p.away_team,"
          + "    if(sg.away_team != '', sg.away_team, sg.away_name)) AS away_team,"
          + " p.home_score AS home_score, p.away_score AS away_score, p.inning AS inning,"
          + " if(s.status != '', s.status, if(sg.status != '', sg.status, 'UNKNOWN')) AS status";

  private static final String SLATE_SCHEDULED_JOIN =
      " LEFT JOIN ( SELECT game_id, game_date, home_team, away_team, home_name, away_name, status"
          + "   FROM scheduled_games FINAL WHERE game_date = toDate(?) ) AS sg"
          + " ON sg.game_id = ids.game_id";

  private static final String SLATE_PITCHES_JOIN =
      " LEFT JOIN ( SELECT game_id, game_date, max(home_score) AS home_score,"
          + "   max(away_score) AS away_score, max(inning) AS inning,"
          + "   any(home_team) AS home_team, any(away_team) AS away_team"
          + "   FROM pitches_live FINAL WHERE game_date = toDate(?)"
          + "   GROUP BY game_id, game_date ) AS p ON p.game_id = ids.game_id";

  private static final String SLATE_STATUS_JOIN =
      " LEFT JOIN ( SELECT game_id, argMax(status, updated_at) AS status"
          + "   FROM live_game_status GROUP BY game_id ) AS s ON s.game_id = ids.game_id";

  private static final String FIND_GAMES_FOR_DATE =
      SLATE_DETAIL_SELECT
          + " FROM ( SELECT game_id FROM scheduled_games FINAL WHERE game_date = toDate(?)"
          + "        UNION DISTINCT"
          + "        SELECT game_id FROM pitches_live FINAL WHERE game_date = toDate(?) ) AS ids"
          + SLATE_SCHEDULED_JOIN
          + SLATE_PITCHES_JOIN
          + SLATE_STATUS_JOIN
          + " ORDER BY ids.game_id ASC";

  // Single pre-game game (the /v1/games/:id fallback when pitches_live has nothing yet). Scheduled
  // -only and self-contained: a pre-game game has no pitches, so score/inning are 0 and status is
  // the schedule's SCHEDULED (else the live status if one already landed). One game_id bind.
  private static final String FIND_SCHEDULED_GAME =
      "SELECT sg.game_id AS game_id, sg.game_date AS game_date,"
          + " if(sg.home_team != '', sg.home_team, sg.home_name) AS home_team,"
          + " if(sg.away_team != '', sg.away_team, sg.away_name) AS away_team,"
          + " 0 AS home_score, 0 AS away_score, 0 AS inning,"
          + " if(s.status != '', s.status, if(sg.status != '', sg.status, 'SCHEDULED')) AS status"
          + " FROM ( SELECT game_id, game_date, home_team, away_team, home_name, away_name, status"
          + "        FROM scheduled_games FINAL WHERE game_id = ? ) AS sg"
          + " LEFT JOIN ( SELECT game_id, argMax(status, updated_at) AS status"
          + "   FROM live_game_status GROUP BY game_id ) AS s ON s.game_id = sg.game_id";

  private static final String FIND_GAME =
      "SELECT g.game_id AS game_id, g.game_date AS game_date, g.home_team AS home_team,"
          + " g.away_team AS away_team, g.home_score AS home_score, g.away_score AS away_score,"
          + " g.inning AS inning, if(s.status = '', 'UNKNOWN', s.status) AS status"
          + " FROM ("
          + "   SELECT game_id, game_date, home_team, away_team,"
          + "          max(home_score) AS home_score, max(away_score) AS away_score,"
          + "          max(inning) AS inning"
          + "   FROM pitches_live FINAL WHERE game_id = ?"
          + "   GROUP BY game_id, game_date, home_team, away_team"
          + " ) AS g"
          + LATEST_STATUS_SUBQUERY;

  private static final String INSERT_GAME_STATUS =
      "INSERT INTO live_game_status (game_id, game_date, status) VALUES (?, ?, ?)";

  private static final String INSERT_SCHEDULED_GAME =
      "INSERT INTO scheduled_games"
          + " (game_id, game_date, game_time_utc, home_team, away_team, home_name, away_name,"
          + " status)"
          + " VALUES (?,?,?,?,?,?,?,?)";

  /** UTC 'yyyy-MM-dd HH:mm:ss' for the Nullable(DateTime) game_time_utc column. */
  private static final DateTimeFormatter CH_DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

  private static final String INSERT_PITCH =
      "INSERT INTO pitches_live"
          + " (game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,"
          + " description, pitch_type, release_speed_mph, plate_x_in, plate_z_in,"
          + " balls, strikes, outs, inning, home_score, away_score, home_team, away_team)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final JdbcTemplate jdbc;

  public LivePitchesRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Batch-insert the pitches parsed from a live feed (the LivePollingService write path).
   *
   * <p>Idempotent: pitches_live is a {@code ReplacingMergeTree} keyed on (game_id, at_bat_index,
   * pitch_number), so a re-polled or corrected pitch is overwritten on the next merge / FINAL read.
   * {@code game_date} is bound as an ISO-8601 String (not {@code java.sql.Date}) so clickhouse-jdbc
   * renders it quoted - same lesson as {@code findGamesForDate}: a bare date inlines as the token
   * {@code 2026-06-04}, which ClickHouse evaluates as arithmetic. {@code pitch_type} is coalesced
   * to {@code ""} because its column is a non-nullable {@code LowCardinality(String)}.
   *
   * <p>Returns the number of pitches submitted; ClickHouse batch-update counts are not reliable, so
   * the FINAL read is the source of truth for what actually landed.
   */
  public int insertPitches(LiveGameFeed feed) {
    List<LivePitch> pitches = feed.pitches();
    if (pitches.isEmpty()) {
      return 0;
    }
    String gameDate = Objects.requireNonNull(feed.gameDate(), "feed.gameDate()").toString();
    String homeTeam = feed.homeAbbrev();
    String awayTeam = feed.awayAbbrev();
    jdbc.batchUpdate(
        INSERT_PITCH,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            LivePitch p = pitches.get(i);
            ps.setLong(1, p.gameId());
            ps.setInt(2, p.atBatIndex());
            ps.setInt(3, p.pitchNumber());
            ps.setString(4, gameDate);
            ps.setLong(5, p.pitcherId());
            ps.setLong(6, p.batterId());
            ps.setString(7, p.description());
            ps.setString(8, p.pitchType() == null ? "" : p.pitchType());
            setNullableFloat(ps, 9, p.releaseSpeedMph());
            setNullableFloat(ps, 10, p.plateXIn());
            setNullableFloat(ps, 11, p.plateZIn());
            ps.setInt(12, p.preBalls());
            ps.setInt(13, p.preStrikes());
            ps.setInt(14, p.outs());
            ps.setInt(15, p.inning());
            ps.setInt(16, p.homeScore());
            ps.setInt(17, p.awayScore());
            ps.setString(18, homeTeam);
            ps.setString(19, awayTeam);
          }

          @Override
          public int getBatchSize() {
            return pitches.size();
          }
        });
    return pitches.size();
  }

  private static void setNullableFloat(PreparedStatement ps, int idx, Double v)
      throws SQLException {
    if (v == null) {
      ps.setNull(idx, Types.REAL);
    } else {
      ps.setDouble(idx, v);
    }
  }

  public List<LivePitchRow> findPitchesSince(long gameId, long sinceCursor) {
    // Params: subquery game_id (prunes prediction_log to this game), outer game_id, cursor.
    return jdbc.query(FIND_PITCHES_SINCE, PITCH_MAPPER, gameId, gameId, sinceCursor);
  }

  /**
   * Upsert a game's current status; the poller (worker) calls this on a transition. The
   * ReplacingMergeTree supersedes the prior row and findGamesForDate / findGame surface the latest
   * via argMax. {@code game_date} is bound as an ISO-8601 String (clickhouse-jdbc inlines a bare
   * date as arithmetic - same lesson as the pitches insert).
   */
  public void upsertGameStatus(long gameId, LocalDate gameDate, String status) {
    jdbc.update(INSERT_GAME_STATUS, gameId, gameDate.toString(), status);
  }

  /**
   * Persist the day's slate (the poller calls this on each schedule refresh, ~15 min, so the full
   * card is present in ClickHouse well before first pitch - the "populate the day at ~11:00 ET"
   * behaviour). ReplacingMergeTree(ingested_at) dedups re-writes; abbreviations and start time
   * coalesce to safe defaults. {@code game_date} is an ISO-8601 String (the bare-date arithmetic
   * lesson); {@code game_time_utc} is a UTC 'yyyy-MM-dd HH:mm:ss' String or NULL.
   */
  public void upsertScheduledGames(List<ScheduledGame> games, LocalDate gameDate) {
    if (games.isEmpty()) {
      return;
    }
    String date = gameDate.toString();
    jdbc.batchUpdate(
        INSERT_SCHEDULED_GAME,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            ScheduledGame g = games.get(i);
            ps.setLong(1, g.gamePk());
            ps.setString(2, date);
            Instant t = g.gameTimeUtc();
            if (t == null) {
              ps.setNull(3, Types.VARCHAR);
            } else {
              ps.setString(3, CH_DATETIME.format(t));
            }
            ps.setString(4, nz(g.homeAbbr()));
            ps.setString(5, nz(g.awayAbbr()));
            ps.setString(6, nz(g.homeName()));
            ps.setString(7, nz(g.awayName()));
            ps.setString(8, g.status() == null ? "SCHEDULED" : g.status().name());
          }

          @Override
          public int getBatchSize() {
            return games.size();
          }
        });
  }

  private static String nz(String s) {
    return s == null ? "" : s;
  }

  /** Today's full slate (schedule-driven), decorated with live score/inning/status. */
  public List<GameSummary> findGamesForDate(LocalDate date) {
    // Two toDate(?) binds (scheduled_games + pitches_live subqueries); ISO-8601 String, see
    // FIND_GAMES_FOR_DATE on the bare-date arithmetic lesson.
    String d = date.toString();
    // Four binds: the ids union (scheduled_games, pitches_live) + the sg + p detail subqueries.
    return jdbc.query(FIND_GAMES_FOR_DATE, GAME_SUMMARY_MAPPER, d, d, d, d);
  }

  public java.util.Optional<GameSummary> findGame(long gameId) {
    // Started/historical games come from pitches_live (FIND_GAME, unchanged). A pre-game game has
    // no pitches yet, so fall back to the schedule (FIND_SCHEDULED_GAME) - both game_id binds.
    List<GameSummary> hits = jdbc.query(FIND_GAME, GAME_SUMMARY_MAPPER, gameId);
    if (!hits.isEmpty()) {
      return java.util.Optional.of(hits.get(0));
    }
    List<GameSummary> scheduled = jdbc.query(FIND_SCHEDULED_GAME, GAME_SUMMARY_MAPPER, gameId);
    return scheduled.isEmpty()
        ? java.util.Optional.empty()
        : java.util.Optional.of(scheduled.get(0));
  }

  private static final RowMapper<GameSummary> GAME_SUMMARY_MAPPER =
      (ResultSet rs, int n) -> {
        String status = rs.getString("status");
        return new GameSummary(
            rs.getLong("game_id"),
            rs.getDate("game_date").toLocalDate(),
            rs.getString("home_team"),
            rs.getString("away_team"),
            rs.getInt("home_score"),
            rs.getInt("away_score"),
            rs.getInt("inning"),
            status,
            humanizeStatus(status));
      };

  /** GameStatus enum name -> display label: IN_PROGRESS -> "In Progress", UNKNOWN -> "Unknown". */
  static String humanizeStatus(String status) {
    if (status == null || status.isEmpty()) {
      return "Unknown";
    }
    StringBuilder sb = new StringBuilder(status.length());
    for (String word : status.split("_")) {
      if (word.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(Character.toUpperCase(word.charAt(0)))
          .append(word.substring(1).toLowerCase(Locale.ROOT));
    }
    return sb.toString();
  }

  private static final RowMapper<LivePitchRow> PITCH_MAPPER =
      (ResultSet rs, int n) -> {
        Timestamp ts = rs.getTimestamp("ingested_at");
        Prediction pred = parsePrediction(rs.getString("prediction_json"));
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
            // Truth-join (step 5): the latest champion prediction keyed to this pitch, or null when
            // none was logged (LEFT JOIN miss -> empty string -> the frontend's "n/a" path).
            pred.classes(),
            pred.winner());
      };

  private static Double nullable(ResultSet rs, String col) throws java.sql.SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }

  /**
   * Parsed champion prediction from the joined {@code prediction} JSON, or {@code (null, null)}.
   */
  record Prediction(Map<String, Double> classes, String winner) {}

  /**
   * Parse the joined {@code prediction_json} ({@code {"probabilities": {...}, "winner": "..."}})
   * into a class map + winner. Returns {@code (null, null)} for an absent prediction (a LEFT JOIN
   * miss yields an empty string) or a malformed payload - one bad prediction row must not break the
   * whole read path.
   */
  static Prediction parsePrediction(String json) {
    if (json == null || json.isEmpty()) {
      return new Prediction(null, null);
    }
    try {
      JsonNode node = MAPPER.readTree(json);
      Map<String, Double> classes = new LinkedHashMap<>();
      for (Map.Entry<String, JsonNode> e : node.path("probabilities").properties()) {
        classes.put(e.getKey(), e.getValue().asDouble());
      }
      String winner = node.path("winner").asText(null);
      return new Prediction(classes.isEmpty() ? null : classes, winner);
    } catch (JsonProcessingException e) {
      return new Prediction(null, null);
    }
  }
}
