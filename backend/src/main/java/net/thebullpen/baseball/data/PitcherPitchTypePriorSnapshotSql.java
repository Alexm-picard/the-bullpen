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
   * FINAL, deduped on the full pitch identity with the historical leg winning overlaps and the
   * newest read winning same-source duplicates. Mirrors the sibling in {@code
   * PitcherFormRepository} exactly, minus the {@code toString(description)} cast that one needs
   * (there {@code description} is Enum8 vs LowCardinality; here {@code pitch_type} is
   * LowCardinality(String) in BOTH tables, verified against V003 and V015).
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
   * typed pitches rather than overstating coverage from a batch of untyped rows.
   */
  private static String unionSource() {
    return "  SELECT game_date, game_id, at_bat_index, pitch_number, pitcher_id,"
        + " balls, strikes, pitch_type, ingested_at, 1 AS src\n"
        + "  FROM pitches FINAL\n"
        + "  WHERE pitch_type NOT IN ('', 'PO', 'IN') AND game_date <= "
        + TODAY_ET
        + " - 1\n"
        + "  UNION ALL\n"
        + "  SELECT game_date, game_id, at_bat_index, pitch_number, pitcher_id,"
        + " balls, strikes, pitch_type, ingested_at, 2 AS src\n"
        + "  FROM pitches_live FINAL\n"
        + "  WHERE pitch_type NOT IN ('', 'PO', 'IN') AND game_date <= "
        + TODAY_ET
        + " - 1\n";
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
        + "  SELECT pitcher_id, balls * 3 + strikes AS slot, "
        + y7Expression
        + " AS y7\n"
        + "  FROM (\n"
        + unionSource()
        + "  )\n"
        + "  ORDER BY src, ingested_at DESC\n"
        + "  LIMIT 1 BY game_date, game_id, at_bat_index, pitch_number\n"
        + ")\n"
        + "GROUP BY pitcher_id\n";
  }

  private static String countIfArray(String predicatePrefix) {
    return IntStream.range(0, SLOTS)
        .mapToObj(s -> "countIf(" + predicatePrefix + s + ")")
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
