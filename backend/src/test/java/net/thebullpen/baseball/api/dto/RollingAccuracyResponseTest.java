package net.thebullpen.baseball.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.Calibration;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.ModelRollingAccuracy;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.ReliabilityBin;
import net.thebullpen.baseball.domain.ReliabilityBinRow;
import net.thebullpen.baseball.domain.ReliabilityWindow;
import net.thebullpen.baseball.domain.RollingAccuracyBucket;
import org.junit.jupiter.api.Test;

/**
 * The live-entry mapping is a pure static factory precisely so the bug class the work order asked
 * about is testable without a mock (review A4): the weighted total, the n == 0 honesty flip, and
 * the sparkline filter.
 */
class RollingAccuracyResponseTest {

  @Test
  void theTotalIsAWeightedSum_neverAnAverageOfDailyPercentages() {
    // Day A: 1/1 (100%). Day B: 0/99 (0%). Averaging the daily percentages says 50%; the honest
    // window figure is 1/100 = 1%.
    ModelRollingAccuracy live =
        ModelRollingAccuracy.live(
            "pitch_outcome_pre",
            List.of(
                new RollingAccuracyBucket(LocalDate.of(2026, 8, 1), 1, 1),
                new RollingAccuracyBucket(LocalDate.of(2026, 8, 2), 99, 0)),
            "unused",
            null);
    assertThat(live.status()).isEqualTo("live");
    assertThat(live.n()).isEqualTo(100);
    assertThat(live.top1()).isEqualTo(0.01);
    assertThat(live.buckets()).hasSize(2);
  }

  @Test
  void anEmptyWindowFlipsToNoTruth_neverAFabricatedZeroPercent() {
    ModelRollingAccuracy empty =
        ModelRollingAccuracy.live("pitch_outcome_pre", List.of(), "why there is nothing", null);
    assertThat(empty.status()).isEqualTo("no_live_truth");
    assertThat(empty.reason()).isEqualTo("why there is nothing");
    assertThat(empty.top1()).isNull();
    assertThat(empty.n()).isNull();
    assertThat(empty.buckets()).isNull();
    assertThat(empty.note()).isNull();
  }

  @Test
  void zeroNBucketsLeaveTheSparklineButNotTheTotals() {
    ModelRollingAccuracy live =
        ModelRollingAccuracy.live(
            "pitch_outcome_post",
            List.of(
                new RollingAccuracyBucket(LocalDate.of(2026, 8, 1), 10, 5),
                new RollingAccuracyBucket(LocalDate.of(2026, 8, 2), 0, 0)),
            "unused",
            null);
    assertThat(live.buckets()).hasSize(1);
    assertThat(live.n()).isEqualTo(10);
    assertThat(live.top1()).isEqualTo(0.5);
  }

  @Test
  void theNoteRidesTheLiveEntry() {
    ModelRollingAccuracy live =
        ModelRollingAccuracy.live(
            "pitch_type_pre",
            List.of(new RollingAccuracyBucket(LocalDate.of(2026, 8, 2), 600, 300)),
            "unused",
            "calibrated prior ([183])");
    assertThat(live.note()).contains("[183]");
  }

  // --- Calibration.from: the pure reliability mapper -----------------------------------------

  private static ReliabilityWindow calendar(ReliabilityBinRow... rows) {
    return new ReliabilityWindow(ReliabilityWindow.Kind.CALENDAR, List.of(rows));
  }

