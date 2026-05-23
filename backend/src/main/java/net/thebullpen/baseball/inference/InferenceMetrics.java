package net.thebullpen.baseball.inference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Micrometer wrappers for inference-path metrics (Phase 1.5).
 *
 * <p>Metric names use the documented prefix:
 *
 * <ul>
 *   <li>{@code thebullpen_inference_prediction_latency_seconds{model_name}} — histogram
 *   <li>{@code thebullpen_inference_prediction_total{model_name,role}} — counter
 *   <li>{@code thebullpen_inference_prediction_errors_total{model_name,error_class}} — counter
 * </ul>
 *
 * <p>One timer/counter per (model_name[, label]) combo is cached so we don't allocate per request.
 */
@Component
@Profile("api")
public class InferenceMetrics {

  private static final String LATENCY_METRIC = "thebullpen_inference_prediction_latency_seconds";
  private static final String COUNT_METRIC = "thebullpen_inference_prediction_total";
  private static final String ERROR_METRIC = "thebullpen_inference_prediction_errors_total";

  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Counter> errors = new ConcurrentHashMap<>();

  public InferenceMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public Timer timer(String modelName) {
    return timers.computeIfAbsent(
        modelName,
        name ->
            Timer.builder(LATENCY_METRIC)
                .tag("model_name", name)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry));
  }

  public Timer.Sample startTimer() {
    return Timer.start(registry);
  }

  public void incrementPrediction(String modelName, String role) {
    counters
        .computeIfAbsent(
            modelName + "|" + role,
            key ->
                Counter.builder(COUNT_METRIC)
                    .tag("model_name", modelName)
                    .tag("role", role)
                    .register(registry))
        .increment();
  }

  public void incrementError(String modelName, String errorClass) {
    errors
        .computeIfAbsent(
            modelName + "|" + errorClass,
            key ->
                Counter.builder(ERROR_METRIC)
                    .tag("model_name", modelName)
                    .tag("error_class", errorClass)
                    .register(registry))
        .increment();
  }
}
