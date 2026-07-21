package net.thebullpen.baseball.domain;

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
 *
 * <p>{@code launchSpeedMph}, {@code launchAngleDeg}, {@code hitDistanceFt}, {@code bbType}, and
 * {@code event} (Phase 1.2) come from the canonical {@code pitches} table (V003) via a LEFT JOIN on
 * the natural pitch key, populated only once the overnight handoff job has moved the row out of
 * {@code pitches_live}. The four batted-ball MEASUREMENTS (launch speed/angle, distance, bb_type)
 * are in-play-only - null on non-in-play pitches. {@code event} is the plate-appearance-terminal
 * Statcast outcome, so it is non-null for ANY backfilled terminal pitch (a strikeout/walk has a
 * non-null {@code event} but null measurements); the game page only reads it for the in-play BIP it
 * selects. All are null on any pitch not yet backfilled into {@code pitches} (the LEFT JOIN miss).
 *
 * <p>{@code pitcherThrows}, {@code batterStand}, {@code baseState}, {@code parkId}, and {@code
 * scoreDiff} (Phase-1 audit item A5; V028) carry the PRE-pitch context the frontend needs to
 * assemble a trustworthy user-triggered next-pitch request (audit item A6). They exist so the A6
 * request MIRRORS the ingest-side {@code LivePitchPredictor.toRequest} conventions exactly: a
 * user-triggered prediction for a given state must match the ingest-side request the worker already
 * logged for the same state bit-for-bit, or the divergence adds avoidable noise to the drift
 * surface {@code prediction_log} feeds (decisions [143] [180], ADR-0014). The frontend must forward
 * these verbatim; it does NOT recompute them.
 *
 * <ul>
 *   <li>{@code pitcherThrows} - pitcher handedness (R/L), from {@code pitches_live.pitch_hand}.
 *       {@code ""} on a pre-migration (pre-V028) row (the LowCardinality default, left as-is).
 *   <li>{@code batterStand} - batter side, from {@code pitches_live.bat_side}. May be {@code "S"}
 *       (switch hitter); resolve S -> L|R downstream against the matchup ({@code resolveBatSide}
 *       precedent, per the {@link net.thebullpen.baseball.domain.LivePitch} javadoc). {@code ""} on
 *       a pre-migration row.
 *   <li>{@code baseState} - base-occupancy bitmask (1=first, 2=second, 4=third), matching {@code
 *       pitches.base_state}. Nullable: {@code null} on a pre-migration row, whose occupancy is
 *       genuinely UNKNOWN (V028 stores {@code Nullable(UInt8)}, NOT {@code DEFAULT 0}, so an old
 *       row does not falsely claim bases-empty). Per the {@link
 *       net.thebullpen.baseball.domain.LivePitch} javadoc's documented v1 approximation, base/outs
 *       are entering-at-bat values and mid-at-bat steals are ignored, so this is constant across an
 *       at-bat's pitches.
 *   <li>{@code parkId} - the park id, projected from {@code pitches_live.home_team} (home_team IS
 *       the park id by project convention; it equals the serving path's {@code ctx.parkId()}). No
 *       DDL: the writer binds {@code home_team = feed.homeAbbrev()}.
 *   <li>{@code scoreDiff} - always {@code 0}. This is NOT a stored column: it is the SERVING-PATH
 *       CONVENTION - {@code LivePitchPredictor.toRequest} sends {@code score_diff = 0} (the
 *       training placeholder is a constant 0). The frontend MUST forward this {@code 0} verbatim so
 *       a user-triggered request matches the ingest-side request for the same state bit-for-bit; do
 *       NOT compute a real home-away differential.
 * </ul>
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
    String predictedWinner,
    Double launchSpeedMph,
    Double launchAngleDeg,
    Double hitDistanceFt,
    String bbType,
    String event,
    String pitcherThrows,
    String batterStand,
    Integer baseState,
    String parkId,
    int scoreDiff) {}
