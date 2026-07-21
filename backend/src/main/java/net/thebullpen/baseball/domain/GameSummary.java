package net.thebullpen.baseball.domain;

import java.time.LocalDate;

/**
 * Top-of-page summary for /v1/games/today and /v1/games/{id} (leaf 4d.1).
 *
 * <p>Carries enough for the UI to render a game header (teams + score + status + when) and decide
 * its polling cadence ({@code status} maps to {@code GameStatus.pollInterval()}).
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
    String detailedState) {}
