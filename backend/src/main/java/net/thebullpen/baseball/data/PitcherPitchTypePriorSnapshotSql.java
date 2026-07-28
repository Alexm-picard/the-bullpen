package net.thebullpen.baseball.data;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The one definition of how {@code pitcher_pitchtype_prior_current} is populated.
 *
 * <p>IT LIVES HERE BECAUSE THERE MUST BE EXACTLY ONE. The nightly refresh job and the parity test
 * both need this INSERT, and a test whose javadoc claims it populates the snapshot "exactly as the
 * job will" is worth nothing if they are two copies of the SQL. The first version of this was
 * written only in the test, and the defect below would therefore have shipped in the job.
 *
 * <p>WHY TWELVE EXPLICIT AGGREGATES RATHER THAN arrayMap. The obvious shape, {@code arrayMap(s ->
 * countIf(balls * 3 + strikes = s), range(12))}, does not work: ClickHouse evaluates {@code
 * countIf} as an aggregate over the GROUP BY block, where the lambda parameter {@code s} is not a
 * column, and fails with {@code NOT_FOUND_COLUMN_IN_BLOCK}. It is an unsupported construct rather
 * than a syntax slip, so no amount of quoting fixes it.
 *
 * <p>Twelve literal {@code countIf}s are verbose and cannot silently misalign. The arrays are
 * fixed-width 12 and the whole reason the snapshot stores counts rather than ratios is exactness,
 * so an idiom where an off-by-one could hide in a reconstruction step (sumMap plus arrayMap) is the
 * wrong trade here. The V030 CHECK constraints on length catch a short array; nothing would catch a
 * shifted one.
 */
public final class PitcherPitchTypePriorSnapshotSql {

  private PitcherPitchTypePriorSnapshotSql() {}

  /**
   * The canonical y7 class fold, lifted verbatim from {@code compute_pitch_type_arsenal.sql}.
   *
   * <p>A CONSTANT PINNED BY TEST rather than configuration. The backend cannot read the training
   * SQL at runtime (it does not ship to the box), and a config value would let the taxonomy drift
   * silently - a wrong fold yields a plausible snapshot, not an obviously broken one, which is the
   * failure mode this whole family keeps producing. {@code PitchTypeArsenalParityIT} asserts this
   * string still equals the fold in the training SQL, so a taxonomy change reds a test.
   */
  public static final String CANONICAL_Y7 =
      "multiIf( pitch_type = 'FF', 'FF', pitch_type = 'SI', 'SI', pitch_type = 'FC', 'FC', pitc"
          + "h_type IN ('SL', 'ST', 'SV'), 'SL', pitch_type IN ('CU', 'KC', 'CS'), 'CU', pitch_type ="
          + " 'CH', 'CH', 'OFF' )";

  /** Slot index for a count state, matching V030's documented {@code balls * 3 + strikes}. */
  public static final int SLOTS = 12;

  /**
   * The date basis, {@code America/New_York}. NOT ClickHouse {@code today()}, which resolves in the
   * SERVER timezone (UTC in prod) while this job's schedule is ET - between 20:00 and 23:59 ET
   * those disagree by a day. Deliberately duplicated from {@code PitcherFormRepository}'s constant
   * rather than shared: the two land in separate [186] PRs and a shared holder would have made them
   * a merge conflict. Unify once both are on main; until then they must agree by inspection.
   */
  private static final String TODAY_ET = "toDate(now('America/New_York'))";

