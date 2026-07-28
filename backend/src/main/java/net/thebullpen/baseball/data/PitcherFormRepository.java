package net.thebullpen.baseball.data;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Writer for {@code pitcher_form_current} (V007): the denormalised current Tier-3 form snapshot the
 * live pitch path reads by a single point lookup, instead of re-scanning ~50M pitch rows per
 * request (V007 doc).
 *
 * <p>DP2 / WS3. The nightly refresh recomputes each active pitcher's 28-day form FRESH from the
 * {@code pitches} UNION {@code pitches_live} source (decision [186]), NOT from the training {@code
 * features} table: that table is the per-fold historical store written during CV runs, so reading
 * it would serve stale form. The strike / swstrike / in-play definitions mirror {@code
 * compute_tier3.sql} EXACTLY so the live snapshot is consistent with what the model trained on -
 * pinned by the parity test in {@code PitcherFormRepositoryIT}, which runs the real training SQL
 * from disk against the same fixture.
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} (NOT
 * {@code @ConditionalOnBean(clickhouseDataSource)} - that bean-ordering guard crash-looped the
 * worker for ~4 days post-2026-05-31; see DriftMetricsRepository). On both {@code api} + {@code
 * worker} so the docker-gated IT can wire it under the api profile, but only the worker {@code
 * PitcherFormRefreshJob} actually calls it.
 */
