package net.thebullpen.baseball.api.dto;

/**
 * One in-play batted ball for a batter (Phase 2.2/2.3): the Statcast contact + at-bat outcome.
 * Sourced from {@code pitches} where {@code description = 'in_play'}. The launch fields are
 * nullable (null for a batted ball without tracking data). {@code gameDate} is ISO-8601
 * (YYYY-MM-DD); {@code events} is the at-bat result (e.g. {@code home_run}, {@code single}, {@code
 * field_out}); {@code bbType} is the Statcast hit type (e.g. {@code ground_ball}, {@code
 * fly_ball}).
 */
public record BattedBallRow(
    String gameDate,
    String events,
    String bbType,
    Double launchSpeedMph,
    Double launchAngleDeg,
    Double hitDistanceFt,
    String parkId,
    String stand) {}
