package net.thebullpen.baseball.ingest;

import java.time.Instant;

/**
 * One game from the MLB Stats API schedule endpoint ({@code /api/v1/schedule}): identity, lifecycle
 * status, the two teams, and the scheduled first pitch. The live poller uses {@code gamePk} +
 * {@code status} for discovery (which games exist today and whether each is pollable - {@link
 * GameStatus#shouldPoll()}); the team names/abbreviations + {@code gameTimeUtc} are persisted to
 * {@code scheduled_games} so {@code /v1/games/today} can surface the full day's card BEFORE first
 * pitch (pitches_live is empty pre-game). Per-pitch detail comes from the GUMBO feed ({@link
 * LiveGameFeed}), not from here.
 *
 * <p>{@code homeAbbr} / {@code awayAbbr} are populated only when the schedule fetch hydrates the
 * team node ({@code &hydrate=team}); they fall back to {@code ""} otherwise (the read path then
 * coalesces to the live abbreviation or the full name). {@code gameTimeUtc} is {@code null} if the
 * feed omitted {@code gameDate}.
 */
public record ScheduledGame(
    long gamePk,
    GameStatus status,
    String homeAbbr,
    String awayAbbr,
    String homeName,
    String awayName,
    Instant gameTimeUtc) {}
