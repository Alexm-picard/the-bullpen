package net.thebullpen.baseball.api.dto;

/**
 * One pitch type in a pitcher's arsenal (Phase 2.1): how often it is thrown plus the velocity RANGE
 * (min / avg / max mph). Aggregated from {@code pitches} over all seasons, over the pitcher's
 * velocity-known pitches. {@code usagePct} is this type's share of those pitches, in [0, 1].
 */
public record ArsenalPitch(
    String pitchType,
    long count,
    double usagePct,
    double veloMinMph,
    double veloAvgMph,
    double veloMaxMph) {}
