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
 * pitch, in the gap after a completed play, and once the game is final. Every field here is
 * therefore nullable-by-contract, and the api omits the whole record rather than emitting a
 * half-populated one - a partial matchup would be worse than none, because a consumer would
 * reasonably trust the fields it does see.
 *
 * <p>Pure record ({@code domain/} purity, ArchUnit-enforced): no Jackson, no Swagger annotations.
 * Nullability is documented here because it cannot be annotated.
 *
 * @param batterId MLB player id of the batter now standing in; null when the feed has no current
 *     play (pre-game, between plays, final) or omitted the id.
 * @param pitcherId MLB player id of the pitcher now facing that batter; same null semantics.
 * @param batSide {@code "R"}, {@code "L"}, or {@code "S"} - the batter's side AS THE FEED REPORTS
 *     IT for this matchup. {@code ""} when unpopulated (the V031 {@code DEFAULT ''} idiom,
 *     mirroring V028), never null. {@code "S"} is a switch hitter the feed has not resolved;
 *     resolve it against {@link #pitchHand} downstream.
 * @param pitchHand {@code "R"} or {@code "L"}; {@code ""} when unpopulated.
 * @param atBatIndex the feed's at-bat index for the current play; null when absent. Pairs with
 *     {@code batterId} as the natural change key - a PINCH HITTER keeps the at-bat index and
 *     changes the batter, so neither field alone detects every matchup change.
 */
public record CurrentMatchup(
    Long batterId, Long pitcherId, String batSide, String pitchHand, Integer atBatIndex) {

  /**
   * True when this carries an actually-usable matchup - both ids present and non-zero.
   *
   * <p>Zero is the parser's missing-id value ({@code JsonNode.asLong()} yields {@code 0L}, not
   * null), so it is checked here rather than trusted: a matchup naming batter 0 is an absent
   * matchup wearing a number.
   */
  public boolean isPopulated() {
    return batterId != null && batterId != 0L && pitcherId != null && pitcherId != 0L;
  }
}
