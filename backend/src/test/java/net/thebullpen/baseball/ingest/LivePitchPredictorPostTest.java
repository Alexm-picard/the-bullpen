package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.data.PitcherFormRepository;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedPitchModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.registry.RegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Focused unit coverage for the POST-head live leg (F2.1a): the completeness gate that skips a
 * pitch whose derived Tier-4 fit is incomplete (never feeding NaN to the model), and the happy path
 * that routes a fully-formed completed pitch through {@code pitch_outcome_post} and enqueues a
 * keyed {@code prediction_log} row. Collaborators are mocked at their boundaries (router, model
 * loader, registry, async logger, ingest metrics); the form repo is absent (Tier-3 -&gt; NaN).
 */
class LivePitchPredictorPostTest {

  private final InferenceRouter router = mock(InferenceRouter.class);
  private final ModelLoader modelLoader = mock(ModelLoader.class);
  private final RegistryService registry = mock(RegistryService.class);
  private final AsyncPredictionLogger logger = mock(AsyncPredictionLogger.class);
  private final IngestMetrics ingestMetrics = mock(IngestMetrics.class);

  private final LivePitchPredictor predictor =
      new LivePitchPredictor(
          router,
          modelLoader,
          registry,
          logger,
          Optional.<PitcherFormRepository>empty(),
          ingestMetrics);

  @Test
  void incomplete_tier4_is_skipped_counted_and_never_logged() throws Exception {
    // spinRateRpm null -> the derived fit is incomplete (a tracking blip): the pitch must be
    // skipped, counted, and never routed or logged (no NaN fed to the post head).
    LivePitch pitch = pitch(/* spinRateRpm= */ null);

    Map<String, Double> result =
        predictor.predictPostAndLog(pitch, "TOR", LocalDate.of(2026, 6, 5));

    assertThat(result).isEmpty();
    verify(ingestMetrics, times(1)).incrementPostTier4Incomplete();
    verify(logger, never()).enqueue(any());
  }

  @Test
  void null_park_is_skipped_without_logging_and_without_counting_a_blip() throws Exception {
    // A game-end feed whose nextPitch is absent (no park): a BENIGN skip, not a tracking blip, so
    // it
    // must NOT inflate the incomplete-fit counter (registry-guard note).
    LivePitch pitch = pitch(/* spinRateRpm= */ 2200.0);

    Map<String, Double> result =
        predictor.predictPostAndLog(pitch, /* parkId= */ null, LocalDate.of(2026, 6, 5));

    assertThat(result).isEmpty();
    verify(ingestMetrics, never()).incrementPostTier4Incomplete();
    verify(logger, never()).enqueue(any());
  }

  @Test
  void full_tier4_routes_post_head_and_enqueues_a_keyed_post_row() throws Exception {
    LivePitch pitch = pitch(/* spinRateRpm= */ 2200.0);

    Map<String, Double> probs =
        Map.of(
            "ball", 0.40,
            "called_strike", 0.20,
            "swinging_strike", 0.10,
            "foul", 0.10,
            "in_play", 0.20);
    // The champion (version_id 100) served; no shadow row in this config.
    RoutedPrediction<Map<String, Double>> routed =
        new RoutedPrediction<>(probs, 100L, Role.CHAMPION, Optional.empty(), Optional.empty());
    doReturn(routed)
        .when(router)
        .route(eq(LivePitchPredictor.POST_MODEL_NAME), anyLong(), any(), any());

    LoadedPitchModel servingModel = mock(LoadedPitchModel.class);
    when(servingModel.version()).thenReturn("v1");
    when(servingModel.schemaHash()).thenReturn("post-schema-hash");
    when(modelLoader.loadPitchPost(100L)).thenReturn(servingModel);

    Map<String, Double> result =
        predictor.predictPostAndLog(pitch, "TOR", LocalDate.of(2026, 6, 5));

    assertThat(result).isEqualTo(probs);
    verify(ingestMetrics, never()).incrementPostTier4Incomplete();

    ArgumentCaptor<PredictionLogEvent> captor = ArgumentCaptor.forClass(PredictionLogEvent.class);
    verify(logger, times(1)).enqueue(captor.capture());
    PredictionLogEvent event = captor.getValue();
    assertThat(event.modelName()).isEqualTo("pitch_outcome_post");
    assertThat(event.modelVersion()).isEqualTo("v1");
    assertThat(event.modelVersionId()).isEqualTo(100L);
    assertThat(event.role()).isEqualTo(PredictionLogEvent.Role.CHAMPION);
    // Keyed to the pitch that produced it (issue #1 step 5 truth-join key).
    assertThat(event.gameId()).isEqualTo(822810L);
    assertThat(event.atBatIndex()).isEqualTo(4);
    assertThat(event.pitchNumber()).isEqualTo(3);
  }

  /**
   * A completed pitch with a full Tier-4 fit except for {@code spinRateRpm}, which the caller
   * toggles: {@code null} exercises the completeness gate, a value exercises the happy path.
   */
  private static LivePitch pitch(Double spinRateRpm) {
    return new LivePitch(
        822810L, // gameId
        4, // atBatIndex
        3, // pitchNumber
        9, // inning
        false, // topInning
        689296L, // pitcherId
        676391L, // batterId
        "R", // pitchHand
        "R", // batSide
        1, // preBalls
        1, // preStrikes
        2, // outs
        false, // onFirst
        false, // onSecond
        false, // onThird
        0, // homeScore
        0, // awayScore
        "in_play", // description
        "SI", // pitchType
        95.0, // releaseSpeedMph
        0.3, // plateXIn
        2.1, // plateZIn
        -0.5, // pfxXIn
        1.2, // pfxZIn
        spinRateRpm, // spinRateRpm (toggled)
        200.0, // spinAxisDeg
        -1.6, // releasePosXIn
        5.9, // releasePosZIn
        true); // terminal
  }
}
