package net.thebullpen.baseball.ingest;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.thebullpen.baseball.data.PitcherForm;
import net.thebullpen.baseball.data.PitcherFormRepository;
import net.thebullpen.baseball.inference.AsyncPredictionLogger;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.InferenceRouter;
import net.thebullpen.baseball.inference.LoadedPitchModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.RoutedPrediction;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the pre-pitch head on the about-to-be-thrown pitch (decision [143] predict-next) and
 * enqueues a {@code prediction_log} row keyed to {@code (game_id, at_bat_index, pitch_number)} so
 * it reconciles to that pitch when it lands (step 5 LEFT JOIN). Worker-profile, in-process
 * inference (decision [27]).
 *
 * <p>W1b: rewired through {@link InferenceRouter} + {@link ModelLoader} so the live prediction
 * carries a REAL {@code model_version_id} FK (it used to log {@code null}, so per-version drift
 * attribution could not join the live row back to the registry). The {@code pitch_outcome_pre}
 * champion serves the live row; any SHADOW challenger runs in parallel and is logged with the same
 * live-game key so its later reconciliation is exact. The poller's per-game cursor drives
 * consistent bucketing - the {@code gameId} keys the router's bucket so a game routes to the same
 * challenger across polls.
 *
 * <p>Graceful degradation: when no champion and no routing config exist for {@code
 * pitch_outcome_pre}, there is nothing to serve. Rather than throw (which would abort the poll
 * tick), {@link #predictAndLog} returns an empty map and logs nothing - the live path is
 * best-effort and must not crash the poller. This is the live-path analogue of the HTTP path's 503.
 *
 * <p>Feature conventions match the training pipeline exactly to avoid train/serve skew:
 *
 * <ul>
 *   <li>{@code score_diff = 0} - the production pre-head trained on a constant-0 placeholder
 *       ({@code select_labeled_pitches.sql}: {@code toInt16(0) AS score_diff}); a real score would
 *       be skew on a feature the model never varied over.
 *   <li>{@code dow} = ISO day-of-week 1=Mon..7=Sun, matching ClickHouse {@code toDayOfWeek}.
 *   <li>{@code base_state} = 1/2/4 runner bitmask (on_1b/2b/3b).
 *   <li>Tier 3 form = null (decision [143]); LightGBM treats it as NaN. A documented skew, watched
 *       as a separate live-calibration metric, closed later by {@code pitcher_form_current}.
 * </ul>
 */
@Component
@Profile("worker")
public class LivePitchPredictor {

  private static final Logger log = LoggerFactory.getLogger(LivePitchPredictor.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  static final String MODEL_NAME = "pitch_outcome_pre";

  private final InferenceRouter router;
  private final ModelLoader modelLoader;
  private final RegistryService registry;
  private final AsyncPredictionLogger logger;

  /**
   * Short-TTL cache over {@code pitcher_form_current} so the poll loop never issues one CH read per
   * pitch per tick: a pitcher is looked up at most once per 60s across all games. {@code null} when
   * no {@link PitcherFormRepository} bean exists (ClickHouse disabled) - then form stays absent and
   * the request forwards NaN, the pre-A3 behavior. 60s staleness on {@code pitches_in_game} is
   * immaterial (a few pitches of in-game fatigue signal).
   */
  private final LoadingCache<Long, Optional<PitcherForm>> formCache;

  public LivePitchPredictor(
      InferenceRouter router,
      ModelLoader modelLoader,
      RegistryService registry,
      AsyncPredictionLogger logger,
      Optional<PitcherFormRepository> formRepo) {
    this.router = router;
    this.modelLoader = modelLoader;
    this.registry = registry;
    this.logger = logger;
    this.formCache =
        formRepo
            .map(
                repo ->
                    Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .maximumSize(2_000)
                        .build((Long pitcherId) -> repo.findCurrent(pitcherId)))
            .orElse(null);
  }

  /**
   * Cached current form for the pitcher, or empty when ClickHouse is absent / the pitcher is new.
   */
  private Optional<PitcherForm> lookupForm(long pitcherId) {
    return formCache == null ? Optional.empty() : formCache.get(pitcherId);
  }

  /**
   * Route the next pitch through the {@code pitch_outcome_pre} champion and enqueue a keyed {@code
   * prediction_log} row carrying the real {@code model_version_id}. Returns the calibrated 5-class
   * distribution (the poller may surface it for live display). Returns an empty map when no
   * champion / routing exists (degrade, never throw on the poll path).
   */
  public Map<String, Double> predictAndLog(LiveNextPitch ctx) throws OrtException {
    Instant requestAt = Instant.now();
    long startNanos = System.nanoTime();
    Optional<PitcherForm> form = lookupForm(ctx.pitcherId());
    FeaturePipelinePitchPre.Request featureReq = toRequest(ctx, form);

    Optional<ModelVersion> champion = registry.findChampion(MODEL_NAME);

    RoutedPrediction<Map<String, Double>> routed;
    try {
      routed =
          router.route(
              MODEL_NAME,
              ctx.gameId(),
              versionId -> predict(versionId, featureReq),
              () -> {
                // No routing config: serve the LIVE champion if one exists; otherwise signal "no
                // model" with null so the caller degrades instead of logging a null-FK row.
                if (champion.isEmpty()) {
                  return null;
                }
                return predict(champion.get().id(), featureReq);
              });
    } catch (RuntimeException e) {
      throw unwrapOrt(e);
    }

    if (routed.servingResponse() == null) {
      log.debug(
          "LivePitchPredictor: no {} champion / routing config - skipping live prediction for"
              + " game {}",
          MODEL_NAME,
          ctx.gameId());
      return Map.of();
    }

    float latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0f;

    // Resolve the serving version's identity. servingVersionId == -1 means the legacy fallback
    // served the champion (re-resolve it for the FK + version label + schema hash). A registered
    // version knows its own identity from the loaded snapshot.
    long servingVersionId =
        routed.servingVersionId() == -1L ? champion.orElseThrow().id() : routed.servingVersionId();
    LoadedPitchModel servingModel = modelLoader.loadPitchPre(servingVersionId);

    logger.enqueue(
        buildEvent(
            ctx,
            featureReq,
            routed.servingResponse(),
            requestAt,
            servingModel.version(),
            servingVersionId,
            servingModel.schemaHash(),
            mapRole(routed.servingRole()),
            latencyMs));

    // Shadow row logged FIRE-AND-FORGET off the request path (F1.4).
    routed
        .shadowFuture()
        .ifPresent(
            shadowFut -> {
              long shadowVid = routed.shadowVersionId().orElseThrow();
              shadowFut.whenComplete(
                  (shadowResp, ex) -> {
                    if (ex != null) {
                      return;
                    }
                    LoadedPitchModel shadowModel = modelLoader.loadPitchPre(shadowVid);
                    logger.enqueue(
                        buildEvent(
                            ctx,
                            featureReq,
                            shadowResp,
                            requestAt,
                            shadowModel.version(),
                            shadowVid,
                            shadowModel.schemaHash(),
                            PredictionLogEvent.Role.SHADOW,
                            latencyMs));
                  });
            });

    return routed.servingResponse();
  }

  private Map<String, Double> predict(long versionId, FeaturePipelinePitchPre.Request req) {
    try {
      return modelLoader.loadPitchPre(versionId).predictPre(req);
    } catch (OrtException e) {
      throw new RuntimeException(e);
    }
  }

  private static OrtException unwrapOrt(RuntimeException e) {
    if (e.getCause() instanceof OrtException ort) {
      return ort;
    }
    throw e;
  }

  private static PredictionLogEvent.Role mapRole(net.thebullpen.baseball.inference.routing.Role r) {
    return switch (r) {
      case CHAMPION -> PredictionLogEvent.Role.CHAMPION;
      case CHALLENGER -> PredictionLogEvent.Role.CHALLENGER;
      case SHADOW -> PredictionLogEvent.Role.SHADOW;
    };
  }

  /**
   * Assemble the pre-head request from the live context (conventions per the class doc). When
   * {@code form} is present (A3), it fills the six pitcher-side Tier-3 slots from {@code
   * pitcher_form_current}; {@code pitcherStrikeRateStd} and the four batter-side rates are not
   * materialised there and stay null -&gt; NaN. When {@code form} is empty (no ClickHouse, or a
   * pitcher with no current row) ALL eleven stay null, the pre-A3 behavior.
   */
  static FeaturePipelinePitchPre.Request toRequest(LiveNextPitch ctx, Optional<PitcherForm> form) {
    Double pitchesLast28d = form.map(PitcherForm::pitchesLast28d).orElse(null);
    Double pitchesInGame = form.map(PitcherForm::pitchesInGame).orElse(null);
    Double daysSinceLastAppearance = form.map(PitcherForm::daysSinceLastAppearance).orElse(null);
    Double strikeRate28d = form.map(PitcherForm::strikeRate28d).orElse(null);
    Double swstrikeRate28d = form.map(PitcherForm::swstrikeRate28d).orElse(null);
    Double inplayRate28d = form.map(PitcherForm::inplayRate28d).orElse(null);
    return new FeaturePipelinePitchPre.Request(
        ctx.balls(),
        ctx.strikes(),
        ctx.outs(),
        ctx.inning(),
        ctx.baseState(),
        0, // score_diff: training placeholder is a constant 0 (no real score, no skew)
        ctx.gameDate().getDayOfWeek().getValue(), // dow: ISO 1=Mon..7=Sun == toDayOfWeek
        ctx.pitchHand(),
        resolveBatSide(ctx.batSide(), ctx.pitchHand()),
        ctx.parkId(),
        ctx.pitcherId(),
        ctx.batterId(),
        // Tier 3: six pitcher-side slots from pitcher_form_current (A3); the rest null -> NaN.
        pitchesLast28d, // pitcherPitchesLast28d
        pitchesInGame, // pitcherPitchesInGame
        daysSinceLastAppearance,
        strikeRate28d, // pitcherStrikeRate28d
        swstrikeRate28d, // pitcherSwstrikeRate28d
        inplayRate28d, // pitcherInplayRate28d
        null, // pitcherStrikeRateStd - not in pitcher_form_current
        null, // batterStrikeRate28d
        null, // batterInplayRate28d
        null, // batterBallRate28d
        null); // batterInplayRateStd
  }

  /**
   * True when the GUMBO payload carries the matchup the pre-head needs (pitcher + batter
   * handedness). Early {@code currentPlay} payloads can omit these for a sub-second window at the
   * top of an at-bat; the poller skips prediction until they populate (C5) rather than feed nulls
   * to the model (which would be a degraded prediction on missing matchup data, or an NPE
   * downstream of {@link #resolveBatSide}).
   */
  static boolean hasResolvableMatchup(LiveNextPitch np) {
    return np.pitchHand() != null && np.batSide() != null;
  }

  /** Switch hitters bat opposite the pitcher's hand; the model expects a resolved {@code L|R}. */
  static String resolveBatSide(String batSide, String pitchHand) {
    if (!"S".equals(batSide)) {
      return batSide;
    }
    return "L".equals(pitchHand) ? "R" : "L";
  }

  /**
   * Build the keyed event for one routed role. The {@code modelVersionId} FK is now REAL (W1b): the
   * router resolved the registered version that produced {@code probs}, so the live row joins back
   * to the registry for per-version drift attribution. The live-game key {@code (gameId,
   * atBatIndex, pitchNumber)} is what step 5 reconciles against when the pitch lands.
   */
  static PredictionLogEvent buildEvent(
      LiveNextPitch ctx,
      FeaturePipelinePitchPre.Request featureReq,
      Map<String, Double> probs,
      Instant requestAt,
      String modelVersion,
      long modelVersionId,
      String schemaHash,
      PredictionLogEvent.Role role,
      float latencyMs) {
    String winner = argmax(probs);
    return new PredictionLogEvent(
        UUID.randomUUID(),
        requestAt,
        MODEL_NAME,
        modelVersion,
        modelVersionId,
        role,
        schemaHash,
        // Serialize the SAME request that produced probs (carries the A3 form values), not a
        // rebuilt
        // one - so the logged feature vector matches what was scored.
        serialize(featureReq),
        serializePrediction(probs, winner),
        latencyMs,
        MDC.get("correlation_id"),
        ctx.gameId(),
        ctx.atBatIndex(),
        ctx.pitchNumber());
  }

  static String argmax(Map<String, Double> probs) {
    String best = null;
    double bestVal = Double.NEGATIVE_INFINITY;
    for (Map.Entry<String, Double> e : probs.entrySet()) {
      if (e.getValue() > bestVal) {
        best = e.getKey();
        bestVal = e.getValue();
      }
    }
    return best == null ? "unknown" : best;
  }

  private static String serialize(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }

  private static String serializePrediction(Map<String, Double> probs, String winner) {
    try {
      return MAPPER.writeValueAsString(Map.of("probabilities", probs, "winner", winner));
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
