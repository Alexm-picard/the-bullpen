package net.thebullpen.baseball.domain;

import java.time.LocalDate;

/**
 * One ET-day bucket of the rolling realized-accuracy truth join: how many deduped, truth-joined,
 * scorable predictions landed on {@code date}, and how many of them had the model's top-1 class
 * match the realized outcome.
 *
 * <p>{@code n} counts only SCORABLE rows: deduped to one prediction per pitch key, INNER-joined to
 * a realized {@code pitches_live} row, prediction JSON parseable, truth inside the model's
 * vocabulary. Unrealized (orphan) predictions and out-of-vocabulary truths are excluded from both
 * numerator and denominator - a % over unscorable rows would be a number wearing a costume.
 */
public record RollingAccuracyBucket(LocalDate date, long n, long hits) {

  /** Top-1 realized accuracy as a fraction (0..1); callers must not divide by a zero-n bucket. */
  public double top1() {
    return n == 0 ? 0.0 : (double) hits / (double) n;
  }
}
