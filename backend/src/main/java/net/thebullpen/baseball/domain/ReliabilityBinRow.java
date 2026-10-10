package net.thebullpen.baseball.domain;

import java.time.LocalDate;

/**
 * One (ET game day, confidence bin) cell of the live top-label reliability aggregate: of the
 * deduped, truth-joined, scorable champion predictions on {@code gameDate} whose top-label
 * confidence fell in decile {@code bin} ({@code [bin/10, (bin+1)/10)}, the last bin closed at 1.0),
 * how many there were, how many came true, and the SUM of their confidences.
 *
 * <p>Sums, not means: a window is always a re-sum of cells, never an average of per-day or per-bin
 * ratios. The same shape is produced by the on-demand truth join and read back from the {@code
 * live_reliability_daily} rollup (V035).
 */
public record ReliabilityBinRow(
    LocalDate gameDate, int bin, long n, long hits, double sumConfidence) {}
