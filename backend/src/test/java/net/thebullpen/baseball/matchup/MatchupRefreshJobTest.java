package net.thebullpen.baseball.matchup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.ScheduledGame;
import net.thebullpen.baseball.ingest.Lineup;
import net.thebullpen.baseball.ingest.MlbStatsApiClient;
import net.thebullpen.baseball.ingest.PlayerSeasonStat;
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

  // --- lineup-aware re-classification --------------------------------------------------------

  private record Mocks(
      MlbStatsApiClient client,
      LivePitchesRepository slate,
      PlayerSeasonStatsRepository stats,
      GameMatchupsRepository matchups,
      MatchupRefreshJob job) {}

  private static Mocks mocks() {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository slate = mock(LivePitchesRepository.class);
    PlayerSeasonStatsRepository stats = mock(PlayerSeasonStatsRepository.class);
    GameMatchupsRepository matchups = mock(GameMatchupsRepository.class);
    MatchupRefreshJob job =
        new MatchupRefreshJob(client, slate, stats, matchups, new MatchupClassifier());
    return new Mocks(client, slate, stats, matchups, job);
  }

  private static ScheduledGame gameStarting(Instant start) {
    return new ScheduledGame(
        101L,
        GameStatus.SCHEDULED,
        "BOS",
        "NYY",
        "Boston",
        "NY",
        start,
        1L,
        "Pitcher H",
        2L,
        "Pitcher A");
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshLineups_reclassifies_a_due_game_with_its_lineup() throws Exception {
    Mocks m = mocks();
    Instant now = Instant.parse("2026-06-05T18:00:00Z");
    when(m.slate().findScheduledGames(any()))
        .thenReturn(List.of(gameStarting(now.plusSeconds(90 * 60)))); // 90 min out -> due
    when(m.matchups().findForDate(any())).thenReturn(List.of()); // no prior 'lineup' stage
    when(m.client().fetchLineup(101L))
        .thenReturn(
            new Lineup(
                101L,
                List.of(new Lineup.LineupBatter(10L, "Strong H")),
                List.of(new Lineup.LineupBatter(20L, "Strong A"))));
    // weak pitchers + strong hitters -> a hitters duel.
    List<PlayerSeasonStat> stats =
        List.of(
            new PlayerSeasonStat(1L, 2026, "pitching", 5.5, null, 600),
            new PlayerSeasonStat(2L, 2026, "pitching", 5.4, null, 600),
            new PlayerSeasonStat(10L, 2026, "hitting", null, 0.380, 600),
            new PlayerSeasonStat(20L, 2026, "hitting", null, 0.375, 600));
    when(m.client().fetchSeasonStats(any(), anyInt())).thenReturn(stats);
    when(m.stats().findForPlayers(any(), anyInt())).thenReturn(stats);

    m.job().refreshLineupsFor(LocalDate.of(2026, 6, 5), now);

    verify(m.client()).fetchLineup(101L);
    ArgumentCaptor<List<GameMatchup>> cap = ArgumentCaptor.forClass(List.class);
    verify(m.matchups()).upsert(cap.capture());
    GameMatchup written = cap.getValue().get(0);
    assertEquals("lineup", written.stage());
    assertEquals("hitters", written.lean()); // strong hitters vs weak pitchers
    assertEquals(10L, written.homePlayerId()); // home's best bat
  }

  @Test
  void refreshLineups_skips_a_game_too_far_out() throws Exception {
    Mocks m = mocks();
    Instant now = Instant.parse("2026-06-05T18:00:00Z");
    when(m.slate().findScheduledGames(any()))
        .thenReturn(List.of(gameStarting(now.plusSeconds(5 * 3600)))); // 5h out -> not due
    when(m.matchups().findForDate(any())).thenReturn(List.of());

    m.job().refreshLineupsFor(LocalDate.of(2026, 6, 5), now);

    verify(m.client(), never()).fetchLineup(anyLong());
    verify(m.matchups(), never()).upsert(any());
  }

  @Test
  void refreshLineups_skips_a_game_already_classified_with_a_lineup() throws Exception {
    Mocks m = mocks();
    Instant now = Instant.parse("2026-06-05T18:00:00Z");
    when(m.slate().findScheduledGames(any()))
        .thenReturn(List.of(gameStarting(now.plusSeconds(60 * 60)))); // due, but...
    when(m.matchups().findForDate(any()))
        .thenReturn(
            List.of(
                new GameMatchup(
                    101L,
                    LocalDate.of(2026, 6, 5),
                    "hitters",
                    10L,
                    "H",
                    "hitter",
                    20L,
                    "A",
                    "hitter",
                    6.0,
                    "lineup"))); // already at stage 'lineup'

    m.job().refreshLineupsFor(LocalDate.of(2026, 6, 5), now);

    verify(m.client(), never()).fetchLineup(anyLong());
  }
}
