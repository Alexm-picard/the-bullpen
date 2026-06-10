package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.thebullpen.baseball.data.PitcherFormRepository;
import org.junit.jupiter.api.Test;

class PitcherFormRefreshJobTest {

  @Test
  void runOnce_delegates_to_the_repository() {
    PitcherFormRepository repo = mock(PitcherFormRepository.class);
    when(repo.refreshCurrentForm()).thenReturn(42L);
    assertThat(new PitcherFormRefreshJob(repo).runOnce()).isEqualTo(42L);
  }

  @Test
  void run_swallows_a_refresh_failure() {
    PitcherFormRepository repo = mock(PitcherFormRepository.class);
    when(repo.refreshCurrentForm()).thenThrow(new RuntimeException("clickhouse down"));
    // A failed nightly refresh must not crash the worker - it degrades to yesterday's form / NaN.
    assertThatCode(() -> new PitcherFormRefreshJob(repo).run()).doesNotThrowAnyException();
  }
}
