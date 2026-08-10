package net.thebullpen.baseball.data;

import java.time.LocalDate;

/**
 * One pitcher's career-expanding pitch-TYPE COUNTS through {@code asOfDate}, read from {@code
 * pitcher_pitchtype_prior_current} (V030). The base half of the Tier-ARS feature reconstruction for
 * the pre-pitch pitch-TYPE head.
 *
 * <p>COUNTS, NOT RATIOS (V030 header section (b)). The served ARS value for class {@code c} is
 *
 * <pre>{@code
 * ars_c = (base.nC() + delta.nC()) / (base.priorN() + delta.priorN())
 * }</pre>
 *
 * <p>and is NULL / NaN if and only if that denominator is 0 - the same cold-start rule {@code
 * compute_pitch_type_arsenal.sql} applies with {@code if(prior_n = 0, NULL, ...)}. The delta comes
 * from {@link PitcherPitchTypeDelta}. Because both sides are counts the reconstruction is EXACT,
 * not an approximation.
 *
 * <p>{@code priorN} is also a model feature in its own right ({@code pitcher_prior_n}, the
 * cold-start indicator), so it is composed the same way and never dropped.
 *
 * <p>{@code priorNByCount} / {@code nFfByCount} are the same counts conditioned on the current
 * ball-strike count; the repository indexes the stored 12-slot arrays server-side, so these are
 * already the scalars for the requested count.
 *
 * <p>{@code asOfDate} is the {@code max(game_date)} OBSERVED in {@code pitches} when the snapshot
 * ran, never {@code today()} (V030 header section (c)). Callers must pass it back into {@link
 * PitcherPitchTypePriorRepository#findInGameDelta} so the two halves meet exactly, with no gap and
 * no double count.
 */
public record PitcherPitchTypePrior(
    LocalDate asOfDate,
    long priorN,
    long nFf,
    long nSi,
    long nFc,
    long nSl,
    long nCu,
    long nCh,
    long nOff,
    long priorNByCount,
    long nFfByCount) {}
