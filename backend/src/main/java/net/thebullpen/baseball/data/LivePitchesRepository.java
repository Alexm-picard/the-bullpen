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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import javax.sql.DataSource;
import net.thebullpen.baseball.domain.BattedBall;
import net.thebullpen.baseball.domain.CurrentMatchup;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.GameSummary;
import net.thebullpen.baseball.domain.LivePitch;
import net.thebullpen.baseball.domain.LivePitchRow;
import net.thebullpen.baseball.domain.PagedRows;
import net.thebullpen.baseball.domain.PostPredictionRow;
import net.thebullpen.baseball.domain.RecentBattedBall;
import net.thebullpen.baseball.domain.ScheduledGame;
import net.thebullpen.baseball.domain.TeamContactBall;
import net.thebullpen.baseball.ingest.LiveGameFeed;
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
          // A5 pre-pitch context (V028): the frontend forwards these into the A6 next-pitch request
          // to mirror LivePitchPredictor.toRequest bit-for-bit. park_id reuses home_team (home_team
          // IS the park id by project convention; == the serving path's ctx.parkId()) - no DDL.
          // pitch_hand/bat_side default '' on a pre-V028 row; base_state is Nullable (NULL on a
          // pre-V028 row - unknown occupancy, never a false bases-empty 0).
          + " pl.pitch_hand AS pitch_hand, pl.bat_side AS bat_side, pl.base_state AS base_state,"
          + " pl.home_team AS park_id,"
          // Realized batted-ball outcome. TWO sources, live preferred:
          //
          //   pitches_live (V032) - populated during the game, the moment a play completes. This
          //     is what makes the card work for a game in progress, which is the whole point.
          //   pitches (V003)      - the canonical table, populated by the overnight handoff. Still
          //     needed: it is the ONLY source for a game whose live rows have aged past the
          //     14-day TTL, and for anything ingested before V032 shipped.
          //
          // The live side is gated on its GROUP-level presence predicate, not on any single
          // column, because 0 is a real launch angle and 1 ft a real distance - only
          // launch_speed_mph and events can carry absence (see V032's header). A live row that
          // fails the predicate falls through to the historical value rather than masking it with
          // a sentinel, so an old backfilled game reads exactly as it did before this change.
          + " if(pl.launch_speed_mph > 0 AND pl.events != '', pl.launch_speed_mph,"
          + "    ph.launch_speed_mph) AS launch_speed_mph,"
          + " if(pl.launch_speed_mph > 0 AND pl.events != '', pl.launch_angle_deg,"
          + "    ph.launch_angle_deg) AS launch_angle_deg,"
          + " if(pl.launch_speed_mph > 0 AND pl.events != '', pl.hit_distance_ft,"
          + "    ph.hit_distance_ft) AS hit_distance_ft,"
          + " if(pl.launch_speed_mph > 0 AND pl.events != '', pl.bb_type, ph.bb_type) AS bb_type,"
          + " if(pl.launch_speed_mph > 0 AND pl.events != '', pl.events, ph.events) AS events,"
          // Coordinates are selected but NOT surfaced raw: the mapper turns them into a spray
          // angle via BattedBall, so the derivation (and its two degeneracy gates) lives in ONE
          // place rather than being reimplemented in TypeScript. `pitches` has hc_x/hc_y too, but
          // only the live side is coalesced here - a backfilled game already reaches the model
          // through the historical path and gains nothing from a second spray source.
          + " pl.hc_x AS hc_x, pl.hc_y AS hc_y,"
          + " pred.prediction AS prediction_json"
          + " FROM pitches_live AS pl FINAL"
          // One champion prediction per pitch: predict-next re-logs the same upcoming pitch on
          // every poll (decision [143]), so collapse to the latest by request_at. NULL-keyed
          // HTTP-path rows never match (game_id IS NULL after the equality). Unmatched pitches get
          // '' from the LEFT JOIN -> null predictions in the mapper (the frontend's "n/a" path).
          // model_name = pitch_outcome_pre is LOAD-BEARING (F2.1a): the worker now also logs
          // pitch_outcome_post champion rows keyed to the same pitch, but AFTER the pitch lands, so
          // their later request_at would win argMax and surface the POST head here. The game page's
          // next-pitch stays PRE-ONLY ([143]/[154]/ADR-0011); the POST head is retrospective-only
          // on
          // its own panel ([177]).
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(prediction, request_at) AS prediction"
          + "   FROM prediction_log"
          + "   WHERE game_id = ? AND role = 'champion' AND model_name = 'pitch_outcome_pre'"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pred"
          + " ON pred.game_id = pl.game_id AND pred.at_bat_index = pl.at_bat_index"
          + "    AND pred.pitch_number = pl.pitch_number"
          // Batted-ball outcome join (Phase 1.2): pitches is a ReplacingMergeTree(ingested_at) on
          // the same natural key, so dedup to the latest version per pitch via argMax(col,
          // ingested_at) - mirrors the prediction_log idiom above and avoids FINAL on the large
          // historical table. game_id is pruned in the subquery (the 2nd outer bind).
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(launch_speed_mph, ingested_at) AS launch_speed_mph,"
          + "          argMax(launch_angle_deg, ingested_at) AS launch_angle_deg,"
          + "          argMax(hit_distance_ft, ingested_at) AS hit_distance_ft,"
          + "          argMax(bb_type, ingested_at) AS bb_type,"
          + "          argMax(events, ingested_at) AS events"
          + "   FROM pitches"
          + "   WHERE game_id = ?"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS ph"
          + " ON ph.game_id = pl.game_id AND ph.at_bat_index = pl.at_bat_index"
          + "    AND ph.pitch_number = pl.pitch_number"
          + " WHERE pl.game_id = ? AND (pl.at_bat_index * 100 + pl.pitch_number) > ?"
          + " ORDER BY cursor ASC LIMIT 500";

  // F2.1b: the logged pitch_outcome_post CHAMPION predictions for a game, joined to each pitch's
  // realized outcome - backs decision [177]'s retrospective panel. This is the mirror image of
  // FIND_PITCHES_SINCE: there the pitch table drives and the prediction is the optional join; here
  // the champion POST prediction DRIVES (one row per logged prediction) and pitches_live is the
  // optional realized-outcome join.
  //
  // The WHERE scoping is LOAD-BEARING (the F2.1a discipline): role = 'champion' AND model_name =
  // 'pitch_outcome_post'. A pitch_outcome_pre row (the game page's next-pitch) or a POST SHADOW row
  // keyed to the same pitch must NEVER surface here. argMax(prediction, request_at) collapses the
  // re-logged rows to the latest by request_at; argMax(model_version, request_at) carries the
  // version that produced that latest prediction. game_id is pruned inside the subquery (the 1st
  // outer bind).
  //
  // pitches_live is deduped via argMax(col, ingested_at) over its ReplacingMergeTree - NO FINAL,
  // mirroring the FIND_PITCHES_SINCE ph subquery idiom, game_id-pruned (the 2nd outer bind). A LEFT
  // JOIN miss (a logged prediction whose pitch has not round-tripped into pitches_live) yields the
  // LowCardinality '' default for realized_outcome -> null in the mapper, and 0 for the numeric
  // columns.
  //
  // Pagination: LIMIT (size + 1) OFFSET (page * size) - the extra +1 row lets the method report
  // hasNext without a separate count query, then drops the overflow row. Binds, in SQL order:
  // prediction_log subquery game_id, pitches_live subquery game_id, LIMIT (size + 1), OFFSET
  // (page * size).
  private static final String FIND_POST_PREDICTIONS =
      "SELECT pred.at_bat_index AS at_bat_index, pred.pitch_number AS pitch_number,"
          + " pl.inning AS inning, pl.pitcher_id AS pitcher_id, pl.batter_id AS batter_id,"
          + " pl.description AS realized_outcome, pred.prediction AS prediction_json,"
          + " pred.model_version AS model_version"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(prediction, request_at) AS prediction,"
          + "          argMax(model_version, request_at) AS model_version"
          + "   FROM prediction_log"
          + "   WHERE game_id = ? AND role = 'champion' AND model_name = 'pitch_outcome_post'"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pred"
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(description, ingested_at) AS description,"
          + "          argMax(inning, ingested_at) AS inning,"
          + "          argMax(pitcher_id, ingested_at) AS pitcher_id,"
          + "          argMax(batter_id, ingested_at) AS batter_id"
          + "   FROM pitches_live"
          + "   WHERE game_id = ?"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pl"
          + " ON pl.game_id = pred.game_id AND pl.at_bat_index = pred.at_bat_index"
          + "    AND pl.pitch_number = pred.pitch_number"
          + " ORDER BY pred.at_bat_index ASC, pred.pitch_number ASC"
          + " LIMIT ? OFFSET ?";

  // V031's live current-play matchup, read as ONE argMax over a TUPLE rather than five parallel
  // argMax calls. WHAT THE TUPLE BUYS, stated in the present tense because a future editor will
  // judge it on that and not on history: five independent argMax aggregate states can each
  // resolve a TIED updated_at to a different row, which would tear the record - a batter from one
  // row paired with an at-bat index from another. One tuple state cannot tear. (The historical
  // reason this shape arrived - argMax skipping NULL args under the pre-fix Nullable columns - is
  // recorded in V031's header, and no longer applies: the columns are non-Nullable, verified by
  // mutating this read back to per-column argMax and watching the ITs still pass.)
  //
  // status stays OUTSIDE the tuple. Not because it is non-NULL - that prevents a row being
  // SKIPPED, not two aggregate states disagreeing on a tie - but because a tie needs two writes
  // for one game inside one second, and the per-game poll gate makes that unreachable short of a
  // D-37 lease handover, where both rows would carry the same status anyway.
  private static final String MATCHUP_SUBQUERY_COLS =
      ", argMax(tuple(current_batter_id, current_pitcher_id, current_bat_side,"
          + " current_pitch_hand, current_at_bat_index), updated_at) AS matchup";

  // The tuple unpacked from the joined status alias. A LEFT JOIN miss yields the tuple type's
  // default - (0, 0, '', '', 0), verified against the V031 types, NOT nulls - which isPopulated()
  // rejects, so a game with no status row reports NO matchup rather than a matchup of zeroes.
  // Naming the mechanism precisely matters here more than usual: NULL-vs-0 on this exact read
  // path is what made the first version of this feature silently wrong.
  private static final String MATCHUP_SELECT_COLS =
      ", tupleElement(s.matchup, 1) AS current_batter_id,"
          + " tupleElement(s.matchup, 2) AS current_pitcher_id,"
          + " tupleElement(s.matchup, 3) AS current_bat_side,"
          + " tupleElement(s.matchup, 4) AS current_pitch_hand,"
          + " tupleElement(s.matchup, 5) AS current_at_bat_index";

  // Latest status per game from the poller (step 7b). argMax over the ReplacingMergeTree gives the
  // current status; a LEFT JOIN miss yields '' -> 'UNKNOWN' (the honest pre-poll answer).
  private static final String LATEST_STATUS_SUBQUERY =
      " LEFT JOIN ( SELECT game_id, argMax(status, updated_at) AS status"
          + MATCHUP_SUBQUERY_COLS
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
          + " if(s.status != '', s.status, if(sg.status != '', sg.status, 'UNKNOWN')) AS status"
          + MATCHUP_SELECT_COLS;

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
          + MATCHUP_SUBQUERY_COLS
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
          + MATCHUP_SELECT_COLS
          + " FROM ( SELECT game_id, game_date, home_team, away_team, home_name, away_name, status"
          + "        FROM scheduled_games FINAL WHERE game_id = ? ) AS sg"
          + " LEFT JOIN ( SELECT game_id, argMax(status, updated_at) AS status"
          + MATCHUP_SUBQUERY_COLS
          + "   FROM live_game_status GROUP BY game_id ) AS s ON s.game_id = sg.game_id";

  private static final String FIND_GAME =
      "SELECT g.game_id AS game_id, g.game_date AS game_date, g.home_team AS home_team,"
          + " g.away_team AS away_team, g.home_score AS home_score, g.away_score AS away_score,"
          + " g.inning AS inning, if(s.status = '', 'UNKNOWN', s.status) AS status"
          + MATCHUP_SELECT_COLS
          + " FROM ("
          + "   SELECT game_id, game_date, home_team, away_team,"
          + "          max(home_score) AS home_score, max(away_score) AS away_score,"
          + "          max(inning) AS inning"
          + "   FROM pitches_live FINAL WHERE game_id = ?"
          + "   GROUP BY game_id, game_date, home_team, away_team"
          + " ) AS g"
          + LATEST_STATUS_SUBQUERY;

  private static final String INSERT_GAME_STATUS =
      "INSERT INTO live_game_status (game_id, game_date, status, current_batter_id,"
          + " current_pitcher_id, current_bat_side, current_pitch_hand, current_at_bat_index)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  private static final String INSERT_SCHEDULED_GAME =
      "INSERT INTO scheduled_games"
          + " (game_id, game_date, game_time_utc, home_team, away_team, home_name, away_name,"
          + " status, home_pitcher_id, home_pitcher_name, away_pitcher_id, away_pitcher_name)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";

  /** UTC 'yyyy-MM-dd HH:mm:ss' for the Nullable(DateTime) game_time_utc column. */
  private static final DateTimeFormatter CH_DATETIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

  private static final String INSERT_PITCH =
      "INSERT INTO pitches_live"
          + " (game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,"
          + " description, pitch_type, release_speed_mph, plate_x_in, plate_z_in,"
          + " balls, strikes, outs, inning, home_score, away_score, home_team, away_team,"
          + " pfx_x_in, pfx_z_in, spin_rate_rpm, spin_axis_deg, release_pos_x_in, release_pos_z_in,"
          // A5 (V028): the pre-pitch context the frontend forwards into the A6 next-pitch request.
          + " pitch_hand, bat_side, base_state,"
          // V032: the measured physics of a COMPLETED ball in play. Non-Nullable with sentinels,
          // and absence is decided at the GROUP level (launch_speed_mph > 0 AND events != '')
          // rather than per column - 0 is a real launch angle, 1 ft a real distance, and 0,0 a
          // coordinate origin, so none of those three can carry absence on its own.
          + " launch_speed_mph, launch_angle_deg, hit_distance_ft, hc_x, hc_y, bb_type, events)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

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
            // Derived Tier-4 (see GumboKinematics); null when the live fit was incomplete.
            setNullableFloat(ps, 20, p.pfxXIn());
            setNullableFloat(ps, 21, p.pfxZIn());
            setNullableFloat(ps, 22, p.spinRateRpm());
            setNullableFloat(ps, 23, p.spinAxisDeg());
            setNullableFloat(ps, 24, p.releasePosXIn());
            setNullableFloat(ps, 25, p.releasePosZIn());
            // A5 pre-pitch context (V028). pitch_hand/bat_side are LowCardinality DEFAULT '', so
            // coalesce null -> '' (the pitch_type idiom above); base_state is Nullable(UInt8) but
            // the parser always resolves it (the 1/2/4 bitmask off LivePitch.baseState()).
            ps.setString(26, p.pitchHand() == null ? "" : p.pitchHand());
            ps.setString(27, p.batSide() == null ? "" : p.batSide());
            ps.setInt(28, p.baseState());
            // A null BattedBall writes the sentinels across the whole group, never a partial row:
            // the record's own contract is that it is entirely real or entirely absent, and the
            // storage shape has to preserve that or a read could see physics without a result.
            BattedBall bb = p.battedBall();
            ps.setDouble(29, bb == null ? 0d : bb.launchSpeedMph());
            ps.setDouble(30, bb == null ? 0d : bb.launchAngleDeg());
            ps.setDouble(31, bb == null ? 0d : bb.hitDistanceFt());
            ps.setDouble(32, bb == null ? 0d : bb.hcX());
            ps.setDouble(33, bb == null ? 0d : bb.hcY());
            ps.setString(34, bb == null ? "" : bb.bbType());
            ps.setString(35, bb == null ? "" : bb.event());
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
    // Params, in SQL order: prediction_log subquery game_id, pitches subquery game_id (batted-ball
    // join), outer pl.game_id, cursor.
    return jdbc.query(FIND_PITCHES_SINCE, PITCH_MAPPER, gameId, gameId, gameId, sinceCursor);
  }

  /**
   * A page of the logged {@code pitch_outcome_post} champion predictions for a game, each joined to
   * its pitch's realized outcome (F2.1b). Backs decision [177]'s retrospective panel.
   *
   * <p>Over-fetches one row ({@code LIMIT size + 1}) to decide {@code hasNext} without a count
   * query, then trims the overflow row off the returned page. Binds, in SQL order: the
   * prediction_log subquery game_id, the pitches_live subquery game_id, then {@code size + 1}
   * (LIMIT) and {@code page * size} (OFFSET). The {@code page}/{@code size} bounds are validated at
   * the controller, which also assembles the {@code api/dto/PostPredictionsPage} wire envelope from
   * this carrier plus the request's page/size (ADR-0015).
   */
  public PagedRows<PostPredictionRow> findPostPredictions(long gameId, int page, int size) {
    int limit = size + 1;
    long offset = (long) page * size;
    List<PostPredictionRow> rows =
        jdbc.query(FIND_POST_PREDICTIONS, POST_PREDICTION_MAPPER, gameId, gameId, limit, offset);
    boolean hasNext = rows.size() > size;
    List<PostPredictionRow> pageRows = hasNext ? List.copyOf(rows.subList(0, size)) : rows;
    return new PagedRows<>(pageRows, hasNext);
  }

  /**
   * Upsert a game's status row together with the LIVE current-play matchup (V031).
   *
   * <p>Called by the poller (worker) on a status transition, on its first poll of a game, or when
   * the matchup moves. The ReplacingMergeTree supersedes the prior row and findGamesForDate /
   * findGame surface the latest. {@code game_date} is bound as an ISO-8601 String (clickhouse-jdbc
   * inlines a bare date as arithmetic - same lesson as the pitches insert).
   *
   * <p>The two travel in one row on purpose: they are read back with a single {@code
   * argMax(tuple(..), updated_at)} off that row, so writing them separately could pair a batter
   * from one tick with an at-bat index from another. {@code matchup} is null whenever the feed
   * carries no current play (pre-game, between plays, final) - and that absence is WRITTEN, as the
   * 0/'' sentinels, so the row stops advertising a batter who has already finished hitting. The
   * sentinels are not a choice made here: the V031 columns are non-Nullable, so absence has exactly
   * one storage shape, and {@code isPopulated()} is what rejects it on the way back out.
   */
  public void upsertGameStatus(
      long gameId, LocalDate gameDate, String status, CurrentMatchup matchup) {
    boolean usable = matchup != null && matchup.isPopulated();
    jdbc.update(
        INSERT_GAME_STATUS,
        gameId,
        gameDate.toString(),
        status,
        usable ? matchup.batterId() : 0L,
        usable ? matchup.pitcherId() : 0L,
        usable ? nz(matchup.batSide()) : "",
        usable ? nz(matchup.pitchHand()) : "",
        usable ? matchup.atBatIndex() : 0);
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
            ps.setLong(9, g.homeProbableId());
            ps.setString(10, nz(g.homeProbableName()));
            ps.setLong(11, g.awayProbableId());
            ps.setString(12, nz(g.awayProbableName()));
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
      // Second query rather than a join: the summary query is keyed on the status row and joining a
      // pitch-level lookup into it would make every slate read pay for a per-game scan. A scheduled
      // game skips this entirely - it has no pitches, so there is nothing to find.
      return java.util.Optional.of(
          hits.get(0).withMostRecentBattedBall(findMostRecentBattedBall(gameId)));
    }
    List<GameSummary> scheduled = jdbc.query(FIND_SCHEDULED_GAME, GAME_SUMMARY_MAPPER, gameId);
    return scheduled.isEmpty()
        ? java.util.Optional.empty()
        : java.util.Optional.of(scheduled.get(0));
  }

  private static final String FIND_SCHEDULED_GAMES_FOR_DATE =
      "SELECT game_id, status, home_team, away_team, home_name, away_name, game_time_utc,"
          + " home_pitcher_id, home_pitcher_name, away_pitcher_id, away_pitcher_name"
          + " FROM scheduled_games FINAL WHERE game_date = toDate(?) ORDER BY game_id ASC";

  /** The day's scheduled games (with probables) - the matchup job's input. */
  public List<ScheduledGame> findScheduledGames(LocalDate date) {
    return jdbc.query(FIND_SCHEDULED_GAMES_FOR_DATE, SCHEDULED_GAME_MAPPER, date.toString());
  }

  private static final RowMapper<ScheduledGame> SCHEDULED_GAME_MAPPER =
      (ResultSet rs, int n) -> {
        // game_time_utc is DateTime('UTC') (V023). Read it tz-explicitly: getTimestamp() would
        // reinterpret the UTC wall-clock in the JVM default zone (ET on the box) and re-introduce
        // the +4h skew V023 fixed in storage. getObject(LocalDateTime) returns the raw UTC
        // wall-clock; pin it to UTC.
        LocalDateTime t = rs.getObject("game_time_utc", LocalDateTime.class);
        return new ScheduledGame(
            rs.getLong("game_id"),
            parseStatus(rs.getString("status")),
            rs.getString("home_team"),
            rs.getString("away_team"),
            rs.getString("home_name"),
            rs.getString("away_name"),
            t == null ? null : t.toInstant(ZoneOffset.UTC),
            rs.getLong("home_pitcher_id"),
            rs.getString("home_pitcher_name"),
            rs.getLong("away_pitcher_id"),
            rs.getString("away_pitcher_name"));
      };

  private static GameStatus parseStatus(String s) {
    if (s == null || s.isEmpty()) {
      return GameStatus.SCHEDULED;
    }
    try {
      return GameStatus.valueOf(s);
    } catch (IllegalArgumentException e) {
      return GameStatus.SCHEDULED;
    }
  }

  /**
   * SQL for the most recent COMPLETED ball in play of a game.
   *
   * <p>Gated on V032's GROUP-level presence predicate, so a pitch whose physics is absent (the
   * sentinels) is not mistaken for a batted ball measured at 0 mph. Ordered by the natural pitch
   * key rather than by ingestion time, because a re-written row (the BIP backfill supersedes a
   * pitch once its play completes) has a LATER ingested_at than pitches thrown after it - ordering
   * by time would surface a stale ball whenever a backfill landed out of order.
   */
  private static final String FIND_RECENT_BATTED_BALL =
      "SELECT batter_id, at_bat_index, pitch_number, ingested_at, events, bb_type,"
          + " launch_speed_mph, launch_angle_deg, hit_distance_ft, hc_x, hc_y,"
          + " bat_side, pitch_hand, base_state, home_team AS park_id, outs"
          + " FROM pitches_live FINAL"
          + " WHERE game_id = ? AND launch_speed_mph > 0 AND events != ''"
          + " ORDER BY at_bat_index DESC, pitch_number DESC"
          + " LIMIT 1";

  /** The most recent completed ball in play, or null when the game has had none yet. */
  public RecentBattedBall findMostRecentBattedBall(long gameId) {
    List<RecentBattedBall> hits =
        jdbc.query(
            FIND_RECENT_BATTED_BALL,
            (ResultSet rs, int n) -> {
              // Switch hitters resolve against the pitcher HERE, so the served model input and the
              // page agree on which side a batter hit from - the same resolution nextPitchRequest
              // performs, done once at the source rather than twice downstream.
              String stand = rs.getString("bat_side");
              String throws_ = rs.getString("pitch_hand");
              if ("S".equals(stand)) {
                stand = "R".equals(throws_) ? "L" : "L".equals(throws_) ? "R" : "";
              }
              // nullableInt, not a cast: clickhouse-jdbc returns Nullable(UInt8) as an
              // UnsignedByte, so (Integer) throws ClassCastException. The existing row mapper
              // already had this helper - reusing it rather than re-solving it.
              Integer baseState = nullableInt(rs, "base_state");
              return new RecentBattedBall(
                  rs.getLong("batter_id"),
                  rs.getInt("at_bat_index"),
                  rs.getInt("pitch_number"),
                  rs.getObject("ingested_at", java.time.LocalDateTime.class)
                      .toInstant(java.time.ZoneOffset.UTC),
                  rs.getString("events"),
                  rs.getString("bb_type"),
                  rs.getDouble("launch_speed_mph"),
                  rs.getDouble("launch_angle_deg"),
                  rs.getDouble("hit_distance_ft"),
                  sprayAngleOrNull(rs),
                  stand,
                  baseState,
                  rs.getString("park_id"),
                  rs.getInt("outs"));
            },
            gameId);
    return hits.isEmpty() ? null : hits.get(0);
  }

  /**
   * A team's most recent REAL balls in play, season-to-date.
   *
   * <p>Team attribution is via {@code players.team}, which is CURRENT team rather than
   * team-at-time-of-contact, so a mid-season trade misattributes that batter's earlier balls.
   * Acceptable for a season profile and stated in the card's copy; it would not be acceptable for
   * anything the model is promoted on.
   *
   * <p>Ordered newest-first and capped, so this is "recent contact" rather than a full-season scan.
   * Rows lacking any measurement are excluded here rather than defaulted, and rows whose spray
   * cannot be honestly derived are dropped in the mapper - the profile is built only from balls
   * where every model input is a real observation.
   */
  private static final String FIND_TEAM_CONTACT =
      "SELECT p.launch_speed_mph AS launch_speed_mph, p.launch_angle_deg AS launch_angle_deg,"
          + " p.hit_distance_ft AS hit_distance_ft, p.hc_x AS hc_x, p.hc_y AS hc_y,"
          + " p.stand AS stand, p.p_throws AS p_throws"
          + " FROM pitches AS p"
          + " INNER JOIN ("
          + "   SELECT id AS player_id, argMax(team, updated_at) AS team"
          + "   FROM players GROUP BY id"
          + " ) AS pl ON pl.player_id = p.batter_id"
          + " WHERE pl.team = ? AND p.game_date >= ?"
          + "   AND p.launch_speed_mph > 0 AND p.launch_angle_deg IS NOT NULL"
          + "   AND p.hit_distance_ft IS NOT NULL AND p.hc_x IS NOT NULL AND p.hc_y IS NOT NULL"
          + " ORDER BY p.game_date DESC, p.game_id DESC, p.at_bat_index DESC"
          + " LIMIT ?";

  /**
   * Recent real batted balls for a team, for the pre-first-pitch comparison.
   *
   * <p>Returns fewer than {@code limit} when spray declines on some rows; that is intended, and the
   * caller reports the surviving n rather than padding.
   */
  public List<TeamContactBall> findTeamContact(String team, LocalDate from, int limit) {
    List<TeamContactBall> rows =
        jdbc.query(
            FIND_TEAM_CONTACT,
            (ResultSet rs, int n) -> {
              OptionalDouble spray =
                  BattedBall.sprayAngleDeg(rs.getDouble("hc_x"), rs.getDouble("hc_y"));
              if (spray.isEmpty()) {
                return null; // degenerate geometry - dropped below rather than fabricated
              }
              String stand = rs.getString("stand").trim();
              String throws_ = rs.getString("p_throws").trim();
              if ("S".equals(stand)) {
                stand = "R".equals(throws_) ? "L" : "L".equals(throws_) ? "R" : "";
              }
              if (!"L".equals(stand) && !"R".equals(stand)) {
                return null; // the model requires L|R; an unresolvable side is not guessed
              }
              return new TeamContactBall(
                  rs.getDouble("launch_speed_mph"),
                  rs.getDouble("launch_angle_deg"),
                  rs.getDouble("hit_distance_ft"),
                  spray.getAsDouble(),
                  stand);
            },
            team,
            from.toString(),
            limit);
    return rows.stream().filter(java.util.Objects::nonNull).toList();
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
            humanizeStatus(status),
            readMatchup(rs),
            // Composed by findGame from a second query - the summary query is keyed on the status
            // row and has no pitch join.
            null);
      };

  /**
   * The V031 matchup columns, or NULL when they do not describe a usable matchup.
   *
   * <p>Returns null - not a half-empty record - when the ids are absent, so the api omits the whole
   * object rather than publishing a matchup a consumer would reasonably half-trust. Absent is ONE
   * shape now that the columns are non-Nullable: 0, which covers a pre-V031 row, an early-GUMBO
   * tick (the parser's missing-id value), a cleared matchup between plays, and a LEFT JOIN miss
   * alike. {@link CurrentMatchup#isPopulated()} is the single place that decision lives.
   */
  private static CurrentMatchup readMatchup(ResultSet rs) throws SQLException {
    CurrentMatchup m =
        new CurrentMatchup(
            rs.getLong("current_batter_id"),
            rs.getLong("current_pitcher_id"),
            nz(rs.getString("current_bat_side")),
            nz(rs.getString("current_pitch_hand")),
            rs.getInt("current_at_bat_index"));
    return m.isPopulated() ? m : null;
  }

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
            pred.winner(),
            // Realized batted-ball outcome from the pitches LEFT JOIN (Phase 1.2): null on
            // non-in-play pitches and on rows not yet moved over by the overnight handoff.
            // emptyToNull collapses the LowCardinality '' default (LEFT JOIN miss / non-in-play) to
            // null per the pinned contract.
            nullable(rs, "launch_speed_mph"),
            nullable(rs, "launch_angle_deg"),
            nullable(rs, "hit_distance_ft"),
            emptyToNull(rs.getString("bb_type")),
            emptyToNull(rs.getString("events")),
            sprayAngleOrNull(rs),
            // A5 pre-pitch context (V028). pitch_hand/bat_side are LowCardinality(String) DEFAULT
            // '' -> '' (NOT null) on a pre-V028 row; the frontend forwards them verbatim ('S'
            // resolves L|R downstream). base_state is Nullable(UInt8) -> null on a pre-V028 row
            // (unknown occupancy), an Integer otherwise. park_id is home_team (the park id).
            // score_diff
            // is NOT a column: it is the serving-path constant 0 (LivePitchPredictor.toRequest
            // sends
            // score_diff=0), forwarded verbatim so the A6 request matches the ingest-side request.
            rs.getString("pitch_hand"),
            rs.getString("bat_side"),
            nullableInt(rs, "base_state"),
            rs.getString("park_id"),
            0);
      };

  private static final RowMapper<PostPredictionRow> POST_PREDICTION_MAPPER =
      (ResultSet rs, int n) -> {
        // The logged post-pitch champion distribution, parsed from the same
        // {"probabilities": {...}, "winner": "..."} shape the PITCH_MAPPER reads.
        Prediction pred = parsePrediction(rs.getString("prediction_json"));
        return new PostPredictionRow(
            rs.getInt("at_bat_index"),
            rs.getInt("pitch_number"),
            rs.getInt("inning"),
            rs.getLong("pitcher_id"),
            rs.getLong("batter_id"),
            // Realized outcome from the pitches_live LEFT JOIN: the LowCardinality '' default (a
            // logged prediction whose pitch has not round-tripped into pitches_live) collapses to
            // null.
            emptyToNull(rs.getString("realized_outcome")),
            pred.classes(),
            pred.winner(),
            rs.getString("model_version"));
      };

  /**
   * Spray angle for a live batted ball, or null when it cannot honestly be derived.
   *
   * <p>Derived HERE rather than on the client so the formula and its two degeneracy gates exist
   * once. {@link BattedBall#sprayAngleDeg()} declines a ball tracked at or behind the plate (where
   * atan2 flips quadrant, and where the ORIGIN would otherwise yield a confident 0 degrees) and any
   * result outside the foul lines. A declined spray must reach the caller as null, never as 0: the
   * per-park model treats a spray angle as observed, so a fabricated 0 would score a ball pulled
   * down the line as though it were hit to dead centre.
   *
   * <p>The {@code (0,0)} check is the STORAGE-SENTINEL case, and it is a DIFFERENT degeneracy from
   * the plate-origin one {@link BattedBall#sprayAngleDeg} documents - do not read them as
   * duplicates and delete one. At the plate origin atan2(0,0) is 0, a false dead-centre. At the
   * storage sentinel (0,0) the derivation is atan2(-125.42, 199.53) = -32.15 degrees, which is
   * comfortably INSIDE the foul lines and so passes the invariant gate: without this check every
   * non-batted-ball row would carry a confident fabricated spray. Null also when the row carries no
   * live coordinates at all - a historical-only row reaches the model through its own path.
   */
  private static Double sprayAngleOrNull(ResultSet rs) throws SQLException {
    double hcX = rs.getDouble("hc_x");
    double hcY = rs.getDouble("hc_y");
    if (hcX == 0 && hcY == 0) {
      return null;
    }
    OptionalDouble spray = BattedBall.sprayAngleDeg(hcX, hcY);
    return spray.isPresent() ? spray.getAsDouble() : null;
  }

  private static Double nullable(ResultSet rs, String col) throws java.sql.SQLException {
    double v = rs.getDouble(col);
    return rs.wasNull() ? null : v;
  }

  /** Read a Nullable(UInt8) as a boxed Integer: null for a NULL (pre-V028 base_state) column. */
  private static Integer nullableInt(ResultSet rs, String col) throws java.sql.SQLException {
    int v = rs.getInt(col);
    return rs.wasNull() ? null : v;
  }

  /** Collapse the LowCardinality(String) '' default (LEFT JOIN miss / non-in-play) to null. */
  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
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
