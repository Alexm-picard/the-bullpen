package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Year;
import java.time.ZoneId;
import java.util.List;
import net.thebullpen.baseball.data.PlayersRefreshRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link PlayersRefreshJob}'s orchestration: backfill-on-empty gating, the
 * latest-season-wins merge, per-season failure tolerance, and the never-crash-the-worker swallow
 * posture. The MLB client is the allowed mock boundary; the repository write path has its own
 * docker-gated IT ({@code PlayersRefreshRepositoryIT}).
 */
class PlayersRefreshJobTest {

  private static final int CURRENT_SEASON = Year.now(ZoneId.of("America/New_York")).getValue();

  private final MlbStatsApiClient client = mock(MlbStatsApiClient.class);
  private final PlayersRefreshRepository repo = mock(PlayersRefreshRepository.class);
  private final PlayersRefreshJob job = new PlayersRefreshJob(client, repo);

  private static MlbPlayer player(long id, String name, boolean active) {
    return new MlbPlayer(id, name, "P", "R", "R", active, "DET");
  }

  @Test
  void backfill_skips_when_the_table_already_has_rows() {
    when(repo.countAll()).thenReturn(4000L);

    assertThat(job.backfillIfEmpty()).isZero();
    verifyNoInteractions(client);
  }

  @Test
  void backfill_merges_all_seasons_with_the_latest_season_winning_per_id() throws IOException {
    when(repo.countAll()).thenReturn(0L);
    when(client.fetchPlayers(anyInt()))
        .thenAnswer(
            inv -> {
              int season = inv.getArgument(0);
              if (season == PlayersRefreshJob.FIRST_SEASON) {
                return List.of(player(1, "Old Name", false));
              }
              if (season == CURRENT_SEASON) {
                return List.of(player(1, "New Name", true), player(2, "Second Player", true));
              }
              return List.of();
            });
    when(repo.upsertAll(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    assertThat(job.backfillIfEmpty()).isEqualTo(2);

    // Every season from the floor through the current one was pulled exactly once.
    verify(client, times(CURRENT_SEASON - PlayersRefreshJob.FIRST_SEASON + 1))
        .fetchPlayers(anyInt());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MlbPlayer>> written = ArgumentCaptor.forClass(List.class);
    verify(repo).upsertAll(written.capture());
    assertThat(written.getValue()).hasSize(2);
    MlbPlayer one = written.getValue().stream().filter(p -> p.id() == 1).findFirst().orElseThrow();
    assertThat(one.name()).isEqualTo("New Name");
    assertThat(one.active()).isTrue();
  }

  @Test
  void backfill_continues_past_a_failed_season() throws IOException {
    when(repo.countAll()).thenReturn(0L);
    when(client.fetchPlayers(anyInt()))
        .thenAnswer(
            inv -> {
              int season = inv.getArgument(0);
              if (season == PlayersRefreshJob.FIRST_SEASON) {
                throw new IOException("MLB API 503");
              }
              if (season == CURRENT_SEASON) {
                return List.of(player(7, "Only Player", true));
              }
              return List.of();
            });
    when(repo.upsertAll(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    assertThat(job.backfillIfEmpty()).isEqualTo(1);
  }

  @Test
  void backfill_writes_nothing_when_every_fetch_fails() throws IOException {
    when(repo.countAll()).thenReturn(0L);
    when(client.fetchPlayers(anyInt())).thenThrow(new IOException("MLB API down"));

    assertThat(job.backfillIfEmpty()).isZero();
    verify(repo, never()).upsertAll(anyList());
  }

  @Test
  void refreshOnce_pulls_only_the_current_season() throws IOException {
    when(client.fetchPlayers(CURRENT_SEASON)).thenReturn(List.of(player(1, "A Player", true)));
    when(repo.upsertAll(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

    assertThat(job.refreshOnce()).isEqualTo(1);
    verify(client, times(1)).fetchPlayers(anyInt());
    verify(client).fetchPlayers(CURRENT_SEASON);
  }

  @Test
  void weeklyRefresh_swallows_a_fetch_failure() throws IOException {
    when(client.fetchPlayers(anyInt())).thenThrow(new IOException("MLB API down"));

    // A missed weekly refresh degrades to last week's roster - it must not crash the worker.
    assertThatCode(job::weeklyRefresh).doesNotThrowAnyException();
  }

  @Test
  void startup_backfill_swallows_a_repository_failure() {
    when(repo.countAll()).thenThrow(new RuntimeException("clickhouse down"));

    // ClickHouse down at worker boot must not crash startup; the next restart retries.
    assertThatCode(job::backfillIfEmptySafely).doesNotThrowAnyException();
  }
}
