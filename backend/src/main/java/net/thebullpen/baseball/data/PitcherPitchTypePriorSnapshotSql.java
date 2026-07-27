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

  /** Slot index for a count state, matching V030's documented {@code balls * 3 + strikes}. */
  public static final int SLOTS = 12;

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
        + "       (SELECT max(game_date) FROM pitches) AS as_of_date,\n"
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
        + "  FROM pitches FINAL\n"
        + "  WHERE pitch_type NOT IN ('', 'PO', 'IN')\n"
        + ")\n"
        + "GROUP BY pitcher_id\n";
  }

  private static String countIfArray(String predicatePrefix) {
    return IntStream.range(0, SLOTS)
        .mapToObj(s -> "countIf(" + predicatePrefix + s + ")")
        .collect(Collectors.joining(", ", "[", "]"));
  }
}
