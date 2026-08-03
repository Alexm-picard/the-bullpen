package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import net.thebullpen.baseball.domain.RollingAccuracyBucket;

/**
 * {@code GET /v1/ops/rolling-accuracy} - the /accuracy Live Scorecard payload: rolling realized
 * top-1 accuracy for all four registered families, with the two structurally-untruthable ones
 * SAYING WHY rather than being omitted (the endpoint's honesty contract).
 *
 * <p>{@code top1} is a fraction (0..1), present only when {@code status == "live"}. Every live
 * figure travels with its {@code n} and the window; the frontend owes each % those two numbers - a
 * percentage without a denominator is decoration.
 *
 * <p>Nullability markers are LOAD-BEARING, not documentation polish: springdoc emits fields as
 * non-nullable by default, and the contract job's response-schema conformance check reds on the
 * first {@code no_live_truth} entry otherwise (the PR #213 class; {@link ModelAccuracyScorecard}
 * carries the same markers for the same reason).
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
      @Schema(nullable = true) String reason,
      @Schema(nullable = true) Double top1,
      @Schema(nullable = true) Long n,
      @Schema(nullable = true) List<DailyBucket> buckets,
      @Schema(nullable = true) String note) {

    /**
     * The live-entry mapping, pure and directly testable: weighted totals across buckets (a
     * windowed weighted sum, NEVER an average of daily percentages - day A at 1/1 plus day B at
     * 0/99 is 1%, not 50%), zero-n buckets dropped from the sparkline, and an empty window flipping
     * to the honest {@code no_live_truth} instead of a fabricated 0%.
     */
    public static ModelRollingAccuracy live(
        String modelName, List<RollingAccuracyBucket> buckets, String emptyReason, String note) {
      long n = buckets.stream().mapToLong(RollingAccuracyBucket::n).sum();
      if (n == 0) {
        return noTruth(modelName, emptyReason);
      }
      long hits = buckets.stream().mapToLong(RollingAccuracyBucket::hits).sum();
      List<DailyBucket> daily =
          buckets.stream()
              .filter(b -> b.n() > 0)
              .map(b -> new DailyBucket(b.date().toString(), b.n(), b.top1()))
              .toList();
      return new ModelRollingAccuracy(
          modelName, "live", null, (double) hits / (double) n, n, daily, note);
    }

    public static ModelRollingAccuracy noTruth(String modelName, String reason) {
      return new ModelRollingAccuracy(modelName, "no_live_truth", reason, null, null, null, null);
    }
  }

  /** One ET-day sparkline bucket; {@code date} is ISO yyyy-MM-dd. */
  public record DailyBucket(String date, long n, double top1) {}
}
