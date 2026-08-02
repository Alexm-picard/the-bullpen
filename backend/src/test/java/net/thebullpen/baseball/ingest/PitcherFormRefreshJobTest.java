package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import net.thebullpen.baseball.data.PitcherFormRepository;
import org.junit.jupiter.api.Test;

class PitcherFormRefreshJobTest {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  @Test
  void runOnce_delegates_and_publishes_the_corpus_age() {
    PitcherFormRepository repo = mock(PitcherFormRepository.class);
    when(repo.refreshCurrentForm()).thenReturn(42L);
    // A corpus 63 days behind is the real number this gauge was born from (2026-07-27): the
    // nightly job had been selecting nothing for two months while its clock-anchored stamp said
    // fresh. The gauge is the half that would have caught it in June - an honest as_of_date alone
    // is a truthful column nobody reads.
    LocalDate corpusEdge = LocalDate.now(ET).minusDays(63);
    when(repo.windowSourceMaxGameDate()).thenReturn(corpusEdge);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();

    PitcherFormRefreshJob job = new PitcherFormRefreshJob(repo, meters);
    assertThat(meters.get("bullpen_pitcher_form_age_days").gauge().value())
        .as(
            "-1 before the first run; NOTE a bare > N alert reads -1 as fresh, so the rule must"
                + " include < 0")
        .isEqualTo(-1.0);
    assertThat(meters.get("bullpen_pitcher_form_last_refresh_timestamp_seconds").gauge().value())
        .as("0 before the first success in this process - the alert rule gates on process age")
        .isEqualTo(0.0);

    long beforeEpoch = java.time.Instant.now().getEpochSecond();
    assertThat(job.runOnce()).isEqualTo(42L);
    assertThat(meters.get("bullpen_pitcher_form_age_days").gauge().value())
        .as("days between today and max(game_date) in pitches")
        .isEqualTo(ChronoUnit.DAYS.between(corpusEdge, LocalDate.now(ET)));
    assertThat(meters.get("bullpen_pitcher_form_last_refresh_timestamp_seconds").gauge().value())
        .as("stamped on success - the job-dead half the frozen age gauge cannot see")
        .isGreaterThanOrEqualTo(beforeEpoch);
  }

  @Test
  void run_swallows_a_refresh_failure_and_keeps_the_last_age() {
    PitcherFormRepository repo = mock(PitcherFormRepository.class);
    when(repo.refreshCurrentForm()).thenThrow(new RuntimeException("clickhouse down"));
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    PitcherFormRefreshJob job = new PitcherFormRefreshJob(repo, meters);
    // A failed nightly refresh must not crash the worker - it degrades to yesterday's form / NaN.
    assertThatCode(job::run).doesNotThrowAnyException();
    assertThat(meters.get("bullpen_pitcher_form_age_days").gauge().value())
        .as("a failed run must not fabricate freshness")
        .isEqualTo(-1.0);
    assertThat(meters.get("bullpen_pitcher_form_last_refresh_timestamp_seconds").gauge().value())
        .as("a failed run must not stamp a success - that would blind the job-dead alert")
        .isEqualTo(0.0);
  }
}
