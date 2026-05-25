package net.thebullpen.baseball.api.dto;

/**
 * One bin of a reliability diagram (leaf 4b.3).
 *
 * <p>Each bin is a fraction of the [0, 1] probability axis (10 equal-width bins by default). {@code
 * binStart} / {@code binEnd} pin the bin boundaries; {@code predicted} is the mean predicted
 * probability of the rows that fell into this bin, {@code actual} is the empirical outcome
 * frequency among those rows, {@code n} is the sample count.
 *
 * <p>A well-calibrated model has {@code predicted ≈ actual} for every bin — i.e., points sit on the
 * diagonal of the diagram.
 */
public record CalibrationBin(
    double binStart, double binEnd, double predicted, double actual, long n) {}