@Repository
@Profile({"api", "worker"})
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherFormRepository {

  /**
   * The shared window SOURCE (decision [186]): the UNION of {@code pitches} (manually-backfilled
   * historical corpus) and {@code pitches_live} (live surface, 14-day TTL), deduped on the
   * canonical pitch key {@code (game_id, at_bat_index, pitch_number)} - identical names and types
   * in both tables by V015's explicit design. Dedup mechanics: each leg is FINAL (both are
   * ReplacingMergeTrees; the live leg's re-polled corrections would double-count without it), legs
   * are tagged with a source priority, and {@code ORDER BY src LIMIT 1 BY <key>} keeps the
   * HISTORICAL row when a future backfill overlaps the live window - the curated corpus wins.
   *
   * <p>{@code toString(description)} on the pitches leg: pitches stores an Enum8, pitches_live a
   * LowCardinality(String) - the cast is explicit rather than trusting implicit supertype
   * resolution across UNION ALL.
   *
   * <p>BOTH bounds are the strictly-before contract [186] preserves: {@code <= today() - 1} matches
   * training's {@code RANGE ... 1 PRECEDING} (exclude the current day). Under the old pitches-only
   * source this bound was academic (the corpus never held today); with the live leg it is
   * LOAD-BEARING - an intra-day {@code runOnce} would otherwise pull today's in-progress pitches
   * into the 28d rates, which training never sees. Today's pitches are the intra-day upsert's job,
   * not this window's.
   */
  private static final String WINDOW_SOURCE =
      "SELECT pitcher_id, game_date, description FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number, pitcher_id, game_date,"
          + "          toString(description) AS description, 1 AS src"
          + "   FROM pitches FINAL"
          + "   WHERE description != 'unknown'"
          + "     AND game_date >= today() - 28 AND game_date <= today() - 1"
          + "   UNION ALL"
          + "   SELECT game_id, at_bat_index, pitch_number, pitcher_id, game_date,"
          + "          description, 2 AS src"
          + "   FROM pitches_live FINAL"
          + "   WHERE description != 'unknown'"
          + "     AND game_date >= today() - 28 AND game_date <= today() - 1"
          // The dedup key includes game_date: pitches' OWN ReplacingMergeTree identity is
          // (game_date, game_id, at_bat_index, pitch_number), and a 3-part key would merge
          // distinct-by-date rows sharing a game_id - data real feeds should never produce but
          // the schema permits, and the union must not be STRICTER than the table's identity.
          // (The parity test caught exactly this: a fixture reusing game_id across dates lost
          // rows to the 3-part key.) The same real pitch carries the same game_date in both
          // tables, so cross-table overlap still dedups.
          + " ) ORDER BY src LIMIT 1 BY game_date, game_id, at_bat_index, pitch_number";

  /**
   * The anchor: newest usable game_date across the UNION, bounded strictly-before like the window.
   * Textually shared by REFRESH, COUNT_AT_ANCHOR, and {@link #windowSourceMaxGameDate()} (evaluated
   * independently in each; a backfill landing mid-run can skew the count and log line, never the
   * data - accepted, the 02:40 ET slot).
   */
  private static final String ANCHOR_SUBQUERY =
      "(SELECT max(game_date) FROM ("
          + "   SELECT game_date FROM pitches"
          + "    WHERE description != 'unknown' AND game_date <= today() - 1"
          + "   UNION ALL"
          + "   SELECT game_date FROM pitches_live"
          + "    WHERE description != 'unknown' AND game_date <= today() - 1"
          + " ))";

  private static final String REFRESH =
      "INSERT INTO pitcher_form_current"
          + " (pitcher_id, as_of_date, pitches_in_game, pitches_last_28d,"
          + "  strike_rate_28d, swstrike_rate_28d, inplay_rate_28d, days_since_last_appearance)"
          // ANCHORED TO THE DATA, NOT THE CLOCK (2026-07-27 finding), and since [186] the data is
          // the UNION: this job once stamped as_of_date = today() while its windows read pitches
          // alone, a manually-backfilled corpus (last backfill RAN 2026-05-28, loading games
          // THROUGH 2026-05-25 - two numbers, write time vs coverage, kept distinct because a
          // reviewer already mistook one for a typo of the other) - so the stamp said "fresh"
          // while the 28-day window had selected nothing for two months, and the PRE head
          // silently served NaN form to most of the cohort. The sibling V030 job's data-anchored
          // refusal is what surfaced the defect; [186] resolves it by reading the union (live
          // rows fill the recent window between backfills) with the anchor tracking what the
          // union actually covers. Known accepted limit ([186]): pitches_live's 14-day TTL means
          // a gap older than 14 days but after the last backfill stays invisible until the next
          // backfill - the age gauge is what surfaces that, honestly, rather than the union
          // pretending completeness.
          + " SELECT pitcher_id,"
          + "        "
          + ANCHOR_SUBQUERY
          + " AS as_of_date,"
          + "        0 AS pitches_in_game,"
          + "        toUInt32(count()) AS pitches_last_28d,"
          + "        toFloat32(countIf(description IN ('called_strike','swinging_strike','foul'))"
          + "                  / count()) AS strike_rate_28d,"
          + "        toFloat32(countIf(description = 'swinging_strike') / count())"
          + "                  AS swstrike_rate_28d,"
          + "        toFloat32(countIf(description = 'in_play') / count()) AS inplay_rate_28d,"
          + "        toUInt16(today() - max(game_date)) AS days_since_last_appearance"
          + " FROM ("
          + WINDOW_SOURCE
          + ")"
          + " GROUP BY pitcher_id";

  // Counts at the DATA anchor, not today(). The old today()-based count reported live-leg strays
  // as "refreshed" pitchers while the nightly window selected nothing - the morning this was
  // found, it said "refreshed 7" when the true nightly answer was 0. With a stale corpus this now
  // returns an honest zero. (Non-FINAL + DISTINCT: the refresh writes one row per pitcher at the
  // anchor; carried-forward intra-day rows at the same anchor dedup via DISTINCT. When the corpus
  // is static across days, this counts the cohort AT the anchor, not just rows written this run -
  // acceptable for a log line, noted so nobody reads it as a write count.)
  // The subquery is the SHARED anchor constant - the same string the REFRESH stamps with - or the
  // two could disagree and the count would miss the rows the refresh just wrote.
  private static final String COUNT_AT_ANCHOR =
      "SELECT count(DISTINCT pitcher_id) FROM pitcher_form_current"
          + " WHERE as_of_date = "
          + ANCHOR_SUBQUERY;

  private final JdbcTemplate jdbc;

  public PitcherFormRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Recompute and insert a current-form row for every pitcher active in the trailing 28-day window
   * of the pitches UNION pitches_live source ([186]), stamped at the UNION ANCHOR (the newest
   * usable game_date across both tables) rather than today.
   *
   * <p>Returns the count of distinct pitchers with a row AT that anchor - the cohort at the anchor,
   * NOT a write count (see COUNT_AT_ANCHOR's caveat: rows from earlier nights at the same anchor
   * are included). The ReplacingMergeTree dedups on {@code pitcher_id} keeping the newest {@code
   * ingested_at}, so repeat nights at a static anchor compact away.
   *
   * <p>Coherence note: {@code as_of_date} describes the 28-day WINDOW columns. {@code
   * days_since_last_appearance} is deliberately serving-clock-based (today minus the last game), so
   * under a stale corpus a row can carry an old {@code as_of_date} with a large dsla - two true
   * statements about different clocks, not a contradiction.
   */
  public long refreshCurrentForm() {
    jdbc.update(REFRESH);
    Long n = jdbc.queryForObject(COUNT_AT_ANCHOR, Long.class);
    return n == null ? 0L : n;
  }

  /**
   * The newest USABLE {@code game_date} across the UNION ([186]) - what the nightly windows can
   * possibly see, and therefore the number the freshness gauge is built from. In steady state the
   * live leg keeps this at ~yesterday; if the poller stops AND the corpus is stale, the gap grows
   * and the gauge is what says so. ({@code pitches} alone is a MANUALLY BACKFILLED corpus - last
   * backfill ran 2026-05-28, loading games through 2026-05-25; no live handoff job exists, per
   * [186] deliberately.)
   *
   * <p>Same shared anchor constant as the REFRESH stamp, deliberately - the gauge must measure
   * exactly what the windows read.
   *
   * <p>ClickHouse trap, handled rather than discovered: {@code max()} over an empty source returns
   * the type default (1970-01-01), NOT NULL, so both are treated as the same fault.
   */
  public LocalDate windowSourceMaxGameDate() {
    LocalDate d = jdbc.queryForObject("SELECT " + ANCHOR_SUBQUERY, LocalDate.class);
    if (d == null || !d.isAfter(LocalDate.EPOCH)) {
      throw new IllegalStateException(
          "the pitches UNION pitches_live window source is empty (max(game_date) returned "
              + d
              + "); the Tier-3 form windows have nothing to read");
    }
    return d;
  }

  // --- live read path (WS3 A3.1) --------------------------------------------

  private static final String SELECT_CURRENT_FORM =
      "SELECT pitches_in_game, pitches_last_28d, strike_rate_28d, swstrike_rate_28d,"
          + "       inplay_rate_28d, days_since_last_appearance"
          + " FROM pitcher_form_current FINAL"
          + " WHERE pitcher_id = ?";

  private static final RowMapper<PitcherForm> FORM_MAPPER =
      (rs, n) -> {
        long dsla = rs.getLong("days_since_last_appearance");
        return new PitcherForm(
            rs.getDouble("pitches_in_game"),
            rs.getDouble("pitches_last_28d"),
            rs.getDouble("strike_rate_28d"),
            rs.getDouble("swstrike_rate_28d"),
            rs.getDouble("inplay_rate_28d"),
            rs.wasNull() ? null : (double) dsla);
      };

  /**
   * Current Tier-3 form for one pitcher, or empty if no row exists (a pitcher the nightly refresh
   * never saw - a debut or someone with no pitch in the last 28 days). {@code FINAL} because
   * pitcher_form_current is a ReplacingMergeTree: the latest {@code ingested_at} row (nightly, or
   * an intra-day upsert) is the one to read (the dsla-gate ghost lesson).
   */
  public Optional<PitcherForm> findCurrent(long pitcherId) {
    List<PitcherForm> rows = jdbc.query(SELECT_CURRENT_FORM, FORM_MAPPER, pitcherId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  // --- intra-day upsert (WS3 A3.2) ------------------------------------------

  // Carry the nightly 28-day window values forward unchanged and update ONLY the two intra-day
  // signals: pitches_in_game (live count of this pitcher's pitches in tonight's game) and
  // days_since_last_appearance = 0 (they are appearing today). ReplacingMergeTree(ingested_at)
  // keyed on pitcher_id: this new row supersedes the prior one on read. A pitcher with no existing
  // row produces no insert (the SELECT is empty) - missing stays missing, never fabricated.
  //
  // Leakage-clean: pitches_in_game counts only pitches already in pitches_live (ts <= now); a live
  // upsert satisfies the streaming temporal cutoff trivially (no future data).
  private static final String UPSERT_INTRA_DAY =
      "INSERT INTO pitcher_form_current"
          + " (pitcher_id, as_of_date, pitches_in_game, pitches_last_28d,"
          + "  strike_rate_28d, swstrike_rate_28d, inplay_rate_28d, days_since_last_appearance)"
          // Carries as_of_date FORWARD rather than stamping today(): the column describes what
          // the 28-day window values cover, and this row carries those values unchanged - the
          // intra-day fields are self-evidently current. Stamping today() was also what made the
          // clock-anchored stamp look fresh table-wide while the nightly leg was dead. Bonus:
          // the replacement now lands in the SAME partition as the row it supersedes
          // (PARTITION BY toYYYYMM(as_of_date)), so background merges can actually collapse the
          // pair - ReplacingMergeTree merges never cross partitions, and the today() stamp
          // scattered a pitcher's replacements across monthly partitions forever, hidden only by
          // read-time FINAL.
          + " SELECT pitcher_id, as_of_date,"
          // FINAL on the live count ([186] PR-1 rider): pitches_live is a ReplacingMergeTree on
          // ingested_at, so a re-polled correction of the same pitch used to double-count here -
          // a latent defect the union scout surfaced (LivePitchesRepository already reads FINAL).
          + "        (SELECT toUInt32(count()) FROM pitches_live FINAL"
          + "         WHERE game_id = ? AND pitcher_id = ?) AS pitches_in_game,"
          + "        pitches_last_28d, strike_rate_28d, swstrike_rate_28d, inplay_rate_28d,"
          + "        toUInt16(0)"
          + " FROM pitcher_form_current FINAL"
          + " WHERE pitcher_id = ?";

  /**
   * Refresh one active pitcher's intra-day signals during a live game: set {@code pitches_in_game}
   * to their live count in {@code gameId} and {@code days_since_last_appearance} to 0, carrying the
   * nightly 28-day window forward. No-op for a pitcher with no current row.
   */
  public void upsertIntraDayForm(long pitcherId, long gameId) {
    jdbc.update(UPSERT_INTRA_DAY, gameId, pitcherId, pitcherId);
  }
}
