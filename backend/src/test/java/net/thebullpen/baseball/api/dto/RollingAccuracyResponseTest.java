package net.thebullpen.baseball.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.ModelRollingAccuracy;
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
}
