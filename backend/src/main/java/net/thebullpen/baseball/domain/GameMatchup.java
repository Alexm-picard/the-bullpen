package net.thebullpen.baseball.domain;

import java.time.LocalDate;

/**
 * The computed matchup for one game (Phase 2c): the lean, the two featured people (id/name/role on
 * each side), a battle score for ranking the slate (highest = the "best battle" -> Featured panel),
 * and the stage ({@code "default"} = morning pitcher-vs-pitcher, {@code "lineup"} = the
 * lineup-aware re-classification). Pure data; persisted to {@code game_matchups} and served by
 * {@code /v1/matchups}.
 */
public record GameMatchup(
    long gameId,
    LocalDate gameDate,
    String lean, // "pitching" | "hitters" | "mixed"
    long homePlayerId,
    String homePlayerName,
    String homeRole, // "pitcher" | "hitter"
    long awayPlayerId,
    String awayPlayerName,
    String awayRole,
    double battleScore,
    String stage) {} // "default" | "lineup"
