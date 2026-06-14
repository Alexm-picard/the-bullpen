package net.thebullpen.baseball.ingest;

/**
 * Season-level quality stats for one player and group, parsed from the MLB Stats API people/stats
 * hydrate. A {@code "pitching"} row carries {@code era} (lower = stronger); a {@code "hitting"} row
 * carries a COMPUTED {@code woba} (higher = stronger) - the API returns the hitting components, not
 * wOBA, so it is computed at ingest. {@code sample} is plate appearances (hitting) or batters faced
 * (pitching), for qualification. {@code era} / {@code woba} are {@code null} on the group that does
 * not carry them, or when the line had no usable denominator.
 */
public record PlayerSeasonStat(
    long playerId, int season, String statGroup, Double era, Double woba, Integer sample) {}