  /**
   * The [186] window SOURCE for the career prior: {@code pitches} UNION {@code pitches_live}, both
   * FINAL, deduped on the full pitch identity with the HISTORICAL leg winning overlaps.
   *
   * <p>ONLY THE OVERLAP IS DEDUPED, and that is a scale requirement rather than an optimisation.
   * The sibling in {@code PitcherFormRepository} can sort its whole union because a 28-day rolling
   * window caps it; this feature is CAREER-EXPANDING, so a global {@code ORDER BY} + {@code LIMIT 1
   * BY} sorts every pitch ever thrown. Measured by ml-leakage-auditor on a 25.4M-row corpus: 3.36
   * GiB peak and 7-8s global, versus 70 MiB and under a second scoped, with byte-identical output
   * across all pitcher rows. Prod is ~50M rows (V030 header) under decision [179]'s 6g cap with a
   * ~2.2 GiB idle parts baseline, and {@code max_bytes_before_external_sort} is 0 on 24.12 - so the
   * global form does not degrade, it throws MEMORY_LIMIT_EXCEEDED, which the refresh job swallows
   * into a log line and the freshness gauge cannot see (it only advances on success). Two days
   * later the deriver refuses every pitch-type prediction: the exact serving outage the bound below
   * exists to prevent, reached through a door the same change would have opened. This is the C-31
   * saga's failure class ({@code docs/postmortems/2026-07-15_c31-retrain-saga.md}).
   *
   * <p>The two legs can only collide where the live table has rows, so everything strictly older
   * than {@code min(game_date)} in {@code pitches_live} is UNION ALL'd straight in, undeduped, and
   * only the overlap window pays for a sort. Correctness is unchanged: a pitch outside that window
   * cannot exist in both tables.
   *
   * <p>No {@code toString} cast here, unlike the sibling: {@code pitch_type} is
   * LowCardinality(String) in BOTH tables (verified against V003 and V015), where {@code
   * description} is Enum8 in {@code pitches}.
   *
   * <p>THE UPPER BOUND IS A CORRECTNESS REQUIREMENT HERE, not only training parity, and the reason
   * is specific to this snapshot: the serving composition is snapshot + in-game delta, where {@code
   * SELECT_IN_GAME_DELTA} takes {@code game_date > as_of_date}. The two meet exactly at the anchor.
   * Aggregating rows the anchor does not cover would break that meet - and if the anchor itself
   * reached today, {@code PitchTypeArsenalDeriver.assertDeltaWindowIsValid} would refuse EVERY
   * pitch of every game played that day (gameDate is not after as_of_date), turning a freshness win
   * into a total serving outage. Bounding both the aggregate and the anchor at {@code TODAY_ET - 1}
   * keeps the snapshot strictly behind the delta's window.
   *
   * <p>Untyped live rows drop out on their own: GUMBO leaves {@code pitch_type} empty until it
   * types a pitch, and {@code NOT IN ('', 'PO', 'IN')} - the same filter the training SQL uses -
   * excludes them. The anchor carries that filter too, so it reports the newest date with USABLE
   * typed pitches rather than overstating coverage from a batch of untyped rows (an unfiltered
   * anchor can outrun the aggregate on a day whose only live rows are untyped, stranding them in
   * neither leg once GUMBO types them - isolated and confirmed by the auditor's probe).
   *
   * <p>ASSUMED DATA-MODEL INVARIANT, stated because the code depends on it silently: the dedup key
   * omits {@code pitcher_id}, so it assumes one pitch key belongs to exactly one pitcher. True for
   * real feeds; a {@code game_id} collision between the historical and live id spaces, or an
   * upstream parser bug, would drop a pitcher's row rather than double-count it. Nothing exercises
   * that case - the parity fixture deliberately offsets its decoy's game_ids to AVOID it.
   *
   * <p>COVERAGE IS NOT CONTIGUITY, and V030's own header section (c) ("the snapshot covers (-inf,
   * as_of_date]") is now too strong: the union's coverage is the corpus range UNION the live TTL
   * window, and between them sits whatever the last manual backfill did not reach. The date ranges
   * of snapshot and delta still meet exactly at the anchor - no gap, no double count in the DATE
   * dimension - but rows can be missing inside the snapshot's own range. That hole is what {@code
   * PitcherPitchTypePriorRepository.coverageGapDays()} measures and the {@code
   * bullpen_pitchtype_prior_coverage_gap_days} gauge exposes; the migration file is immutable once
   * applied, so this is where the correction lives.
   *
   * <p>{@code pitches_live FINAL} is load-bearing and NOT redundant, for a reason specific to this
   * table: its own ORDER BY is 3-part {@code (game_id, at_bat_index, pitch_number)} with no {@code
   * game_date}, so the 4-part dedup key here is STRICTER than the table's identity. A suspended
   * game re-reported under the same {@code game_pk} on a different date is two distinct rows to
   * {@code LIMIT 1 BY} and only FINAL collapses them (probe: 2 rows vs 1). The {@code ingested_at
   * DESC} tiebreak, by contrast, is DEFENSIVE ONLY - with both legs FINAL each source yields at
   * most one row per key, so {@code src} alone decides. It stays so that losing a leg's FINAL
   * degrades to the same answer rather than an arbitrary one.
   */
  /**
   * The live-floor scalar, extracted so a test can assert on the EXPRESSION rather than on its
   * consequences. That distinction is the whole reason it is here: the guard's failure mode is
   * WHICH LEG RUNS, not which rows come out, and at fixture scale both legs return the same answer
   * - so a snapshot-content test cannot see a broken guard (verified by mutation: the dead ifNull
   * form passes every content assertion in the parity IT). Only evaluating the expression against
   * an empty table catches it.
   */
  static final String LIVE_FLOOR =
      "(SELECT if(count() = 0, toDate('2149-06-06'), min(game_date)) FROM pitches_live)";

