package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.config.IngestProperties;
import net.thebullpen.baseball.data.JobLeaseRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.BattedBall;
import net.thebullpen.baseball.domain.CurrentMatchup;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.LivePitch;
import net.thebullpen.baseball.domain.ScheduledGame;
import net.thebullpen.baseball.inference.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Orchestration coverage for the live poll loop (issue #1 step 6): fetch -> write-new-pitches ->
 * predict-next, plus the two idempotency guards (cursor high-water + predict-next dedup) that keep
 * a re-poll of the same feed from double-writing or double-predicting. Collaborators are mocked at
 * their boundaries (HTTP client, ClickHouse repo, ONNX predictor).
 */
class LivePollingServiceTest {

  private static LivePitch pitch(int atBat, int pitchNumber) {
    return new LivePitch(
        822810L,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        0,
        0,
        "ball",
        "SI",
        95.0,
        0.0,
        0.0,
        -0.5,
        1.2,
        2200.0,
        200.0,
        -1.6,
        5.9,
        false,
        null); // battedBall: these fixtures are not balls in play
  }

  // --- V031: the live current matchup rides the status row -------------------------------

  @Test
  void pollGame_writes_the_live_matchup_alongside_the_status() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L)).thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)));

    service(client, repo, predictor).pollGame(822810L);

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo).upsertGameStatus(eq(822810L), any(), eq("IN_PROGRESS"), captor.capture());
    CurrentMatchup m = captor.getValue();
    assertThat(m).isNotNull();
    assertThat(m.batterId()).isEqualTo(676391L);
    assertThat(m.pitcherId()).isEqualTo(689296L);
    assertThat(m.batSide()).isEqualTo("R");
    assertThat(m.pitchHand()).isEqualTo("R");
    assertThat(m.atBatIndex()).isEqualTo(1);
  }

  @Test
  void pollGame_rewrites_the_status_row_when_the_at_bat_rolls_over_though_status_is_unchanged()
      throws Exception {
    // THE cadence fix. The status row was written only on a STATUS transition (or the process's
    // first poll), so hanging the matchup on it would freeze the batter at whatever was standing in
    // when the game went IN_PROGRESS - stale for nine innings, which is the exact defect this
    // feature removes. A moved matchup must itself trigger the write.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitchWithBatter(2, 1, 700000L)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L); // at-bat 1
    svc.pollGame(822810L); // at-bat 2, SAME status

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo, times(2))
        .upsertGameStatus(eq(822810L), any(), eq("IN_PROGRESS"), captor.capture());
    assertThat(captor.getAllValues().get(0).batterId()).isEqualTo(676391L);
    assertThat(captor.getAllValues().get(1).batterId())
        .as("the new batter must reach the row without waiting for a status transition")
        .isEqualTo(700000L);
  }

  @Test
  void pollGame_rewrites_when_a_pinch_hitter_keeps_the_at_bat_index() throws Exception {
    // The change key is (atBatIndex, batterId) precisely because a pinch hitter mid-PA keeps the
    // index and changes the batter - an index-only key would miss it and serve the replaced batter.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitchWithBatter(1, 2, 676391L)))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitchWithBatter(1, 2, 999111L)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo, times(2)).upsertGameStatus(eq(822810L), any(), any(), captor.capture());
    assertThat(captor.getAllValues().get(1).batterId()).isEqualTo(999111L);
    assertThat(captor.getAllValues().get(1).atBatIndex())
        .as("same at-bat, different batter")
        .isEqualTo(1);
  }

  @Test
  void pollGame_writes_a_null_matchup_when_the_play_completes_rather_than_leaving_a_stale_batter()
      throws Exception {
    // parseNextPitch returns null between plays and once final. That null must be WRITTEN, not
    // skipped: a skipped write leaves the row advertising a batter who has finished hitting.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)))
        .thenReturn(feed(List.of(pitch(1, 1)), null)); // play complete / game over

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo, times(2)).upsertGameStatus(eq(822810L), any(), any(), captor.capture());
    assertThat(captor.getAllValues().get(1))
        .as("the row must stop naming the finished batter")
        .isNull();
  }

  @Test
  void pollGame_treats_a_zero_id_early_gumbo_matchup_as_absent() throws Exception {
    // MlbFeedParser's asLong() yields 0L (never null) for a missing matchup id, so an early-GUMBO
    // tick produces a matchup naming batter 0 - a fabricated value if written as-is.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitchWithBatter(0, 1, 0L)));

    service(client, repo, predictor).pollGame(822810L);

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo).upsertGameStatus(eq(822810L), any(), any(), captor.capture());
    assertThat(captor.getValue()).isNull();
  }

  private static LiveNextPitch nextPitch(int atBat, int pitchNumber) {
    return new LiveNextPitch(
        822810L,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        "TOR",
        LocalDate.of(2026, 6, 5));
  }

  private static LiveGameFeed feed(List<LivePitch> pitches, LiveNextPitch next) {
    return new LiveGameFeed(
        822810L,
        GameStatus.IN_PROGRESS,
        LocalDate.of(2026, 6, 5),
        1,
        2,
        "TOR",
        "BAL",
        pitches,
        next);
  }

  /** Poller cadence props with a zero API gap so the unit tests don't throttle between polls. */
  private static IngestProperties pollerProps() {
    return new IngestProperties(
        new IngestProperties.Live("https://statsapi.mlb.com", "ua", 5000, 3, 0L, 15L, 30L),
        new IngestProperties.Players(false));
  }

  private static LivePollingService service(
      MlbStatsApiClient client, LivePitchesRepository repo, LivePitchPredictor predictor) {
    return service(client, repo, predictor, new SimpleMeterRegistry());
  }

  /** Overload exposing the registry, so a test can assert on the counters the poller writes. */
  private static LivePollingService service(
      MlbStatsApiClient client,
      LivePitchesRepository repo,
      LivePitchPredictor predictor,
      MeterRegistry registry) {
    return new LivePollingService(
        client,
        repo,
        Optional.of(predictor),
        Optional.empty(),
        new IngestMetrics(registry),
        heldLease(),
        pollerProps());
  }

  /**
   * A {@link JobLeaseRepository} mock that always grants the D-37 live-polling lease, so {@code
   * tick()} proceeds into the loop under test instead of returning dormant. The lease's own
   * acquire/renew/failover semantics are covered by {@code JobLeaseRepositoryIT}.
   */
  private static JobLeaseRepository heldLease() {
    JobLeaseRepository lease = mock(JobLeaseRepository.class);
    when(lease.tryAcquireOrRenew(any(), any(), anyLong())).thenReturn(true);
    return lease;
  }

  @Test
  void pollGame_writes_new_pitches_and_predicts_the_next_pitch() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1), pitch(1, 2)), nextPitch(1, 3)));

    service(client, repo, predictor).pollGame(822810L);

    verify(repo, times(1)).insertPitches(any());
    verify(predictor, times(1)).predictAndLog(any());
  }

  @Test
  void pollGame_skips_the_status_write_when_the_feed_carries_no_gameDate() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    // A feed with no parseable gameData.datetime (C-3 replay finding): the status transition
    // cannot key into live_game_status, so the write is skipped (and debug-logged) instead of
    // attempted with a null date.
    when(client.fetchLiveFeed(822810L))
        .thenReturn(
            new LiveGameFeed(
                822810L, GameStatus.IN_PROGRESS, null, 1, 2, "TOR", "BAL", List.of(), null));

    assertThatCode(() -> service(client, repo, predictor).pollGame(822810L))
        .doesNotThrowAnyException();

    verify(repo, never()).upsertGameStatus(anyLong(), any(), any(), any());
  }

  @Test
  void pollGame_does_not_rewrite_or_repredict_an_unchanged_feed() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1), pitch(1, 2)), nextPitch(1, 3)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L); // a re-poll while the at-bat sits at the same count

    // cursor high-water + predict-next dedup: still just one insert + one prediction.
    verify(repo, times(1)).insertPitches(any());
    verify(predictor, times(1)).predictAndLog(any());
    // status upserts only on a transition (null -> IN_PROGRESS on poll 1; unchanged on poll 2).
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any(), any());
  }

  @Test
  void pollGame_predicts_the_new_upcoming_pitch_after_the_count_advances() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1), pitch(1, 2)), nextPitch(1, 3))) // next = pitch 3
        .thenReturn(
            feed(List.of(pitch(1, 1), pitch(1, 2), pitch(1, 3)), nextPitch(1, 4))); // 3 landed

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    verify(repo, times(2)).insertPitches(any()); // pitch 3 is new on the 2nd poll
    verify(predictor, times(2)).predictAndLog(any()); // next-pitch key advanced 103 -> 104
  }

  @Test
  void pollGame_writes_pitches_but_skips_prediction_when_no_model_is_loaded() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    when(client.fetchLiveFeed(822810L)).thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)));

    // No predictor bean (no model artifact) -> Optional.empty(). No form repo either.
    new LivePollingService(
            client,
            repo,
            Optional.empty(),
            Optional.empty(),
            new IngestMetrics(new SimpleMeterRegistry()),
            heldLease(),
            pollerProps())
        .pollGame(822810L);

    verify(repo, times(1)).insertPitches(any());
  }

  @Test
  void pollGame_updates_last_poll_gauge_and_pitch_counter() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1), pitch(1, 2)), nextPitch(1, 3)));

    new LivePollingService(
            client,
            repo,
            Optional.empty(),
            Optional.empty(),
            new IngestMetrics(registry),
            heldLease(),
            pollerProps())
        .pollGame(822810L);

    assertThat(registry.get("bullpen_ingest_pitches_total").counter().count()).isEqualTo(2.0);
    assertThat(registry.get("bullpen_ingest_last_poll_timestamp_seconds").gauge().value())
        .isGreaterThan(0.0);
  }

  @Test
  void pollGame_counts_an_unknown_game_status_as_a_parse_anomaly() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    // A detailedState the parser has never seen collapses to UNKNOWN - the schema-drift signal.
    LiveGameFeed unknownStatusFeed =
        new LiveGameFeed(
            822810L,
            GameStatus.UNKNOWN,
            LocalDate.of(2026, 6, 5),
            1,
            2,
            "TOR",
            "BAL",
            List.of(),
            null);
    when(client.fetchLiveFeed(822810L)).thenReturn(unknownStatusFeed);

    new LivePollingService(
            client,
            repo,
            Optional.empty(),
            Optional.empty(),
            new IngestMetrics(registry),
            heldLease(),
            pollerProps())
        .pollGame(822810L);

    assertThat(
            registry
                .get("bullpen_ingest_parse_anomalies_total")
                .tag("reason", "unknown_game_status")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  // --- WS1 robustness (C1 / C2 / C5) ------------------------------------

  @Test
  void restart_mid_game_persists_the_status_row_on_first_poll_without_a_transition()
      throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    // Restart-mid-game shape (L1): the schedule already reports the game IN_PROGRESS, so the
    // prime sets prev == current and the old transition-only persistence never wrote the row -
    // the game stayed invisible to /v1/games/today until its NEXT transition.
    when(client.fetchSchedule(any()))
        .thenReturn(
            List.of(
                new ScheduledGame(
                    822810L,
                    GameStatus.IN_PROGRESS,
                    "BOS",
                    "BAL",
                    "BOS",
                    "BAL",
                    null,
                    0L,
                    "",
                    0L,
                    "")));
    when(client.fetchLiveFeed(822810L)).thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)));

    LivePollingService svc = service(client, repo, predictor);
    svc.tick();
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any(), any());

    // A later poll of the same game (no transition, already persisted) does not re-write.
    svc.pollGame(822810L);
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any(), any());
  }

  @Test
  void tick_isolates_a_failing_game_so_other_games_still_poll() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);

    long gameA = 822810L;
    long gameB = 822811L;
    when(client.fetchSchedule(any()))
        .thenReturn(
            List.of(
                new ScheduledGame(
                    gameA,
                    GameStatus.IN_PROGRESS,
                    "TOR",
                    "BAL",
                    "TOR",
                    "BAL",
                    null,
                    0L,
                    "",
                    0L,
                    ""),
                new ScheduledGame(
                    gameB,
                    GameStatus.IN_PROGRESS,
                    "NYY",
                    "BOS",
                    "NYY",
                    "BOS",
                    null,
                    0L,
                    "",
                    0L,
                    "")));
    when(client.fetchLiveFeed(gameA))
        .thenReturn(feedFor(gameA, List.of(pitchFor(gameA, 1, 1)), nextPitchFor(gameA, 1, 2)));
    when(client.fetchLiveFeed(gameB))
        .thenReturn(feedFor(gameB, List.of(pitchFor(gameB, 1, 1)), nextPitchFor(gameB, 1, 2)));
    // Game A's model is unavailable (a stale routing row whose snapshot won't load); game B's
    // serves.
    when(predictor.predictAndLog(argThat(np -> np != null && np.gameId() == gameA)))
        .thenThrow(new ModelUnavailableException("stale routing row for game A"));
    when(predictor.predictAndLog(argThat(np -> np != null && np.gameId() == gameB)))
        .thenReturn(Map.of("ball", 1.0));

    // The whole tick must not abort on game A's failure.
    assertThatCode(() -> service(client, repo, predictor).tick()).doesNotThrowAnyException();

    // Game B is fully serviced even though game A blew up: B's pitches were written and B's next
    // pitch was predicted. A's pitches were still ingested (write precedes predict).
    verify(predictor, times(1)).predictAndLog(argThat(np -> np.gameId() == gameB));
    verify(repo, times(1)).insertPitches(argThat(f -> f.gamePk() == gameB));
    verify(repo, times(1)).insertPitches(argThat(f -> f.gamePk() == gameA));
  }

  @Test
  void pollGame_ingests_and_degrades_when_the_model_is_unavailable() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any()))
        .thenThrow(new ModelUnavailableException("snapshot will not load"));
    when(client.fetchLiveFeed(822810L)).thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)));

    // A load/inference failure degrades the prediction; it does NOT escape pollGame, and ingest of
    // the landed pitch still happens (C2).
    assertThatCode(() -> service(client, repo, predictor).pollGame(822810L))
        .doesNotThrowAnyException();
    verify(repo, times(1)).insertPitches(any());
    verify(predictor, times(1)).predictAndLog(any());
  }

  @Test
  void pollGame_does_not_reattempt_a_failed_prediction_for_the_same_pitch() throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any()))
        .thenThrow(new ModelUnavailableException("snapshot will not load"));
    when(client.fetchLiveFeed(822810L)).thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L); // same feed, same upcoming-pitch key, still failing

    // Failure-dedup (C1): the same doomed pitch is attempted once, not re-hit every poll/tick.
    verify(predictor, times(1)).predictAndLog(any());
  }

  @Test
  void pollGame_skips_prediction_but_still_ingests_when_the_matchup_is_not_populated()
      throws Exception {
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    // Early GUMBO payload: pitchHand / batSide not yet populated.
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nullMatchupNextPitch(1, 2)));

    service(client, repo, predictor).pollGame(822810L);

    // C5: prediction skipped (no nulls fed to the model), ingest proceeds.
    verify(repo, times(1)).insertPitches(any());
    verify(predictor, never()).predictAndLog(any());
  }

  // --- parameterized helpers for multi-game / null-matchup cases --------

  private static LivePitch pitchFor(long gameId, int atBat, int pitchNumber) {
    return new LivePitch(
        gameId,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        0,
        0,
        "ball",
        "SI",
        95.0,
        0.0,
        0.0,
        -0.5,
        1.2,
        2200.0,
        200.0,
        -1.6,
        5.9,
        false,
        null); // battedBall: these fixtures are not balls in play
  }

  private static LiveNextPitch nextPitchFor(long gameId, int atBat, int pitchNumber) {
    return new LiveNextPitch(
        gameId,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        "TOR",
        LocalDate.of(2026, 6, 5));
  }

  private static LiveGameFeed feedFor(long gameId, List<LivePitch> pitches, LiveNextPitch next) {
    return new LiveGameFeed(
        gameId,
        GameStatus.IN_PROGRESS,
        LocalDate.of(2026, 6, 5),
        1,
        2,
        "TOR",
        "BAL",
        pitches,
        next);
  }

  @Test
  void aMatchupWithRealIdsButNoAtBatIndexIsAbsent() throws Exception {
    // isPopulated() is TOTAL over the at-bat index for a load-bearing reason: the write path's
    // `usable ? matchup.atBatIndex() : 0` mixes Integer with int, so binary numeric promotion
    // unboxes - a partial guard would pass this record through and NPE on the write. Not
    // reachable from the poller today (LiveNextPitch.atBatIndex is a primitive), but the record
    // is public in domain/ and its contract permits it.
    // The NPE half of this belongs on the REAL write path, not here: an earlier version asserted
    // doesNotThrowAnyException against a mock repository, whose unstubbed void method is a no-op,
    // so it passed identically with the guard deleted - it advertised coverage it did not have.
    // LivePitchesRepositoryIT.aNullAtBatIndexMatchupIsStoredAsAbsent_notAnNpe carries that.
    CurrentMatchup noIndex = new CurrentMatchup(676391L, 689296L, "R", "R", null);
    assertThat(noIndex.isPopulated()).isFalse();
  }

  @Test
  void aMissingGameDateMustNotPoisonTheMatchupKey() throws Exception {
    // T-1: lastMatchupKey.put lives INSIDE the gameDate guard on purpose. Hoisting it out
    // compiles and passes every other test here, but permanently drops a matchup whenever one
    // tick lacks gameDate - the poller would believe it had already written that matchup.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    LiveNextPitch np = nextPitchWithBatter(3, 1, 555000L);
    when(client.fetchLiveFeed(822810L))
        .thenReturn(
            new LiveGameFeed(
                822810L, GameStatus.IN_PROGRESS, null, 1, 2, "TOR", "BAL", List.of(), np))
        .thenReturn(feed(List.of(pitch(1, 1)), np)); // same matchup, gameDate now present

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L); // dropped: no gameDate
    svc.pollGame(822810L); // must NOT be considered already-written

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo, times(1)).upsertGameStatus(eq(822810L), any(), any(), captor.capture());
    assertThat(captor.getValue().batterId()).isEqualTo(555000L);
  }

  @Test
  void aFailedStatusWriteMustNotSuppressTheRetry() throws Exception {
    // T-1's sibling: lastMatchupKey.put sits AFTER the upsert on purpose. Hoisting it above -
    // which reads as harmless bookkeeping - records the matchup as written even though the write
    // threw, and the poller then skips it forever because the key already matches.
    //
    // Isolating that requires THREE ticks with a prior SUCCESSFUL write. A first-write failure
    // cannot show it: a throwing upsert also skips statusPersisted.add, so the next tick rewrites
    // for that reason whatever the key ordering is, and the assertion passes on a mutant. Only
    // once the game is already in statusPersisted, with the status steady, is lastMatchupKey the
    // sole thing that can suppress the retry.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    doNothing()
        .doThrow(new RuntimeException("clickhouse down"))
        .doNothing()
        .when(repo)
        .upsertGameStatus(anyLong(), any(), any(), any());
    LiveNextPitch first = nextPitchWithBatter(1, 1, 111000L);
    LiveNextPitch second = nextPitchWithBatter(2, 1, 222000L);
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), first)) // writes, and is remembered
        .thenReturn(feed(List.of(pitch(1, 1)), second)) // new matchup; the write throws
        .thenReturn(feed(List.of(pitch(1, 1)), second)); // same matchup - must still be retried

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    // pollGame propagates; in production tick()'s per-game catch absorbs it and the loop moves to
    // the next game. Asserting the throw here keeps that boundary visible.
    assertThatThrownBy(() -> svc.pollGame(822810L)).hasMessage("clickhouse down");
    svc.pollGame(822810L);

    // Three attempts. With the put hoisted above the upsert this is 2: tick 3 sees a key that
    // matches a matchup that was never actually stored, and the batter freezes for the game.
    verify(repo, times(3)).upsertGameStatus(eq(822810L), any(), any(), any());
  }

  /**
   * A pitch fixture that IS a completed ball in play - mirroring {@link #pitch} field-for-field
   * rather than copying accessors off it, so this cannot silently diverge from the shape that
   * helper defines.
   */
  private static LivePitch bipPitch(int atBat, int pitchNumber, BattedBall bb) {
    return new LivePitch(
        822810L,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        0,
        0,
        "in_play",
        "SI",
        95.0,
        0.0,
        0.0,
        -0.5,
        1.2,
        2200.0,
        200.0,
        -1.6,
        5.9,
        true,
        bb);
  }

  private static final BattedBall HOMER =
      new BattedBall(102.4, 24.0, 403.0, 196.18, 65.51, "fly_ball", "Home Run");

  @Test
  void aBallInPlaySeenBeforeItsPlayCompletedIsBackfilledOnceItDoes() throws Exception {
    // THE case the cursor gate cannot serve, and the one that decides whether any of this reaches
    // a live game. writeNewPitches filters cursor(p) > since, so a pitch is written EXACTLY ONCE.
    // A ball in play first seen while its play was in flight carries no physics; without the
    // backfill it keeps none for the rest of the game, and the card stays empty for exactly the
    // games it exists to serve. Note the failure mode: fewer rows, which reads as a quiet slate.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2))) // in flight: no physics yet
        .thenReturn(feed(List.of(bipPitch(1, 1, HOMER)), nextPitch(2, 1))); // play completed

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LivePollingService svc = service(client, repo, predictor, registry);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    ArgumentCaptor<LiveGameFeed> captor = ArgumentCaptor.forClass(LiveGameFeed.class);
    verify(repo, atLeastOnce()).insertPitches(captor.capture());
    assertThat(captor.getAllValues().stream().flatMap(f -> f.pitches().stream()))
        .as("the completed batted ball must reach storage on the later poll")
        .anyMatch(pp -> pp.battedBall() != null && "Home Run".equals(pp.battedBall().event()));

    // The backfill must NOT increment the pitches counter: that one is documented as "pitches
    // written to pitches_live", and re-counting a re-write turns it into "row writes" - breaking
    // the counter you would reach for to ask whether ingest had stalled. Without this assertion,
    // putting incrementPitchesIngested back reds nothing.
    assertThat(registry.counter(IngestMetrics.PITCHES_METRIC).count())
        .as("one pitch was ingested, and the backfill re-write is not a second one")
        .isEqualTo(1.0);
    assertThat(registry.counter(IngestMetrics.BIP_BACKFILLS_METRIC).count())
        .as("the backfill fired exactly once")
        .isEqualTo(1.0);
  }

  @Test
  void aBattedBallCompleteOnFirstSightingIsNotWrittenTwice() throws Exception {
    // The backfill must not re-write what writeNewPitches already stored complete. One extra
    // insert per ball in play is the budget; a rolling re-write window would be ~80x amplification
    // into a 14-day TTL table.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(bipPitch(1, 1, HOMER)), nextPitch(2, 1)))
        .thenReturn(feed(List.of(bipPitch(1, 1, HOMER)), nextPitch(2, 1)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    verify(repo, times(1)).insertPitches(any());
  }

  @Test
  void aBallWhosePhysicsArriveOutOfOrderIsStillBackfilled() throws Exception {
    // The defect two reviewers found independently. The first version tracked a single high-water
    // mark, so once a LATER ball in play was written with physics, an EARLIER one that had not yet
    // received its hitData could never be backfilled - empty physics forever. Reachable on a failed
    // feed fetch, an insert throw, a Statcast late correction, and above all a worker restart.
    //
    // Sequence: at-bat 2 completes with no hitData yet, at-bat 4 completes WITH it, then at-bat 2's
    // hitData finally arrives.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    LivePitch early = pitch(2, 1); // in play, physics not yet attached
    LivePitch earlyWithPhysics = bipPitch(2, 1, HOMER);
    LivePitch laterWithPhysics = bipPitch(4, 1, HOMER);
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(early), nextPitch(3, 1)))
        .thenReturn(feed(List.of(early, laterWithPhysics), nextPitch(5, 1)))
        .thenReturn(feed(List.of(earlyWithPhysics, laterWithPhysics), nextPitch(5, 1)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    ArgumentCaptor<LiveGameFeed> captor = ArgumentCaptor.forClass(LiveGameFeed.class);
    verify(repo, atLeastOnce()).insertPitches(captor.capture());
    assertThat(
            captor.getAllValues().stream()
                .flatMap(f -> f.pitches().stream())
                .filter(pp -> pp.battedBall() != null)
                .anyMatch(pp -> pp.atBatIndex() == 2))
        .as("at-bat 2's late physics must still reach storage after at-bat 4 was written")
        .isTrue();
  }

  @Test
  void aStableBattedBallIsNotRewrittenOnEveryPoll() throws Exception {
    // Named for what it actually pins. It was called ...DoesNotWalkTheWatermarkBackwards, which
    // was false advertising: it passes with the max replaced by a plain put, because no reachable
    // input walks the watermark backwards. What it DOES pin is the write budget - the feed carries
    // every completed play on every poll, so without the watermark this BIP would be re-inserted
    // once per 5s tick for the rest of the game.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(bipPitch(3, 1, HOMER)), nextPitch(4, 1)))
        .thenReturn(feed(List.of(bipPitch(3, 1, HOMER)), nextPitch(4, 1)))
        .thenReturn(feed(List.of(bipPitch(3, 1, HOMER)), nextPitch(4, 1)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    verify(repo, times(1)).insertPitches(any());
  }

  @Test
  void aRepeatedNullMatchupDoesNotRewriteEveryTick() throws Exception {
    // T-2: the write-amplification guard for the NULL side. isComplete latches for the whole
    // between-plays gap, so "" must be as stable a key as a populated one.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)))
        .thenReturn(feed(List.of(pitch(1, 1)), null))
        .thenReturn(feed(List.of(pitch(1, 1)), null));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    verify(repo, times(2)).upsertGameStatus(anyLong(), any(), any(), any());
  }

  @Test
  void aMatchupMoveOnTheSameTickAsAStatusTransitionWritesExactlyOnce() throws Exception {
    // T-3: the condition is an OR of independent reasons - it must not double-write when both
    // fire together.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitch(1, 2)))
        .thenReturn(
            new LiveGameFeed(
                822810L,
                GameStatus.MID_INNING,
                LocalDate.of(2026, 6, 5),
                1,
                2,
                "TOR",
                "BAL",
                List.of(pitch(1, 1)),
                nextPitchWithBatter(2, 1, 640000L)));

    LivePollingService svc = service(client, repo, predictor);
    svc.pollGame(822810L);
    svc.pollGame(822810L);

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo, times(2)).upsertGameStatus(anyLong(), any(), any(), captor.capture());
    assertThat(captor.getAllValues().get(1).batterId()).isEqualTo(640000L);
  }

  @Test
  void theFirstPollAfterARestartCarriesTheMatchupNotJustAStatus() throws Exception {
    // T-4: after a restart both statusPersisted and lastMatchupKey are empty, so a write happens
    // for two independent reasons - a restart test that only asserts "a write occurred" passes
    // even with the matchup plumbing deleted. Assert the write CARRIES the matchup.
    MlbStatsApiClient client = mock(MlbStatsApiClient.class);
    LivePitchesRepository repo = mock(LivePitchesRepository.class);
    LivePitchPredictor predictor = mock(LivePitchPredictor.class);
    when(predictor.predictAndLog(any())).thenReturn(Map.of("ball", 1.0));
    when(client.fetchLiveFeed(822810L))
        .thenReturn(feed(List.of(pitch(1, 1)), nextPitchWithBatter(4, 2, 610000L)));

    service(client, repo, predictor).pollGame(822810L); // a fresh process

    ArgumentCaptor<CurrentMatchup> captor = ArgumentCaptor.forClass(CurrentMatchup.class);
    verify(repo).upsertGameStatus(eq(822810L), any(), any(), captor.capture());
    assertThat(captor.getValue()).isNotNull();
    assertThat(captor.getValue().batterId()).isEqualTo(610000L);
  }

  /** A current play with an explicit batter id, for pinch-hitter / at-bat-rollover fixtures. */
  private static LiveNextPitch nextPitchWithBatter(int atBat, int pitchNumber, long batterId) {
    return new LiveNextPitch(
        822810L,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        batterId,
        "R",
        "R",
        0,
        0,
        0,
        false,
        false,
        false,
        "TOR",
        LocalDate.of(2026, 6, 5));
  }

  private static LiveNextPitch nullMatchupNextPitch(int atBat, int pitchNumber) {
    return new LiveNextPitch(
        822810L,
        atBat,
        pitchNumber,
        9,
        false,
        689296L,
        676391L,
        null,
        null,
        0,
        0,
        0,
        false,
        false,
        false,
        "TOR",
        LocalDate.of(2026, 6, 5));
  }
}
