package net.thebullpen.baseball.ingest;

/**
 * One game from the MLB Stats API schedule endpoint ({@code /api/v1/schedule}): identity plus
 * lifecycle status. The live poller uses this for discovery - which games exist today and whether
 * each is in a pollable (non-terminal) state ({@link GameStatus#shouldPoll()}). Per-pitch detail
 * comes from the GUMBO feed ({@link LiveGameFeed}), not from here.
 */
public record ScheduledGame(long gamePk, GameStatus status, String homeName, String awayName) {}
