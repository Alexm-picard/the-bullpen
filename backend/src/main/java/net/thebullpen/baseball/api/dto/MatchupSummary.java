package net.thebullpen.baseball.api.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One day's matchup for {@code GET /v1/matchups/today} - the computed {@code game_matchups} row
 * (lean + the two featured people + battle score + stage) joined to the team context (abbreviations
 * + start time) from {@code scheduled_games}. The list is ordered best battle first, so the home
 * Featured panel takes row 0 and the Tonight's board renders the rest by lean.
 */
public record MatchupSummary(
    long gameId,
    LocalDate gameDate,
    Instant gameTimeUtc,
    String homeTeam,
    String awayTeam,
    String lean, // "pitching" | "hitters" | "mixed"
    long homePlayerId,
    String homePlayerName,
    String homeRole, // "pitcher" | "hitter"
    long awayPlayerId,
    String awayPlayerName,
    String awayRole,
    double battleScore,
    String stage) {} // "default" | "lineup"
