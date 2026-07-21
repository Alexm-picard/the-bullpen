package net.thebullpen.baseball.domain;

/**
 * One live-game prediction reconciled against its real pitch (W3, issue #1 truth-join).
 *
 * <p>A row from {@code prediction_log} LEFT JOINed to {@code pitches_live} on the natural pitch key
 * {@code (game_id, at_bat_index, pitch_number)} added by V017. The prediction-side fields always
 * populate; the truth-side fields ({@link #actualDescription}, {@link #actualPitchType}) are null
 * when the join misses - i.e., the predicted pitch never landed (an orphan: intentional walk,
 * pitch-clock auto ball/strike, mid-PA suspension, per decision [143] / the V017 contract).
 *
 * <p>{@link #matched} is the explicit reconciliation flag: {@code true} when a {@code pitches_live}
 * row exists for the key, {@code false} for an orphan. The calibration set (per-player reliability
 * diagram + the README's "empty" history views) consumes only matched rows - {@link
 * net.thebullpen.baseball.data.PredictionLogRepository#findCalibrationSet} filters orphans out so a
 * predicted-but-never-thrown pitch can't bias the empirical-frequency denominator.
 *
 * @param gameId natural-key game id (never null on a live prediction row)
 * @param atBatIndex natural-key at-bat index
 * @param pitchNumber natural-key pitch number
 * @param modelName the model that produced the prediction
 * @param modelVersion the model version string
 * @param predictionJson the raw {@code {"probabilities": {...}, "winner": "..."}} payload
 * @param matched true when a {@code pitches_live} row exists for the key (not an orphan)
 * @param actualDescription the realized pitch outcome (e.g. {@code called_strike}); null for
 *     orphans
 * @param actualPitchType the realized pitch type (e.g. {@code FF}); null for orphans
 */
public record TruthJoinedPrediction(
    long gameId,
    int atBatIndex,
    int pitchNumber,
    String modelName,
    String modelVersion,
    String predictionJson,
    boolean matched,
    String actualDescription,
    String actualPitchType) {}
