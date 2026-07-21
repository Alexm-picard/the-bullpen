package net.thebullpen.baseball.domain;

import java.time.Instant;

/**
 * One game from the MLB Stats API schedule endpoint ({@code /api/v1/schedule}): identity, lifecycle
 * status, the two teams, the scheduled first pitch, and the probable starting pitchers. The live
 * poller uses {@code gamePk} + {@code status} for discovery (which games exist today and whether
 * each is pollable - {@link GameStatus#shouldPoll()}); everything else is persisted to {@code
 * scheduled_games} so {@code /v1/games/today} can surface the full day's card BEFORE first pitch
 * and the matchup classification can read the probables. Per-pitch detail comes from the GUMBO feed
 * ({@code LiveGameFeed} in {@code ingest/}; deliberately NOT a {@link} - domain must not import an
 * app module to satisfy a javadoc reference), not from here.
 *
 * <p>{@code homeAbbr} / {@code awayAbbr} are populated only when the schedule fetch hydrates the
 * team node ({@code &hydrate=team}); they fall back to {@code null} otherwise (the read path then
 * coalesces to the live abbreviation or the full name). {@code gameTimeUtc} is {@code null} if the
 * feed omitted {@code gameDate}. The probable-pitcher ids/names ({@code &hydrate=probablePitcher})
 * are {@code 0} / {@code ""} when not yet announced (TBD, or a late scratch that the ~1-2h-before
 * refresh re-confirms).
 */
public record ScheduledGame(
    long gamePk,
    GameStatus status,
    String homeAbbr,
    String awayAbbr,
    String homeName,
    String awayName,
    Instant gameTimeUtc,
    long homeProbableId,
    String homeProbableName,
    long awayProbableId,
    String awayProbableName) {}
