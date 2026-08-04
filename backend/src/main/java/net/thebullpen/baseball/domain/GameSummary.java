package net.thebullpen.baseball.domain;

import java.time.LocalDate;

/**
 * Top-of-page summary for /v1/games/today and /v1/games/{id} (leaf 4d.1).
 *
 * <p>Carries enough for the UI to render a game header (teams + score + status + when) and decide
 * its polling cadence ({@code status} maps to {@code GameStatus.pollInterval()}).
 *
 * <p>{@code currentMatchup} is NULL whenever the feed has no current play - before first pitch, in
 * the gap after a completed play, once the game is final, and on every row written before V031. It
 * is populated on BOTH endpoints that return this record (the slate query joins the same status
 * row), so the field means the same thing everywhere rather than being silently endpoint-specific.
 * Consumers must treat null as "fall back to whatever you knew before", never as an error - the
 * game page keeps its last-pitch derivation as exactly that fallback.
 */
public record GameSummary(
    long gameId,
    LocalDate gameDate,
    String homeTeam,
    String awayTeam,
    int homeScore,
    int awayScore,
    int inning,
    String status,
    String detailedState,
    CurrentMatchup currentMatchup,
    RecentBattedBall mostRecentBattedBall) {

  /**
   * Same summary with the batted ball attached - the repository composes it from a second query.
   */
  public GameSummary withMostRecentBattedBall(RecentBattedBall bb) {
    return new GameSummary(
        gameId,
        gameDate,
        homeTeam,
        awayTeam,
        homeScore,
        awayScore,
        inning,
        status,
        detailedState,
        currentMatchup,
        bb);
  }
}
