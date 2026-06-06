package net.thebullpen.baseball.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.data.LivePitchesRepository;
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
    return new LivePollingService(client, repo, Optional.of(predictor), 0L, 15L);
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

    // No predictor bean (no model artifact) -> Optional.empty().
    new LivePollingService(client, repo, Optional.empty(), 0L, 15L).pollGame(822810L);

    verify(repo, times(1)).insertPitches(any());
  }
}
