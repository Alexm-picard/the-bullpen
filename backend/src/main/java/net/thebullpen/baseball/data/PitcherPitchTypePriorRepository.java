package net.thebullpen.baseball.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reader for the Tier-ARS inputs of the pre-pitch pitch-TYPE head: the career-expanding pitch-type
 * COUNT snapshot in {@code pitcher_pitchtype_prior_current} (V030), plus the in-game delta from
 * {@code pitches_live} that carries it forward to "strictly before THIS pitch".
 *
 * <p>NOT {@link PitcherArsenalRepository}. That class computes a DIFFERENT quantity for a different
 * consumer: a {@code GROUP BY pitch_type} aggregate of throw counts and velocity min/avg/max over
 * all seasons, for the frontend's arsenal card. It is a display aggregate; this is a model-feature
 * source with a train/serve-equivalence contract. Neither should grow into the other.
 *
 * <p>WHY A SNAPSHOT AT ALL: computing the career-expanding counts per request scans 100% of {@code
 * pitches} (measured: 7,442,393 rows for ONE pitcher), because {@code pitches} is ORDER BY
 * (game_date, game_id, at_bat_index, pitch_number) and {@code pitcher_id} is in neither the sort
 * key nor a skip index, so the primary index prunes nothing. Full reasoning and the measured
 * numbers are in the V030 header; it is the same trade V007 made for Tier-3 form.
 *
 * <p>THE CONTRACT THIS CLASS EXISTS TO PRESERVE: the model trained on ARS ratios computed over a
 * window frame that EXCLUDES the current pitch. Serving reproduces that EXACTLY, not approximately,
 * by keeping both halves as counts:
 *
 * <pre>{@code
 * ars_c = (base.nC() + delta.nC()) / (base.priorN() + delta.priorN())   // NULL iff denom == 0
 * }</pre>
 *
 * <p>Callers MUST pass {@link PitcherPitchTypePrior#asOfDate()} into {@link #findInGameDelta}: the
 * snapshot covers everything up to and including that date and the delta covers everything strictly
 * after it, so the two meet with no gap and no double count regardless of whether the overnight
 * {@code pitches_live -> pitches} handoff has run yet (V030 header section (c)).
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} (NOT {@code @ConditionalOnBean}, which
 * bean-ordering trap crash-looped the worker; see DriftMetricsRepository / PitcherFormRepository).
 * On {@code api} (the serving read) and {@code worker} (the refresh job, a later PR).
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherPitchTypePriorRepository {

  /**
   * The snapshot row for one pitcher, with the two 12-slot by-count arrays indexed SERVER-SIDE so
   * Java receives plain numbers and never has to know the array layout.
   *
   * <p>{@code FINAL} is load-bearing, not decoration, and it is PERMANENTLY so rather than just
   * until the next merge: {@code pitcher_pitchtype_prior_current} is a ReplacingMergeTree keyed on
   * {@code pitcher_id} but partitioned by {@code toYYYYMM(as_of_date)}, and background merges only
   * collapse duplicates WITHIN a partition, so last month's row survives on disk forever. Verified
   * against ClickHouse 24.12 across a month boundary: the point lookup returns 1 row with FINAL and
   * 2 rows (one of them a stale month) without it.
   *
   * <p>The array-element aliases carry a {@code _slot} suffix rather than reusing the column name.
   * {@code arrayElement(prior_n_by_count, ?) AS prior_n_by_count} does in fact resolve on 24.12,
   * but the alias would then name a scalar while the identifier still reads like the 12-element
   * array - the exact confusion the {@code + 1} indexing trap already invites.
   *
   * <p>Bind order: slot, slot, pitcherId.
   */
  private static final String SELECT_SNAPSHOT =
      "SELECT as_of_date, prior_n, n_ff, n_si, n_fc, n_sl, n_cu, n_ch, n_off,"
          + " arrayElement(prior_n_by_count, ?) AS prior_n_by_count_slot,"
          + " arrayElement(n_ff_by_count, ?) AS n_ff_by_count_slot"
          + " FROM pitcher_pitchtype_prior_current FINAL"
          + " WHERE pitcher_id = ?";

  /**
   * The in-game delta: this pitcher's LABELED pitches strictly between the snapshot's {@code
   * as_of_date} and the pitch being predicted.
   *
   * <p>The upper bound is a FULL TUPLE comparison on the natural pitch key, {@code (game_date,
   * game_id, at_bat_index, pitch_number) < (?, ?, ?, ?)}, which is lexicographic and therefore
   * exactly "ordered before this pitch" - the same ordering the training window frame uses. It is
   * deliberately NOT {@code game_id = <current>}: on a doubleheader the same pitcher can appear in
   * two games on one date, and a game_id equality would silently drop the earlier appearance from
   * the delta. The tuple elements are cast explicitly so each side of the comparison has the exact
   * column type (Date, UInt64, UInt16, UInt8) and ClickHouse never has to invent a common type.
   *
   * <p>The lower bound {@code game_date > toDate(?)} is what makes the composition exact: it is the
   * complement of the snapshot's coverage. It also prunes partitions ({@code PARTITION BY
   * toYYYYMM(game_date)}).
   *
   * <p>{@code pitches_live FINAL}: the live feed re-polls and CORRECTS pitches (pitch_type most of
   * all), so without FINAL a corrected pitch is counted twice. The table carries a 14-day TTL, so
   * the merge is cheap.
   *
   * <p>The y7 multiIf and the {@code NOT IN ('', 'PO', 'IN')} labeled-set filter are copied
   * VERBATIM from {@code training/src/bullpen_training/features/sql/compute_pitch_type_arsenal.sql}
   * and must stay identical to it. An as-yet-untyped live pitch has {@code pitch_type = ''} and is
   * excluded from both numerator and denominator, exactly as in training.
   *
   * <p>Every placeholder lives in the inner SELECT so the bind order reads top-to-bottom: balls,
   * strikes, pitcherId, asOfDate, gameDate, gameId, atBatIndex, pitchNumber.
   *
   * <p>No {@code SETTINGS max_memory_usage = 0} here: that is an offline-batch escape hatch in the
   * training SQL and has no business on the request path.
   */
  private static final String SELECT_IN_GAME_DELTA =
      "SELECT count() AS prior_n,"
          + " countIf(y7 = 'FF') AS n_ff,"
          + " countIf(y7 = 'SI') AS n_si,"
          + " countIf(y7 = 'FC') AS n_fc,"
          + " countIf(y7 = 'SL') AS n_sl,"
          + " countIf(y7 = 'CU') AS n_cu,"
          + " countIf(y7 = 'CH') AS n_ch,"
          + " countIf(y7 = 'OFF') AS n_off,"
          + " countIf(in_count) AS prior_n_by_count_slot,"
          + " countIf(in_count AND y7 = 'FF') AS n_ff_by_count_slot"
          + " FROM ("
          + "   SELECT multiIf("
          + "            pitch_type = 'FF', 'FF',"
          + "            pitch_type = 'SI', 'SI',"
          + "            pitch_type = 'FC', 'FC',"
          + "            pitch_type IN ('SL', 'ST', 'SV'), 'SL',"
          + "            pitch_type IN ('CU', 'KC', 'CS'), 'CU',"
          + "            pitch_type = 'CH', 'CH',"
          + "            'OFF'"
          + "          ) AS y7,"
          + "          (balls = ? AND strikes = ?) AS in_count"
          + "   FROM pitches_live FINAL"
          + "   WHERE pitcher_id = ?"
          + "     AND game_date > toDate(?)"
          + "     AND pitch_type NOT IN ('', 'PO', 'IN')"
          + "     AND (game_date, game_id, at_bat_index, pitch_number)"
          + "         < (toDate(?), toUInt64(?), toUInt16(?), toUInt8(?))"
          + " )";

  private static final RowMapper<PitcherPitchTypePrior> SNAPSHOT_MAPPER =
      (rs, n) ->
          new PitcherPitchTypePrior(
              rs.getDate("as_of_date").toLocalDate(),
              rs.getLong("prior_n"),
              rs.getLong("n_ff"),
              rs.getLong("n_si"),
              rs.getLong("n_fc"),
              rs.getLong("n_sl"),
              rs.getLong("n_cu"),
              rs.getLong("n_ch"),
              rs.getLong("n_off"),
              rs.getLong("prior_n_by_count_slot"),
              rs.getLong("n_ff_by_count_slot"));

  private static final RowMapper<PitcherPitchTypeDelta> DELTA_MAPPER =
      (rs, n) ->
          new PitcherPitchTypeDelta(
              rs.getLong("prior_n"),
              rs.getLong("n_ff"),
              rs.getLong("n_si"),
              rs.getLong("n_fc"),
              rs.getLong("n_sl"),
              rs.getLong("n_cu"),
              rs.getLong("n_ch"),
              rs.getLong("n_off"),
              rs.getLong("prior_n_by_count_slot"),
              rs.getLong("n_ff_by_count_slot"));

  private static final PitcherPitchTypeDelta EMPTY_DELTA =
      new PitcherPitchTypeDelta(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

  private final JdbcTemplate jdbc;

  public PitcherPitchTypePriorRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * The snapshot's by-count slot for a ball-strike count, ONE-BASED for ClickHouse.
   *
   * <p>The stored layout is {@code slot = balls * 3 + strikes} over {@code balls} 0..3 and {@code
   * strikes} 0..2, i.e. 0..11; ClickHouse arrays are one-indexed, hence the {@code + 1}. Both read
   * methods route through here so the writer's layout and the reader's index can only ever be
   * changed together.
   *
   * <p>Deliberately not range-checked. An impossible count (a parser bug upstream) yields an
   * out-of-range subscript, and ClickHouse returns the type default 0 for that rather than raising
   * - which composes into a 0 denominator and therefore a NULL / NaN {@code ars_FF_by_count}. A NaN
   * feature is handled natively by LightGBM and matches the cold-start path; throwing here would
   * turn a bad ball-strike count into a failed prediction instead.
   */
  static int countSlotOneBased(int balls, int strikes) {
    return balls * 3 + strikes + 1;
  }

  /**
   * The career-expanding count snapshot for one pitcher, with the by-count arrays already reduced
   * server-side to the slot for {@code (balls, strikes)}.
   *
   * <p>Empty for a pitcher the refresh job has never seen: a debut, or an id that has thrown no
   * labeled pitch. Empty is NOT the same as a zero row - it means there is no base at all, and the
   * caller decides whether the in-game delta alone is a legitimate basis (it is: a debut pitcher's
   * ARS is genuinely just their pitches so far, NULL until the first one lands).
   */
  public Optional<PitcherPitchTypePrior> findSnapshot(long pitcherId, int balls, int strikes) {
    int slot = countSlotOneBased(balls, strikes);
    List<PitcherPitchTypePrior> rows =
        jdbc.query(SELECT_SNAPSHOT, SNAPSHOT_MAPPER, slot, slot, pitcherId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  /**
   * This pitcher's labeled pitch counts strictly after {@code asOfDate} and strictly before the
   * pitch identified by {@code (gameDate, gameId, atBatIndex, pitchNumber)}, with the by-count
   * numbers restricted to the {@code (balls, strikes)} slot.
   *
   * <p>{@code asOfDate} MUST be the {@code as_of_date} of the snapshot this delta will be added to
   * ({@link PitcherPitchTypePrior#asOfDate()}). Passing anything else breaks the exactness of the
   * reconstruction: too early double-counts pitches the snapshot already has, too late drops
   * pitches neither side then covers.
   *
   * <p>PRECONDITION: {@code gameDate} must be strictly after {@code asOfDate}. For a pitch on or
   * before {@code asOfDate} the snapshot already contains that pitch and every later pitch of that
   * game, so there is nothing to add and the decomposition does not apply; this method would return
   * zeros and the composed ARS would be an OVERCOUNT. The serving path only predicts pitches in
   * games that have not yet been handed off, so the precondition holds there by construction.
   *
   * <p>Always returns a row (all zeros when the pitcher has thrown nothing since the snapshot);
   * never empty.
   */
  /**
   * Rebuild the whole snapshot, and report the {@code as_of_date} it anchored to.
   *
   * <p>Delegates to {@link PitcherPitchTypePriorSnapshotSql} rather than carrying its own copy of
   * the INSERT. That class exists precisely because the first version of this SQL lived only in the
   * parity test while claiming the job would match it - so the job would have inherited a query
   * ClickHouse cannot execute. One definition, exercised by the test, run by the job.
   *
   * @param y7Expression the training SQL's own class fold, passed through so a taxonomy change
   *     cannot desync training from serving.
   */
  public LocalDate refreshSnapshot(String y7Expression) {
    jdbc.execute(PitcherPitchTypePriorSnapshotSql.refreshInsert(y7Expression));
    LocalDate asOf =
        jdbc.queryForObject(
            "SELECT max(as_of_date) FROM pitcher_pitchtype_prior_current FINAL", LocalDate.class);
    return Objects.requireNonNull(asOf, "snapshot wrote no rows");
  }

  public PitcherPitchTypeDelta findInGameDelta(
      long pitcherId,
      LocalDate asOfDate,
      LocalDate gameDate,
      long gameId,
      int atBatIndex,
      int pitchNumber,
      int balls,
      int strikes) {
    // ISO-8601 Strings into toDate(?), never java.sql.Date: clickhouse-jdbc inlines a bare date
    // unquoted, and ClickHouse then evaluates the token 2026-07-25 as arithmetic (the
    // LivePitchesRepository#findGamesForDate lesson).
    String asOf = Objects.requireNonNull(asOfDate, "asOfDate").toString();
    String on = Objects.requireNonNull(gameDate, "gameDate").toString();
    List<PitcherPitchTypeDelta> rows =
        jdbc.query(
            SELECT_IN_GAME_DELTA,
            DELTA_MAPPER,
            balls,
            strikes,
            pitcherId,
            asOf,
            on,
            gameId,
            atBatIndex,
            pitchNumber);
    return rows.isEmpty() ? EMPTY_DELTA : rows.get(0);
  }
}
