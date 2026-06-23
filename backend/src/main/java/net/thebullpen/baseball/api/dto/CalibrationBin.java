package net.thebullpen.baseball.api.dto;

/**
 * One bin of a reliability diagram (leaf 4b.3).
 *
 * <p>Each bin is a fraction of the [0, 1] probability axis. {@code binStart} / {@code binEnd} pin
 * the bin boundaries; {@code predicted} is the mean predicted probability of the rows that fell
 * into this bin; {@code n} is the sample count. Both are always real.
 *
 * <p>{@code actual} is the empirical outcome frequency among those rows, or {@code null} when no
 * truth-join has been performed - the current state of {@code /v1/players/:id/calibration}, which
 * bins predicted probabilities from {@code prediction_log} only and does NOT join observed
 * outcomes. A null {@code actual} means "no empirical truth yet", NOT zero: consumers must render
 * it as absent (the reliability diagram falls back to a predicted-only view), never as a fabricated
 * on-diagonal point that would falsely imply the model is perfectly calibrated.
 */
public record CalibrationBin(
    double binStart, double binEnd, double predicted, Double actual, long n) {}
