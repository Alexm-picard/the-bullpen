package net.thebullpen.baseball.domain;

/**
 * The LIVE current-play matchup: who is standing in RIGHT NOW, from the feed's {@code
 * currentPlay.matchup} rather than from the last pitch thrown.
 *
 * <p>The distinction is the whole point. A last-pitch-derived "current batter" is one plate
 * appearance behind from the moment an at-bat ends until the next batter's first pitch lands, and
 * between half-innings it names a batter from the team now in the field. GUMBO updates {@code
 * currentPlay.matchup} the instant the next batter steps in, including {@code batSide} resolved
 * against the current pitcher - which is how a switch-hitter's side is known at all, and something
 * no static roster lookup can answer.
 *
 * <p>ABSENCE IS REAL AND FREQUENT, not an error: the feed carries no current play before first
 * pitch, in the gap after a completed play, and once the game is final. Absence is expressed by the
 * WHOLE RECORD being null - never by a half-populated one, because a consumer would reasonably
 * trust the fields it does see. Within a record every field is non-null: the compact constructor
 * normalises the side codes to {@code ""} (matching the V031 storage sentinel), and {@link
 * #isPopulated()} - TOTAL over the ids AND the at-bat index - is the single place that decides
 * whether a record describes a real matchup at all.
 *
 * <p>Pure record ({@code domain/} purity, ArchUnit-enforced): no Jackson, no Swagger annotations.
 * Nullability is documented here because it cannot be annotated.
 *
 * @param batterId MLB player id of the batter now standing in; {@code 0} (the repo-wide player-id
 *     absence sentinel, per V020/V022 and {@code MlbFeedParser.asLong}) when the feed has no
 *     current play or omitted the id. A record whose ids are 0 is absent, not a matchup.
 * @param pitcherId MLB player id of the pitcher now facing that batter; same 0 semantics.
 * @param batSide {@code "R"}, {@code "L"}, or {@code "S"} - the batter's side AS THE FEED REPORTS
 *     IT for this matchup. {@code ""} when unpopulated (the V031 {@code DEFAULT ''} idiom,
 *     mirroring V028), never null. {@code "S"} is a switch hitter the feed has not resolved;
 *     resolve it against {@link #pitchHand} downstream.
 * @param pitchHand {@code "R"} or {@code "L"}; {@code ""} when unpopulated.
 * @param atBatIndex the feed's at-bat index for the current play; null when unknown, which makes
 *     the whole record un-populated. NOT normalised to 0, because index 0 is a REAL at-bat (a
 *     game's first) and normalising would assert that specific one. Storage cannot express the null
 *     (the V031 column is non-Nullable), so a record read back always carries an index and the id
 *     checks alone decide absence there; null is reachable only in-flight. Pairs with {@code
 *     batterId} as the natural change key - a PINCH HITTER keeps the at-bat index and changes the
 *     batter, so neither field alone detects every matchup change.
 */
public record CurrentMatchup(
    Long batterId, Long pitcherId, String batSide, String pitchHand, Integer atBatIndex) {

  /**
   * Normalises the two side codes to {@code ""}, making this record's own contract true rather than
   * aspirational: the parser yields null for a missing code while the storage layer yields {@code
   * ""}, so without this an in-flight record and the one read back would not be {@link #equals}
   * despite describing the same matchup.
   */
  public CurrentMatchup {
    batSide = batSide == null ? "" : batSide;
    pitchHand = pitchHand == null ? "" : pitchHand;
  }

  /**
   * True when this carries an actually-usable matchup - both ids present and non-zero, AND the
   * at-bat index known.
   *
   * <p>Zero is the parser's missing-id value ({@code JsonNode.asLong()} yields {@code 0L}, not
   * null), so it is checked here rather than trusted: a matchup naming batter 0 is an absent
   * matchup wearing a number.
   */
  public boolean isPopulated() {
    // TOTAL over every field the consumers rely on, including atBatIndex - which is not
    // fastidiousness but the guard that keeps the write path safe: `usable ? atBatIndex() : 0`
    // mixes Integer with int, so binary numeric promotion UNBOXES, and a record with real ids and
    // a null index would pass a partial guard and then NPE. Guarding here rather than normalising
    // to 0 in the constructor also keeps the honest semantics: at-bat 0 is a REAL at-bat (a
    // game's first), so a null index means "we do not know which at-bat", and a matchup whose
    // at-bat is unknown is not a usable matchup. It is also what makes the frontend's non-null
    // `atBatIndex: number` true, which matters - its `matchup.atBatIndex === row.atBatIndex`
    // comparison is load-bearing.
    return batterId != null
        && batterId != 0L
        && pitcherId != null
        && pitcherId != 0L
        && atBatIndex != null;
  }
}
