package net.thebullpen.baseball.inference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
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
// Not @Profile-restricted: InferenceRouter (used by the worker's LivePitchPredictor too) injects
// this for the shadow-latency metric, so it must exist in the worker context as well as the api.
@Component
public class InferenceMetrics {

  private static final String LATENCY_METRIC = "thebullpen_inference_prediction_latency_seconds";
  private static final String SHADOW_LATENCY_METRIC = "thebullpen_inference_shadow_latency_seconds";
  private static final String COUNT_METRIC = "thebullpen_inference_prediction_total";
  private static final String ERROR_METRIC = "thebullpen_inference_prediction_errors_total";

  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Timer> shadowTimers = new ConcurrentHashMap<>();
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
                // Histogram-only. In this micrometer-prometheus registry a meter renders as EITHER
                // a
                // summary (client-computed {quantile=}) OR a histogram (_bucket{le=}) under one
                // name,
                // never both - so client .publishPercentiles(...) would be inert once the histogram
                // is on, and is deliberately omitted. The histogram is strictly more capable: a
                // fleet-wide p99 aggregates _bucket across instances, and a per-instance p99 is
                // still
                // available by grouping histogram_quantile() by instance.
                // serviceLevelObjectives(50ms)
                // adds an explicit le=0.05 SLA bucket; min/max bound the ladder to the real latency
                // band (~45 buckets/model instead of the unbounded ~70, and sharper low-end
                // interp).
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50))
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(registry));
  }

  public Timer.Sample startTimer() {
    return Timer.start(registry);
  }

  /**
   * Record the latency of the fire-and-forget SHADOW challenger leg, on its OWN metric so it never
   * blends into the served {@link #LATENCY_METRIC} the user experiences (F1.4). The shadow runs off
   * the request path, so this is the shadow's true wall time, not anything the user waited on.
   */
  public void recordShadowLatency(Timer.Sample sample, String modelName) {
    sample.stop(
        shadowTimers.computeIfAbsent(
            modelName,
            name ->
                Timer.builder(SHADOW_LATENCY_METRIC)
                    .tag("model_name", name)
                    .description("Latency of the off-request-path shadow-challenger inference")
                    .publishPercentileHistogram()
                    .serviceLevelObjectives(Duration.ofMillis(50))
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(1))
                    .register(registry)));
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
