package net.thebullpen.baseball.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.thebullpen.baseball.domain.ReliabilityBinRow;
import net.thebullpen.baseball.domain.ReliabilityWindow;
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
 *
 * <p>Two windows, never confused: {@code windowDays} is the TOP-1 window ({@code days}, default 7);
 * {@code calibrationWindowDays} is the live reliability window ({@code calibrationDays}, default
 * 30) behind each pitch head's {@link Calibration}. {@code status} describes the top-1 window only.
 */
public record RollingAccuracyResponse(
    int windowDays,
    int calibrationWindowDays,
    Instant generatedAt,
    List<ModelRollingAccuracy> models) {

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
      @Schema(nullable = true) String note,
      @Schema(
              nullable = true,
              description =
                  "Live top-label reliability over the calibration window. Independent of status:"
                      + " present for the three pitch heads even when the top-1 window is"
                      + " no_live_truth; null for battedball_outcome and when the analytical store"
                      + " is not configured or the rollup read failed.")
          Calibration calibration) {

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
          modelName, "live", null, (double) hits / (double) n, n, daily, note, null);
    }

    public static ModelRollingAccuracy noTruth(String modelName, String reason) {
      return new ModelRollingAccuracy(
          modelName, "no_live_truth", reason, null, null, null, null, null);
    }

    /** This entry with its calibration attached (the top-1 fields are untouched). */
    public ModelRollingAccuracy withCalibration(Calibration c) {
      return new ModelRollingAccuracy(modelName, status, reason, top1, n, buckets, note, c);
    }
  }

  /** One ET-day sparkline bucket; {@code date} is ISO yyyy-MM-dd. */
  public record DailyBucket(String date, long n, double top1) {}

  /**
   * Live top-label reliability over the calibration window, read from the daily rollup through
   * yesterday ET. Served champion predictions only ([188]); y5 confidence is the persisted winner's
   * probability, y7 the {@code (-value, key)} argmax's. Zero-n bins are omitted, never fabricated.
   *
   * <p>{@code windowKind} is {@code "calendar"} (the trailing {@code windowDays} ET days through
   * yesterday) or {@code "last_days_of_play"} (that window held no graded call - offseason or a
   * long break - so the bins cover the {@code windowDays} most recent ET game days that did).
   * {@code truthFrom}/{@code truthThrough} are the earliest/latest graded game dates actually
   * covered and {@code gameDays} the number of distinct graded game dates; a frontend must show the
   * dates whenever coverage is shorter than the window, and must label a {@code last_days_of_play}
   * chart as such.
   */
  public record Calibration(
      @Schema(allowableValues = {"top_label"}) String definition,
      double binWidth,
      int minReadableN,
      int windowDays,
      @Schema(allowableValues = {"calendar", "last_days_of_play"}) String windowKind,
      @Schema(nullable = true, description = "ISO date; null when n == 0") String truthFrom,
      @Schema(nullable = true, description = "ISO date; null when n == 0") String truthThrough,
      int gameDays,
      long n,
      List<ReliabilityBin> bins) {

    public static final String DEFINITION = "top_label";
    public static final double BIN_WIDTH = 0.1;

    /** Bins below this n are drawn hollow and never flagged by the frontend's note rule. */
    public static final int MIN_READABLE_N = 30;

    public static final String CALENDAR = "calendar";
    public static final String LAST_DAYS_OF_PLAY = "last_days_of_play";

    /**
     * Pure mapper from rollup cells: per-bin SUMS across days (never an average of daily ratios),
     * zero-n bins dropped, bins ordered by lower edge, dates from the cells that carry graded calls
     * only. An empty input is an honest {@code n = 0}, {@code bins = []}, null dates.
     */
    public static Calibration from(ReliabilityWindow window, int windowDays) {
      Map<Integer, long[]> counts = new TreeMap<>();
      Map<Integer, Double> confidence = new TreeMap<>();
      LocalDate from = null;
      LocalDate through = null;
      Set<LocalDate> days = new HashSet<>();
      for (ReliabilityBinRow row : window.rows()) {
        if (row.n() <= 0) {
          continue;
        }
        long[] c = counts.computeIfAbsent(row.bin(), b -> new long[2]);
        c[0] += row.n();
        c[1] += row.hits();
        confidence.merge(row.bin(), row.sumConfidence(), Double::sum);
        days.add(row.gameDate());
        from = from == null || row.gameDate().isBefore(from) ? row.gameDate() : from;
        through = through == null || row.gameDate().isAfter(through) ? row.gameDate() : through;
      }
      List<ReliabilityBin> bins =
          counts.entrySet().stream()
              .map(
                  e -> {
                    int b = e.getKey();
                    long n = e.getValue()[0];
                    long hits = e.getValue()[1];
                    double lower = b / 10.0;
                    double upper = (b + 1) / 10.0;
                    return new ReliabilityBin(
                        lower,
                        upper,
                        n,
                        hits,
                        confidence.get(b) / (double) n,
                        (double) hits / (double) n);
                  })
              .sorted(Comparator.comparingDouble(ReliabilityBin::lower))
              .toList();
      long total = bins.stream().mapToLong(ReliabilityBin::n).sum();
      String kind =
          window.kind() == ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY ? LAST_DAYS_OF_PLAY : CALENDAR;
      return new Calibration(
          DEFINITION,
          BIN_WIDTH,
          MIN_READABLE_N,
          windowDays,
          kind,
          from == null ? null : from.toString(),
          through == null ? null : through.toString(),
          days.size(),
          total,
          bins);
    }
  }

  /**
   * One top-label confidence bin: {@code [lower, upper)} (the last bin closed at 1.0), its graded
   * calls {@code n}, how many came true, the mean predicted confidence, and the observed hit rate.
   */
  public record ReliabilityBin(
      double lower, double upper, long n, long hits, double meanConfidence, double observed) {}
}
