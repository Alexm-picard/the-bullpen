package net.thebullpen.baseball.drift;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Ops-visible drift-health signals (Wave E / E-1 part 2b). Worker profile only.
 *
 * <p>The PSI jobs skip a model whose {@code metadata.json} carries no training-distribution
 * baseline (the {@code feature_distributions} / {@code training_prediction_distribution} blocks the
 * backfill CLI or a native emission writes). For a SHADOW challenger that skip is benign - its PSI
 * is nice-to-have. For a CHAMPION it means <em>PSI is dark for the production model</em>: the exact
 * silent-starve that produced zero drift rows on 2026-07-04 and triggered decision [175]. That case
 * used to {@code return} with no signal; this counter makes it alertable.
 *
 * <ul>
 *   <li>{@code bullpen_drift_baseline_missing_total{model,kind}} - counter, incremented once per
 *       daily PSI run per champion that lacks a baseline. {@code kind} is {@code feature} (the
 *       {@link net.thebullpen.baseball.drift.jobs.PsiFeatureJob}) or {@code prediction} (the {@link
 *       net.thebullpen.baseball.drift.jobs.PsiPredictionJob}). {@code model} is the model name
 *       (stable across retrains; the version lives in the WARN log, not the tag, to keep
 *       cardinality bounded). Alert: {@code sum by (model,kind)
 *       (increase(bullpen_drift_baseline_missing_total[25h])) > 0} - a production champion has been
 *       PSI-dark for a full daily cycle. Remediation: run {@code
 *       scripts/backfill_training_distributions.py --model <model>} against the champion bundle.
 * </ul>
 *
 * <p>Only CHAMPION misses are counted; a SHADOW miss is logged at INFO by the job and does not
 * touch this counter, so the alert stays clean (any nonzero value = a served model is dark).
 */
@Component
@Profile("worker")
public class DriftHealthMetrics {

  static final String BASELINE_MISSING_METRIC = "bullpen_drift_baseline_missing_total";

  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, Counter> baselineMissing = new ConcurrentHashMap<>();

  public DriftHealthMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /**
   * A serving CHAMPION was found with no training-distribution baseline during a daily PSI run.
   *
   * @param modelName the low-cardinality model name (not the versioned natural key)
   * @param kind {@code feature} or {@code prediction} - which PSI job saw the empty reference
   */
  public void markChampionMissingBaseline(String modelName, String kind) {
    baselineMissing
        .computeIfAbsent(
            modelName + "|" + kind,
            k ->
                Counter.builder(BASELINE_MISSING_METRIC)
                    .description(
                        "Daily PSI runs that found a serving champion with no training-distribution"
                            + " baseline (PSI dark for a production model)")
                    .tag("model", modelName)
                    .tag("kind", kind)
                    .register(registry))
        .increment();
  }
}
