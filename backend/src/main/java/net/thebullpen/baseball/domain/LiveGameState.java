package net.thebullpen.baseball.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Live state of a single game as served by GET /v1/games/{id}/live (decision [194]). The worker
 * writes the prediction onto the game's status row; this record is what the api reads back and
 * serves. Polled at 2s by the frontend, roughly 1 KB on the wire.
 *
 * <p>{@code predictedAt} is null (and both predictions are null) when no prediction has been
 * written yet - pre-game, between plays, game final, or the worker has not caught up. The consumer
 * falls back to the derive-and-POST path in that case.
 */
public record LiveGameState(
    String status,
    CurrentMatchup matchup,
    UpcomingPitch upcomingPitch,
    long lastPitchCursor,
    Prediction prePrediction,
    Prediction pitchTypePrediction,
    ModelVersions modelVersions,
    Instant predictedAt,
    Instant asOf) {

  public record UpcomingPitch(
      int atBatIndex, int pitchNumber, int balls, int strikes, int outs, int baseState) {}

  public record Prediction(Map<String, Double> probabilities, String winner) {}

  public record ModelVersions(String pre, String pitchType) {}
}
