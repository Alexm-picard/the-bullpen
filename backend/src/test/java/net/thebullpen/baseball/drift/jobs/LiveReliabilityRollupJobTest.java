package net.thebullpen.baseball.drift.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.data.LiveReliabilityRepository;
import org.junit.jupiter.api.Test;

/**
 * Unit lane for the rollup job's freshness metric SEMANTICS (the SQL is proven by {@code
 * LiveReliabilityRollupIT}). The alert rules in bullpen-alerts.yml are written against exactly
 * these states: stamp 0 before the first success, advanced ONLY when every head rolled up, frozen
 * by any failure.
 */
class LiveReliabilityRollupJobTest {

  private static double stamp(SimpleMeterRegistry meters) {
    return meters.get(LiveReliabilityRollupJob.LAST_SUCCESS_METRIC).gauge().value();
  }

  private static double runs(SimpleMeterRegistry meters, String outcome) {
    return meters
        .get(LiveReliabilityRollupJob.RUNS_METRIC)
        .tag("outcome", outcome)
        .counter()
        .count();
  }

  @Test
  void aFullSuccessStampsAndCountsSuccess() {
    LiveReliabilityRepository repo = mock(LiveReliabilityRepository.class);
    when(repo.daysSinceLastRollup(anyString())).thenReturn(2L);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LiveReliabilityRollupJob job =
        new LiveReliabilityRollupJob(repo, mock(JobLockRepository.class), meters);
    assertThat(stamp(meters)).as("0 before the first success in this process").isZero();

    long before = Instant.now().getEpochSecond();
    assertThat(job.runOnce()).isTrue();

    assertThat(stamp(meters)).isGreaterThanOrEqualTo((double) before);
    assertThat(runs(meters, "success")).isEqualTo(1.0);
    assertThat(runs(meters, "failure")).isZero();
    verify(repo).rollup("pitch_outcome_pre", 3);
    verify(repo).rollup("pitch_outcome_post", 3);
    verify(repo).rollup("pitch_type_pre", 3);
  }

  @Test
  void oneFailingHeadFreezesTheStampButTheOthersStillRun() {
    LiveReliabilityRepository repo = mock(LiveReliabilityRepository.class);
    when(repo.daysSinceLastRollup(anyString())).thenReturn(2L);
    doThrow(new RuntimeException("clickhouse down"))
        .when(repo)
        .rollup(eq("pitch_outcome_post"), anyInt());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LiveReliabilityRollupJob job =
        new LiveReliabilityRollupJob(repo, mock(JobLockRepository.class), meters);

    assertThat(job.runOnce()).isFalse();

    assertThat(stamp(meters))
        .as("a partial run must not stamp a success - that would blind the staleness alert")
        .isZero();
    assertThat(runs(meters, "failure")).isEqualTo(1.0);
    assertThat(runs(meters, "success")).isZero();
    verify(repo).rollup("pitch_outcome_pre", 3);
    verify(repo).rollup("pitch_type_pre", 3);
  }

  @Test
  void aFailureAfterASuccessLeavesTheEarlierStampUnchanged() {
    LiveReliabilityRepository repo = mock(LiveReliabilityRepository.class);
    when(repo.daysSinceLastRollup(anyString())).thenReturn(2L);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LiveReliabilityRollupJob job =
        new LiveReliabilityRollupJob(repo, mock(JobLockRepository.class), meters);
    job.runOnce();
    double first = stamp(meters);

    when(repo.daysSinceLastRollup(anyString())).thenThrow(new RuntimeException("query died"));
    assertThat(job.runOnce()).isFalse();
    assertThat(stamp(meters)).isEqualTo(first);
  }

  @Test
  void theLockLoserTouchesNothing() {
    LiveReliabilityRepository repo = mock(LiveReliabilityRepository.class);
    JobLockRepository locks = mock(JobLockRepository.class);
    when(locks.tryAcquire(anyString(), any(LocalDate.class))).thenReturn(false);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    LiveReliabilityRollupJob job = new LiveReliabilityRollupJob(repo, locks, meters);

    assertThatCode(job::run).doesNotThrowAnyException();
    verify(repo, never()).rollup(anyString(), anyInt());
    assertThat(stamp(meters)).isZero();
  }

  @Test
  void recomputeWindow_backfillsOnFirstRun_coversGaps_andIsCapped() {
    assertThat(LiveReliabilityRollupJob.recomputeDays(-1))
        .as("first run backfills the full game days pitches_live still holds")
        .isEqualTo(13);
    assertThat(LiveReliabilityRollupJob.recomputeDays(1)).isEqualTo(3);
    assertThat(LiveReliabilityRollupJob.recomputeDays(2)).isEqualTo(3);
    assertThat(LiveReliabilityRollupJob.recomputeDays(6))
        .as("a worker outage widens the window to close the gap")
        .isEqualTo(6);
    assertThat(LiveReliabilityRollupJob.recomputeDays(40))
        .as("never past what pitches_live can still answer")
        .isEqualTo(13);
  }
}
