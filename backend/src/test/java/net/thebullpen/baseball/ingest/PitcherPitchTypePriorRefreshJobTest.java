package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.data.PitcherPitchTypePriorRepository;
import org.junit.jupiter.api.Test;

/**
 * Unit lane for the prior-snapshot job's THREE gauges (age, coverage gap, last-refresh stamp),
 * mirroring {@link PitcherFormRefreshJobTest}. The SQL underneath is proven by {@code
 * PitchTypeArsenalParityIT}; this pins the metric SEMANTICS - what each gauge reads before the
 * first run, after a success, and after a failure - because the alert rules in bullpen-alerts.yml
 * are written against exactly those states.
 */
class PitcherPitchTypePriorRefreshJobTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  @Test
  void runOnce_publishes_age_gap_and_success_stamp() {
    PitcherPitchTypePriorRepository repo = mock(PitcherPitchTypePriorRepository.class);
    LocalDate asOf = LocalDate.now(ET).minusDays(1);
    when(repo.refreshSnapshot(anyString())).thenReturn(asOf);
    // 49 is the REAL prod reading: the manual-backfill hole between the corpus edge and the
    // pitches_live floor that the [186] union SURFACES rather than hides. Correct, not a bug.
    when(repo.coverageGapDays()).thenReturn(49L);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    PitcherPitchTypePriorRefreshJob job =
        new PitcherPitchTypePriorRefreshJob(repo, mock(JobLockRepository.class), meters, null);
    assertThat(meters.get("bullpen_pitchtype_prior_age_days").gauge().value()).isEqualTo(-1.0);
    assertThat(meters.get("bullpen_pitchtype_prior_coverage_gap_days").gauge().value())
        .isEqualTo(-1.0);
    assertThat(meters.get("bullpen_pitchtype_prior_last_refresh_timestamp_seconds").gauge().value())
        .as("0 before the first success in this process - the alert rule gates on process age")
        .isEqualTo(0.0);

    long beforeEpoch = Instant.now().getEpochSecond();
    assertThat(job.runOnce()).isEqualTo(asOf);

    assertThat(meters.get("bullpen_pitchtype_prior_age_days").gauge().value())
        .isEqualTo(ChronoUnit.DAYS.between(asOf, LocalDate.now(ET)));
    assertThat(meters.get("bullpen_pitchtype_prior_coverage_gap_days").gauge().value())
        .as("a standing 49-day gap must be REPORTED at 49, never smoothed")
        .isEqualTo(49.0);
    assertThat(meters.get("bullpen_pitchtype_prior_last_refresh_timestamp_seconds").gauge().value())
        .as("stamped on success - the job-dead half the frozen age gauge cannot see")
        .isGreaterThanOrEqualTo(beforeEpoch);
  }

  @Test
  void run_swallows_a_refresh_failure_and_stamps_nothing() {
    PitcherPitchTypePriorRepository repo = mock(PitcherPitchTypePriorRepository.class);
    when(repo.refreshSnapshot(anyString())).thenThrow(new RuntimeException("clickhouse down"));
    JobLockRepository locks = mock(JobLockRepository.class);
    when(locks.tryAcquire(anyString(), any(LocalDate.class))).thenReturn(true);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PitcherPitchTypePriorRefreshJob job =
        new PitcherPitchTypePriorRefreshJob(repo, locks, meters, null);

    assertThatCode(job::run).doesNotThrowAnyException();
    assertThat(meters.get("bullpen_pitchtype_prior_age_days").gauge().value()).isEqualTo(-1.0);
    assertThat(meters.get("bullpen_pitchtype_prior_last_refresh_timestamp_seconds").gauge().value())
        .as("a failed run must not stamp a success - that would blind the job-dead alert")
        .isEqualTo(0.0);
  }

  @Test
  void a_gauge_query_failure_after_a_successful_refresh_keeps_the_success_stamp() {
    // The refresh SUCCEEDED, so the stamp must say so even when the follow-up coverage-gap
    // query dies - and run() must not relog it as "refresh failed" (the log and the metric
    // have to agree; the sibling form job split exactly this failure domain).
    PitcherPitchTypePriorRepository repo = mock(PitcherPitchTypePriorRepository.class);
    LocalDate asOf = LocalDate.now(ET).minusDays(1);
    when(repo.refreshSnapshot(anyString())).thenReturn(asOf);
    when(repo.coverageGapDays()).thenThrow(new RuntimeException("clickhouse hiccup"));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PitcherPitchTypePriorRefreshJob job =
        new PitcherPitchTypePriorRefreshJob(repo, mock(JobLockRepository.class), meters, null);

    assertThat(job.runOnce()).isEqualTo(asOf);
    assertThat(meters.get("bullpen_pitchtype_prior_last_refresh_timestamp_seconds").gauge().value())
        .as("the refresh succeeded; the stamp must not be held hostage by the gap query")
        .isGreaterThan(0.0);
    assertThat(meters.get("bullpen_pitchtype_prior_coverage_gap_days").gauge().value())
        .as("the failed gap query must not fabricate a reading")
        .isEqualTo(-1.0);
  }

  @Test
  void the_lock_loser_touches_nothing() {
    PitcherPitchTypePriorRepository repo = mock(PitcherPitchTypePriorRepository.class);
    JobLockRepository locks = mock(JobLockRepository.class);
    when(locks.tryAcquire(anyString(), any(LocalDate.class))).thenReturn(false);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PitcherPitchTypePriorRefreshJob job =
        new PitcherPitchTypePriorRefreshJob(repo, locks, meters, null);

    assertThatCode(job::run).doesNotThrowAnyException();
    assertThat(meters.get("bullpen_pitchtype_prior_last_refresh_timestamp_seconds").gauge().value())
        .as("the losing instance did not refresh; it must not claim it did")
        .isEqualTo(0.0);
  }
}
