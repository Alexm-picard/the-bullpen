package net.thebullpen.baseball.config;

import java.util.Optional;
import java.util.OptionalLong;
import net.thebullpen.baseball.inference.FeaturePipelineBattedBall;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPost;
import net.thebullpen.baseball.inference.FeaturePipelinePitchPre;
import net.thebullpen.baseball.inference.LoadedAllParksModel;
import net.thebullpen.baseball.inference.LoadedPitchModel;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.PitchPredictionService;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Drives warm-up predictions before flipping the readiness probe to UP. Closes Risk Register G11
 * (cold-start spike on first user request).
 *
 * <p>Pattern: Spring publishes {@link ApplicationReadyEvent} once context refresh + servlet
 * container start are done. We hold the readiness probe down until warm-up completes - without
 * this, the first real prediction takes ~2s (ONNX session graph compile + JIT) and Uptime Robot's
 * sampling would catch it.
 *
 * <p>Warms the models that are actually SERVED, via the same load + predict paths the controllers
 * use, so the graphs that pay the compile cost on a real request are the ones we warm:
 *
 * <ul>
 *   <li><b>{@code battedball_outcome}</b> (the user-facing {@code /parks} surface, served through
 *       {@link ModelLoader#loadAllParks}) is FAIL-CLOSED: if it is registered (routing row or live
 *       champion) but its champion cannot load, readiness stays DOWN - the api cannot serve real
 *       predictions, so it must not advertise readiness. Its challenger (when present) is warmed
 *       best-effort, so a shadow-routed challenger - exactly what a retrain candidate becomes - is
 *       not cold on its first, timeout-bounded shadow call after a restart.
 *   <li>The <b>pitch heads</b> have no user-facing surface (a direct {@code /v1/predict/pitch}
 *       caller aside), so they are warmed BEST-EFFORT: a failure is logged and never fails
 *       readiness.
 * </ul>
 *
 * <p>When nothing is registered (fresh / unregistered environments) we warm the toy so readiness
 * reflects a live JVM and never hangs; {@code /parks} would return 503 there, which is honest.
 */
@Component
@Profile("api")
public class WarmupReadiness implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(WarmupReadiness.class);
  static final int WARMUP_ITERATIONS = 3; // package-private so the test asserts against it

  /**
   * The registry name of the served batted-ball model. It MUST equal {@code
   * PredictAllParksController.MODEL_NAME} (package-private in {@code api}, deliberately not
   * imported so {@code config} does not depend on {@code api}). There is no automated cross-check,
   * so a rename must touch both; if they diverge, warm-up would fail-closed on the wrong name and
   * the actually-served champion would never be warmed.
   */
  static final String BATTED_BALL_MODEL = "battedball_outcome";

  private final ToyBattedBallInference toy;
  private final ModelLoader modelLoader;
  private final RoutingService routingService;
  private final RegistryService registry;
  private final ApplicationEventPublisher publisher;

  public WarmupReadiness(
      ToyBattedBallInference toy,
      ModelLoader modelLoader,
      RoutingService routingService,
      RegistryService registry,
      ApplicationEventPublisher publisher) {
    this.toy = toy;
    this.modelLoader = modelLoader;
    this.routingService = routingService;
    this.registry = registry;
    this.publisher = publisher;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
    try {
      warm();
      AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    } catch (Exception ex) {
      // Fail-closed: a served-champion warm failure leaves readiness DOWN (the api can't serve
      // /parks anyway). Errors (e.g. a broken native ONNX runtime) are intentionally NOT caught -
      // that is an environment-level defect that should surface loudly and abort startup (DEF-L3).
      log.error("warm-up failed; readiness stays DOWN", ex);
    }
  }

  private void warm() throws Exception {
    long start = System.nanoTime();
    String battedBall = warmBattedBall(); // fail-closed (may throw -> readiness stays DOWN)
    String pitchPre = warmPitchPreBestEffort();
    String pitchPost = warmPitchPostBestEffort();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    log.info(
        "warm-up complete: battedball[{}] pitch_pre[{}] pitch_post[{}] iterations={} elapsed_ms={}",
        battedBall,
        pitchPre,
        pitchPost,
        WARMUP_ITERATIONS,
        elapsedMs);
  }

  // --- batted-ball (fail-closed, the served /parks surface) ----------------

  private String warmBattedBall() throws Exception {
    FeaturePipelineBattedBall.Request req = sampleBattedBallRequest();
    Optional<RoutingConfig> cfgOpt = routingService.findRouting(BATTED_BALL_MODEL);
    if (cfgOpt.isPresent()) {
      RoutingConfig cfg = cfgOpt.get();
      warmAllParks(cfg.championVersionId(), req); // fail-closed: propagates
      String label = "routing champion v" + cfg.championVersionId();
      if (cfg.hasChallenger()) {
        label += warmChallengerBestEffort(cfg.challengerVersionId(), req);
      }
      return label;
    }
    // No routing row: /parks still serves the registry LIVE champion (the controller's fallback),
    // so
    // warm that - it is the graph a real request runs, and it must load or readiness stays DOWN.
    Optional<ModelVersion> champ = registry.findChampion(BATTED_BALL_MODEL);
    if (champ.isPresent()) {
      warmAllParks(champ.get().id(), req); // fail-closed
      return "registry champion v" + champ.get().id() + " (no routing row)";
    }
    // Truly unregistered: /parks would 503. Warm the toy so readiness reflects a live JVM.
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      toy.predict(95.0, 28.0, 92.0, "NYY", "R");
    }
    return "toy (no " + BATTED_BALL_MODEL + " registered)";
  }

  private void warmAllParks(long versionId, FeaturePipelineBattedBall.Request req)
      throws Exception {
    LoadedAllParksModel model = modelLoader.loadAllParks(versionId);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      model.predictWithCarry(req);
    }
  }

  String warmChallengerBestEffort(long versionId, FeaturePipelineBattedBall.Request req) {
    // Mirrors the router's silent-degrade contract: a broken shadow challenger degrades silently in
    // serving, so a warm failure must NOT fail readiness.
    try {
      warmAllParks(versionId, req);
      return " + challenger v" + versionId;
    } catch (Exception ex) {
      log.warn(
          "warm-up: {} challenger v{} failed to warm; readiness unaffected",
          BATTED_BALL_MODEL,
          versionId,
          ex);
      return " + challenger v" + versionId + " (warm failed)";
    }
  }

  // --- pitch heads (best-effort; no user-facing surface) -------------------

  private String warmPitchPreBestEffort() {
    // The resolution lookup (routing + registry) is INSIDE the try: a transient registry/routing
    // failure during a pitch head's warm is best-effort too and must never fail readiness. Only the
    // served battedball_outcome champion is fail-closed.
    try {
      OptionalLong vid = resolveChampionId(PitchPredictionService.PRE_MODEL_NAME);
      if (vid.isEmpty()) {
        return "skipped (not registered)";
      }
      LoadedPitchModel model = modelLoader.loadPitchPre(vid.getAsLong());
      FeaturePipelinePitchPre.Request req = samplePitchPreRequest();
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        model.predictPre(req);
      }
      return "champion v" + vid.getAsLong();
    } catch (Exception ex) {
      log.warn(
          "warm-up: {} failed to warm; readiness unaffected",
          PitchPredictionService.PRE_MODEL_NAME,
          ex);
      return "warm failed";
    }
  }

  private String warmPitchPostBestEffort() {
    try {
      OptionalLong vid = resolveChampionId(PitchPredictionService.POST_MODEL_NAME);
      if (vid.isEmpty()) {
        return "skipped (not registered)";
      }
      LoadedPitchModel model = modelLoader.loadPitchPost(vid.getAsLong());
      FeaturePipelinePitchPost.Request req = samplePitchPostRequest();
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        model.predictPost(req);
      }
      return "champion v" + vid.getAsLong();
    } catch (Exception ex) {
      log.warn(
          "warm-up: {} failed to warm; readiness unaffected",
          PitchPredictionService.POST_MODEL_NAME,
          ex);
      return "warm failed";
    }
  }

  // --- resolution (pure; service-only, unit-testable without ONNX) ---------

  /**
   * The version id whose graph a real request for {@code modelName} would run: the routing champion
   * when a routing row exists, else the registry LIVE champion, else empty (unregistered).
   */
  OptionalLong resolveChampionId(String modelName) {
    Optional<RoutingConfig> cfgOpt = routingService.findRouting(modelName);
    if (cfgOpt.isPresent()) {
      return OptionalLong.of(cfgOpt.get().championVersionId());
    }
    return registry
        .findChampion(modelName)
        .map(mv -> OptionalLong.of(mv.id()))
        .orElse(OptionalLong.empty());
  }

  // --- sample requests (values are irrelevant; they only exercise the graph) --

  private static FeaturePipelineBattedBall.Request sampleBattedBallRequest() {
    return new FeaturePipelineBattedBall.Request(95.0, 28.0, 10.0, 380.0, "R", 0, 0);
  }

  private static FeaturePipelinePitchPre.Request samplePitchPreRequest() {
    // Tier-3 form values are nullable (forwarded as NaN), so a warm sample can leave them null.
    return new FeaturePipelinePitchPre.Request(
        1, 1, 0, 1, 0, 0, 3, "R", "R", "NYY", 0L, 0L, null, null, null, null, null, null, null,
        null, null, null, null);
  }

  private static FeaturePipelinePitchPost.Request samplePitchPostRequest() {
    // Tier-3 null (NaN); Tier-4 populated (required for the post head).
    return new FeaturePipelinePitchPost.Request(
        1, 1, 0, 1, 0, 0, 3, "R", "R", "NYY", 0L, 0L, null, null, null, null, null, null, null,
        null, null, null, null, "FF", 92.0, 0.0, 2.5, 0.0, 1.0, 2200.0, 200.0, -12.0, 60.0);
  }
}
