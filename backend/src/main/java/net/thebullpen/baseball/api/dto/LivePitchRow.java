package net.thebullpen.baseball.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One pitch row from {@code pitches_live} (leaf 4d.1). Served by {@code GET
 * /v1/games/{id}/pitches?since=<lastPitchCursor>}.
 *
 * <p>The {@code cursor} field is monotonically increasing across all pitches in a game — derived
 * from {@code (at_bat_index * 100) + pitch_number}. The frontend's {@code useLivePitches} hook
 * passes the largest cursor it has seen back as {@code ?since=} to get only new pitches.
 *
 * <p>{@code predictedClasses} + {@code predictedWinner} (leaf 4d.2) carry the per-pitch prediction
 * if one was logged for this exact pitch. They are nullable: filled in when {@code prediction_log}
 * has a row keyed to {@code (game_id, at_bat_index, pitch_number)}, and null otherwise (the model
 * may have missed the live cutoff, or {@code prediction_log} isn't carrying traffic yet). The
 * frontend renders an "n/a" placeholder per the leaf's known-edge-case.
 */
public record LivePitchRow(
    long gameId,
    int atBatIndex,
    int pitchNumber,
    long cursor,
    Instant ingestedAt,
    long pitcherId,
    long batterId,
    String description,
    String pitchType,
    Double releaseSpeedMph,
    Double plateXIn,
    Double plateZIn,
    int balls,
    int strikes,
    int outs,
    int inning,
    int homeScore,
    int awayScore,
    Map<String, Double> predictedClasses,
    String predictedWinner) {}
