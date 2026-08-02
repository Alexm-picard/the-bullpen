package net.thebullpen.baseball.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /v1/ops/rolling-accuracy} - the /accuracy Live Scorecard payload: rolling realized
 * top-1 accuracy for all four registered families, with the two structurally-untruthable ones
 * SAYING WHY rather than being omitted (the endpoint's honesty contract).
 *
 * <p>{@code top1} is a fraction (0..1), present only when {@code status == "live"}. Every live
 * figure travels with its {@code n} and the window; the frontend owes each % those two numbers - a
 * percentage without a denominator is decoration.
 */
public record RollingAccuracyResponse(
    int windowDays, Instant generatedAt, List<ModelRollingAccuracy> models) {

  /**
   * One model's rolling window. {@code status} is {@code "live"} (top1/n/buckets present) or {@code
   * "no_live_truth"} ({@code reason} present, numbers null) - never a fabricated zero.
   */
  public record ModelRollingAccuracy(
      String modelName,
      String status,
      String reason,
      Double top1,
      Long n,
      List<DailyBucket> buckets) {}

  /** One ET-day sparkline bucket; {@code date} is ISO yyyy-MM-dd. */
  public record DailyBucket(String date, long n, double top1) {}
}
