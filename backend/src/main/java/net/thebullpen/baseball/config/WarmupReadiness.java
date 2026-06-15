package net.thebullpen.baseball.config;

import java.util.Optional;
import net.thebullpen.baseball.inference.ModelLoader;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingService;
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
 * <p>Warms the <b>registry-served champion</b> (the model a real {@code /v1/predict/batted-ball}
 * request actually runs), not the toy - the toy's graph is irrelevant to user-facing latency once a
 * champion is registered. Falls back to the toy only when no routing row exists (unregistered-model
 * environments), so warm-up never hangs readiness. A champion that fails to load fails readiness
 * (fail-closed: the api can't serve real predictions either way); a shadow/challenger that fails to
 * warm is logged but does NOT fail readiness, mirroring the router's silent-degrade contract.
 */
@Component
@Profile("api")
public class WarmupReadiness implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(WarmupReadiness.class);
  private static final int WARMUP_ITERATIONS = 3;

  private final ToyBattedBallInference toy;
  private final ModelLoader modelLoader;
  private final RoutingService routingService;
  private final ApplicationEventPublisher publisher;

  public WarmupReadiness(
      ToyBattedBallInference toy,
      ModelLoader modelLoader,
      RoutingService routingService,
      ApplicationEventPublisher publisher) {
    this.toy = toy;
    this.modelLoader = modelLoader;
    this.routingService = routingService;
    this.publisher = publisher;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
    try {
      warm();
      AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    } catch (Exception ex) {
      log.error("warm-up failed; readiness stays DOWN", ex);
    }
  }

  private void warm() throws Exception {
    Optional<RoutingConfig> cfgOpt = routingService.findRouting(ToyBattedBallInference.MODEL_NAME);
    long start = System.nanoTime();
    String warmed;
    if (cfgOpt.isPresent()) {
      RoutingConfig cfg = cfgOpt.get();
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        modelLoader.loadBattedBall(cfg.championVersionId()).predict(95.0, 28.0, 92.0, "NYY", "R");
      }
      warmed = "registry champion v" + cfg.championVersionId();
      if (cfg.hasChallenger()) {
        // Best-effort, mirrors the router's silent-degrade contract: a broken shadow must NOT fail
        // readiness (it degrades silently in serving), so warm it but swallow failures.
        try {
          for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            modelLoader
                .loadBattedBall(cfg.challengerVersionId())
                .predict(95.0, 28.0, 92.0, "NYY", "R");
          }
          warmed += " + challenger v" + cfg.challengerVersionId();
        } catch (Exception ex) {
          log.warn(
              "warm-up: challenger v{} failed to warm; readiness unaffected",
              cfg.challengerVersionId(),
              ex);
        }
      }
    } else {
      // No routing row: serving falls back to the toy (the router's legacyFallback), so warm that.
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        toy.predict(95.0, 28.0, 92.0, "NYY", "R");
      }
      warmed = "toy (no routing row registered)";
    }
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    log.info(
        "warm-up complete via {} iterations={} elapsed_ms={}",
        warmed,
        WARMUP_ITERATIONS,
        elapsedMs);
  }
}
