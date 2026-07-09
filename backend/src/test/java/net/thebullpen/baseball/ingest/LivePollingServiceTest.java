package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.data.JobLeaseRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.inference.ModelUnavailableException;
import org.junit.jupiter.api.Test;

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
        false);
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

  private static LivePollingService service(
      MlbStatsApiClient client, LivePitchesRepository repo, LivePitchPredictor predictor) {
    return new LivePollingService(
        client,
        repo,
        Optional.of(predictor),
        Optional.empty(),
        new IngestMetrics(new SimpleMeterRegistry()),
        heldLease(),
        0L,
        15L,
        30L);
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

    verify(repo, never()).upsertGameStatus(anyLong(), any(), any());
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
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any());
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
            0L,
            15L,
            30L)
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
            0L,
            15L,
            30L)
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
            0L,
            15L,
            30L)
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
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any());

    // A later poll of the same game (no transition, already persisted) does not re-write.
    svc.pollGame(822810L);
    verify(repo, times(1)).upsertGameStatus(anyLong(), any(), any());
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
        false);
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
