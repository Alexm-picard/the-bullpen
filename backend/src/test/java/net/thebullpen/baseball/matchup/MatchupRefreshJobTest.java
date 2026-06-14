package net.thebullpen.baseball.matchup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.data.GameMatchupsRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.data.PlayerSeasonStatsRepository;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.GameStatus;
import net.thebullpen.baseball.ingest.MlbStatsApiClient;
import net.thebullpen.baseball.ingest.PlayerSeasonStat;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Orchestration test - mocks the MLB client + repos (the external boundary), as the poller test.
 */
class MatchupRefreshJobTest {

  private static final LocalDate DATE = LocalDate.of(2026, 6, 5);

  @Test
  @SuppressWarnings("unchecked")
  void refresh_fetches_pitcher_stats_classifies_and_upserts() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository slate = mock(LivePitchesRepository.class);
    PlayerSeasonStatsRepository stats = mock(PlayerSeasonStatsRepository.class);
    GameMatchupsRepository matchups = mock(GameMatchupsRepository.class);
    MatchupRefreshJob job =
        new MatchupRefreshJob(client, slate, stats, matchups, new MatchupClassifier());

    ScheduledGame g =
        new ScheduledGame(
            101L,
            GameStatus.SCHEDULED,
            "BOS",
            "NYY",
            "Boston",
            "NY",
            Instant.now(),
            1L,
            "Ace A",
            2L,
            "Ace B");
    when(slate.findScheduledGames(DATE)).thenReturn(List.of(g));
    List<PlayerSeasonStat> eras =
        List.of(
            new PlayerSeasonStat(1L, 2026, "pitching", 2.10, null, 700),
            new PlayerSeasonStat(2L, 2026, "pitching", 2.50, null, 680));
    when(client.fetchSeasonStats(any(), anyInt())).thenReturn(eras);
    when(stats.findForPlayers(any(), anyInt())).thenReturn(eras);

    job.refreshFor(DATE);

    verify(stats).upsert(eras); // the fetched stats are persisted
    ArgumentCaptor<List<GameMatchup>> cap = ArgumentCaptor.forClass(List.class);
    verify(matchups).upsert(cap.capture());
    List<GameMatchup> written = cap.getValue();
    assertEquals(1, written.size());
    GameMatchup m = written.get(0);
    assertEquals("pitching", m.lean());
    assertEquals(1L, m.homePlayerId());
    assertEquals(12.0 - 4.60, m.battleScore(), 1e-9); // classified from the fetched ERAs
  }

  @Test
  void refresh_with_no_games_writes_nothing() {
    LivePitchesRepository slate = mock(LivePitchesRepository.class);
    GameMatchupsRepository matchups = mock(GameMatchupsRepository.class);
    when(slate.findScheduledGames(any())).thenReturn(List.of());

    MatchupRefreshJob job =
        new MatchupRefreshJob(
            mock(MlbStatsApiClient.class),
            slate,
            mock(PlayerSeasonStatsRepository.class),
            matchups,
            new MatchupClassifier());

    job.refreshFor(DATE);

    verifyNoInteractions(matchups);
  }
}
