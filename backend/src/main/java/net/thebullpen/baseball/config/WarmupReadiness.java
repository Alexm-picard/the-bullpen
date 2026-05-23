package net.thebullpen.baseball.config;

import ai.onnxruntime.OrtException;
import net.thebullpen.baseball.inference.ToyBattedBallInference;
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
 * Drives 3 warm-up predictions before flipping the readiness probe to UP. Closes Risk Register G11
 * (cold-start spike on first user request).
 *
 * <p>Pattern: Spring publishes {@link ApplicationReadyEvent} once context refresh + servlet
 * container start are done. We hold the readiness probe down until our warm-up completes — without
 * this, the first real prediction takes ~2s (ONNX session graph compile + JIT) and Uptime Robot's
 * sampling would catch it.
 */
@Component
@Profile("api")
public class WarmupReadiness implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(WarmupReadiness.class);
  private static final int WARMUP_ITERATIONS = 3;

  private final ToyBattedBallInference inference;
  private final ApplicationEventPublisher publisher;

  public WarmupReadiness(ToyBattedBallInference inference, ApplicationEventPublisher publisher) {
    this.inference = inference;
    this.publisher = publisher;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
    try {
      long start = System.nanoTime();
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        inference.predict(95.0, 28.0, 92.0, "NYY", "R");
      }
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      log.info("warm-up complete iterations={} elapsed_ms={}", WARMUP_ITERATIONS, elapsedMs);
      AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    } catch (OrtException ex) {
      log.error("warm-up failed; readiness stays DOWN", ex);
    }
  }
}