  @Test
  void calibrationSumsAcrossDays_dropsZeroBins_ordersByLower() {
    LocalDate d1 = LocalDate.of(2026, 9, 1);
    LocalDate d2 = LocalDate.of(2026, 9, 3);
    Calibration c =
        Calibration.from(
            calendar(
                // out of order on purpose: bin 7 before bin 3
                new ReliabilityBinRow(d2, 7, 10, 6, 7.4),
                new ReliabilityBinRow(d1, 3, 100, 30, 34.0),
                new ReliabilityBinRow(d2, 3, 300, 100, 105.0),
                new ReliabilityBinRow(d1, 9, 0, 0, 0.0)),
            30);

    assertThat(c.definition()).isEqualTo("top_label");
    assertThat(c.binWidth()).isEqualTo(0.1);
    assertThat(c.minReadableN()).isEqualTo(30);
    assertThat(c.windowDays()).isEqualTo(30);
    assertThat(c.windowKind()).isEqualTo("calendar");
    assertThat(c.n()).isEqualTo(410);
    assertThat(c.bins()).extracting(ReliabilityBin::lower).containsExactly(0.3, 0.7);
    ReliabilityBin b3 = c.bins().getFirst();
    assertThat(b3.upper()).isEqualTo(0.4);
    assertThat(b3.n()).isEqualTo(400);
    assertThat(b3.hits()).isEqualTo(130);
    // Pooled sums, never an average of the two days' ratios (0.34 and 0.35 -> 0.3475 pooled;
    // observed 0.30 and 0.333 average to 0.3167, pooled is 130/400 = 0.325).
    assertThat(b3.meanConfidence()).isEqualTo(139.0 / 400.0);
    assertThat(b3.observed()).isEqualTo(130.0 / 400.0);
    assertThat(c.truthFrom()).isEqualTo("2026-09-01");
    assertThat(c.truthThrough()).isEqualTo("2026-09-03");
    assertThat(c.gameDays()).isEqualTo(2);
  }

  @Test
  void aZeroOnlyDayDoesNotWidenTheTruthDates() {
    // Zero-grid rows exist for every recomputed day; the dates must come from GRADED cells only,
    // or an off-day's zero grid would claim coverage that never happened.
    Calibration c =
        Calibration.from(
            calendar(
                new ReliabilityBinRow(LocalDate.of(2026, 9, 1), 0, 0, 0, 0.0),
                new ReliabilityBinRow(LocalDate.of(2026, 9, 2), 5, 40, 20, 22.0)),
            30);
    assertThat(c.truthFrom()).isEqualTo("2026-09-02");
    assertThat(c.truthThrough()).isEqualTo("2026-09-02");
    assertThat(c.gameDays()).isEqualTo(1);
  }

  @Test
  void anEmptyWindowIsAnHonestZero_withNullDates() {
    Calibration c = Calibration.from(calendar(), 30);
    assertThat(c.n()).isZero();
    assertThat(c.bins()).isEmpty();
    assertThat(c.truthFrom()).isNull();
    assertThat(c.truthThrough()).isNull();
    assertThat(c.gameDays()).isZero();
    assertThat(c.windowKind()).isEqualTo("calendar");
  }

  @Test
  void theDaysOfPlayFallbackIsLabelled() {
    Calibration c =
        Calibration.from(
            new ReliabilityWindow(
                ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY,
                List.of(new ReliabilityBinRow(LocalDate.of(2026, 10, 30), 6, 50, 31, 32.5))),
            30);
    assertThat(c.windowKind()).isEqualTo("last_days_of_play");
    assertThat(c.truthThrough()).isEqualTo("2026-10-30");
  }

  @Test
  void calibrationIsIndependentOfTheTop1Status() {
    ModelRollingAccuracy entry =
        ModelRollingAccuracy.live("pitch_outcome_pre", List.of(), "nothing this week", null)
            .withCalibration(
                Calibration.from(
                    calendar(new ReliabilityBinRow(LocalDate.of(2026, 9, 2), 5, 40, 20, 22.0)),
                    30));
    assertThat(entry.status()).isEqualTo("no_live_truth");
    assertThat(entry.reason()).isEqualTo("nothing this week");
    assertThat(entry.top1()).isNull();
    assertThat(entry.calibration()).isNotNull();
    assertThat(entry.calibration().n()).isEqualTo(40);
  }

  @Test
  void theFactoriesLeaveCalibrationNull() {
    assertThat(ModelRollingAccuracy.noTruth("battedball_outcome", "why").calibration()).isNull();
    assertThat(
            ModelRollingAccuracy.live(
                    "pitch_outcome_pre",
                    List.of(new RollingAccuracyBucket(LocalDate.of(2026, 8, 1), 1, 1)),
                    "unused",
                    null)
                .calibration())
        .isNull();
  }

  @Test
  void theAdvertisedBinWidthMatchesTheRollupGrid() {
    // The SQL bins with floor(c * 10) capped at 9 and the rollup writes a BINS-wide grid; the
    // payload's binWidth must describe that same grid.
    assertThat(Calibration.BIN_WIDTH * net.thebullpen.baseball.data.LiveReliabilityRepository.BINS)
        .isEqualTo(1.0);
  }
}