  private static String unionSource(String y7Expression) {
    String projection =
        "SELECT pitcher_id, balls * 3 + strikes AS slot, " + y7Expression + " AS y7";
    String filter = "pitch_type NOT IN ('', 'PO', 'IN') AND game_date <= " + TODAY_ET + " - 1";
    // EMPTY pitches_live -> live_floor is the far future, so the whole corpus takes the cheap
    // undeduped leg and the overlap leg matches nothing. The guard is an explicit count(), NOT
    // ifNull(min(...)): ClickHouse min() over an EMPTY non-Nullable column returns the TYPE
    // DEFAULT (1970-01-01), never NULL, so ifNull cannot fire - it would leave live_floor at the
    // epoch, match NOTHING on the cheap leg, and send the entire career corpus through the global
    // sort this whole rewrite exists to avoid (measured 3.16 GiB / 6-8s vs 56 MiB / 0.24s). That
    // is not a rare edge: pitches_live has a 14-day TTL, so it is empty for the entire OFFSEASON
    // (~100 consecutive nightly refreshes), through any 14-day ingest outage, and on a fresh or
    // just-restored box. The same trap is called out on corpusMaxGameDate's sibling in
    // PitcherFormRepository - it was documented before it was walked into here.
    // 2149-06-06 is Date's actual maximum (UInt16 days from epoch); a larger literal silently
    // saturates to it, so write the reachable value.
    return "WITH "
        + LIVE_FLOOR
        + " AS live_floor\n"
        + projection
        + "\n  FROM pitches FINAL\n"
        + "  WHERE "
        + filter
        + " AND game_date < live_floor\n"
        + "UNION ALL\n"
        + projection
        + "\n  FROM (\n"
        + "    SELECT game_date, game_id, at_bat_index, pitch_number, pitcher_id,"
        + " balls, strikes, pitch_type, ingested_at, 1 AS src\n"
        + "    FROM pitches FINAL\n"
        + "    WHERE "
        + filter
        + " AND game_date >= live_floor\n"
        + "    UNION ALL\n"
        + "    SELECT game_date, game_id, at_bat_index, pitch_number, pitcher_id,"
        + " balls, strikes, pitch_type, ingested_at, 2 AS src\n"
        + "    FROM pitches_live FINAL\n"
        + "    WHERE "
        + filter
        + " AND game_date >= live_floor\n"
        + "  )\n"
        + "  ORDER BY src, ingested_at DESC\n"
        + "  LIMIT 1 BY game_date, game_id, at_bat_index, pitch_number\n";
  }

  /** Newest usable game_date across the same bounded, filtered union the aggregate reads. */
  private static String anchor() {
    return "(SELECT max(game_date) FROM (\n"
        + "  SELECT game_date FROM pitches\n"
        + "   WHERE pitch_type NOT IN ('', 'PO', 'IN') AND game_date <= "
        + TODAY_ET
        + " - 1\n"
        + "  UNION ALL\n"
        + "  SELECT game_date FROM pitches_live\n"
        + "   WHERE pitch_type NOT IN ('', 'PO', 'IN') AND game_date <= "
        + TODAY_ET
        + " - 1\n"
        + "))";
  }

  /**
   * The refresh INSERT.
   *
   * @param y7Expression the class fold, which MUST be the training SQL's own multiIf. Passed in
   *     rather than duplicated so a taxonomy change cannot desync training from serving.
   */
  public static String refreshInsert(String y7Expression) {
    // Concatenated rather than a text block through String.formatted: SpotBugs' FS rule flags the
    // newlines inside a text block used as a format string, and suppressing a real static-analysis
    // rule to keep a nicer-looking literal is the wrong trade.
    return "INSERT INTO pitcher_pitchtype_prior_current\n"
        + "SELECT pitcher_id,\n"
        + "       "
        + anchor()
        + " AS as_of_date,\n"
        + "       count() AS prior_n,\n"
        + "       countIf(y7 = 'FF'), countIf(y7 = 'SI'), countIf(y7 = 'FC'), countIf(y7 = 'SL'),\n"
        + "       countIf(y7 = 'CU'), countIf(y7 = 'CH'), countIf(y7 = 'OFF'),\n"
        + "       "
        + countIfArray("slot = ")
        + ",\n"
        + "       "
        + countIfArray("y7 = 'FF' AND slot = ")
        + ",\n"
        + "       now64(3)\n"
        + "FROM (\n"
        + unionSource(y7Expression)
        + ")\n"
        + "GROUP BY pitcher_id\n";
  }

  private static String countIfArray(String predicatePrefix) {
    return IntStream.range(0, SLOTS)
        .mapToObj(s -> "countIf(" + predicatePrefix + s + ")")
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
