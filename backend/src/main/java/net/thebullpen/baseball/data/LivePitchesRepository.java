package net.thebullpen.baseball.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import net.thebullpen.baseball.ingest.LiveGameFeed;
import net.thebullpen.baseball.ingest.LivePitch;
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
