package net.thebullpen.baseball.drift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.drift.TrainingDistributionLoader.ReferenceDistributions;
import net.thebullpen.baseball.inference.PredictionLogEvent;
import net.thebullpen.baseball.inference.PredictionLogWriter;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The LIVE-PATH induced-drift injector for the E-2 drill (plan Wave E, decision [175]). Writes
 * synthetic {@code prediction_log} rows so the REAL detection chain - {@link
 * RealFeatureDistributionFetcher} -&gt; {@link net.thebullpen.baseball.drift.jobs.PsiFeatureJob}
 * -&gt; {@code drift_metrics} -&gt; {@link
 * net.thebullpen.baseball.drift.alerting.DriftAlertEvaluator} NOTICE -&gt; {@link
 * net.thebullpen.baseball.retraining.triggers.DriftTrigger} - fires end-to-end on production
 * ClickHouse. This is the live-path counterpart to {@code DriftInductionDrillIT}, which proves the
 * math + alert chain but MOCKS every ClickHouse read (so it never exercises the prediction_log
 * observed-side that E-2 needs).
 *
 * <p><b>Scope: feature-PSI only.</b> The drill's second lane (over-confidence -&gt; calibration
 * ECE) is NOT inducible for {@code battedball_outcome} via prediction_log injection: {@link
 * ClickHouseTruthJoinedPredictionFetcher} joins prediction_log to {@code pitches_live} on the pitch
 * key with {@code game_id IS NOT NULL} and parses a {@code {"probabilities":...}} pitch payload, so
 * the batted-ball family (HTTP-path, {@code game_id} null, per-park {@code Map} prediction, batted
 * outcomes) has no live truth-join at all - its calibration is offline (the /accuracy scorecard +
 * the isotonic promotion gate). During the All-Star break there are no live pitches to join to
 * anyway. Feature-PSI is the primary, live-proven (2026-07-10), break-compatible drift signal.
 *
 * <p><b>Faithfulness by construction.</b> Rows are built from {@link PredictionLogEvent} and
 * written through the exact {@link PredictionLogWriter} the served all-parks path uses, so they are
 * byte-indistinguishable from real champion rows except: (1) {@code correlation_id} carries the
 * server-set {@code drill:} prefix (always excludable / cleanable), and (2) {@code prediction} is
 * an inert {@code {}} (the feature-PSI lane reads only {@code features}; battedball has no live
 * prediction-PSI lane). The champion's identity (name / version / id / schema hash) and the SHIFT
 * baseline come straight from the registry + the champion's {@code metadata.json}, so the injector
 * self-targets and self-calibrates - no hardcoded ids or magic numbers.
 *
 * <p><b>Hygiene ([175], enforced).</b> The injector refuses to run unless {@code bullpen.drift.tag}
 * is set (the box exports {@code BULLPEN_DRIFT_TAG} for the drill window), so synthetic
 * prediction_log rows can never land while the paired {@code drift_metrics} tagging is disarmed.
 * The {@code drill:} correlation prefix is the prediction_log-side exclusion / cleanup key; the
 * {@code drift_metrics.tag} column (V027) is the metrics-side one.
 *
 * <p>Gated {@code @ConditionalOnProperty(bullpen.drift.inject.enabled)} - the bean does not exist
 * in normal operation (disabled by default, same discipline as the retrain timer): the box flips it
 * on only for the drill window, then off.
 */
@Service
@Profile("api")
@ConditionalOnProperty(name = "bullpen.drift.inject.enabled", havingValue = "true")
public class DriftInjectionService {

  private static final Logger log = LoggerFactory.getLogger(DriftInjectionService.class);

  /** Server-set prediction_log.correlation_id prefix - the excludable / cleanable drill marker. */
  public static final String DRILL_CORRELATION_PREFIX = "drill:";

  /**
   * Default shifted feature - the drill's canonical 1-sigma launch-speed shift, camelCase per the
   * served request.
   */
  public static final String DEFAULT_SHIFT_FEATURE = "launchSpeedMph";

  private static final String CLEANUP_SQL =
      "ALTER TABLE prediction_log DELETE WHERE correlation_id LIKE 'drill:%'";
  private static final String COUNT_DRILL_SQL =
      "SELECT count() FROM prediction_log WHERE correlation_id LIKE 'drill:%'";

  private final RegistryRepository registry;
  private final TrainingDistributionLoader baselineLoader;
  private final PredictionLogWriter writer;
  private final JdbcTemplate clickhouse;
  private final String driftTag;
  private final ObjectMapper mapper = new ObjectMapper();

  public DriftInjectionService(
      RegistryRepository registry,
      TrainingDistributionLoader baselineLoader,
      PredictionLogWriter writer,
      @Qualifier("clickhouseDataSource") DataSource clickhouse,
      // The [175] hygiene guard: the injector refuses to run unless the box has armed
      // BULLPEN_DRIFT_TAG (so the drift_metrics side is tagged in lockstep). Read from the SAME
      // property DriftMetricsRepository tags with, so api + worker agree on the single box env.
      @Value("${bullpen.drift.tag:}") String driftTag) {
    this.registry = registry;
    this.baselineLoader = baselineLoader;
    this.writer = writer;
    this.clickhouse = new JdbcTemplate(clickhouse);
    this.driftTag = driftTag == null ? "" : driftTag;
  }

  /**
   * Inject {@code n} synthetic prediction_log rows for the {@code modelName} champion, all seven
   * features drawn from the champion's own training baseline EXCEPT {@code shiftFeature}, which is
   * drawn from {@code N(mean + shiftSigmas*std, std)} - a self-calibrating drift that shifts one
   * feature clear of the PSI-NOTICE threshold while the others stay quiet (the specific,
   * attributable drill signal). {@code request_at} is spread uniformly over {@code [now -
   * lookbackHours, now]} so the next PsiFeatureJob 24h window captures the whole batch.
   */
  public InjectionResult induce(
      String modelName, int n, double shiftSigmas, int lookbackHours, String shiftFeature) {
    if (driftTag.isBlank()) {
      throw new DriftInjectionException(
          "refusing to inject: bullpen.drift.tag is empty. Set BULLPEN_DRIFT_TAG (e.g."
              + " induced-drill-2026-07) on the box + restart so the drift_metrics side is tagged in"
              + " lockstep ([175] hygiene), then retry.");
    }
    if (n < 1 || n > 200_000) {
      throw new DriftInjectionException("n must be in [1, 200000]; got " + n);
    }
    if (lookbackHours < 1 || lookbackHours > 48) {
      throw new DriftInjectionException("lookbackHours must be in [1, 48]; got " + lookbackHours);
    }
    // Symmetric with the DTO bound - guards direct service calls (the IT / a future caller) that
    // bypass bean validation, so a 0 (no-op) or NaN shift can never slip through.
    if (!(shiftSigmas >= 0.1 && shiftSigmas <= 10.0)) {
      throw new DriftInjectionException("shiftSigmas must be in [0.1, 10.0]; got " + shiftSigmas);
    }

    ModelVersion champ =
        registry.findActiveChampions().stream()
            .filter(m -> m.modelName().equals(modelName))
            .findFirst()
            .orElseThrow(
                () ->
                    new DriftInjectionException(
                        "no active CHAMPION for model '" + modelName + "' in the registry"));

    ReferenceDistributions refs = baselineLoader.load(champ.id(), Path.of(champ.metadataPath()));
    if (refs.isEmpty()) {
      throw new DriftInjectionException(
          "champion "
              + champ.naturalKey()
              + " (id="
              + champ.id()
              + ") has no feature_distributions baseline in its metadata.json - run"
              + " scripts/backfill_training_distributions.py --model "
              + modelName
              + " first (the drill needs a baseline to shift against + compare PSI against).");
    }
    double[] baseSample = refs.continuous().get(shiftFeature);
    if (baseSample == null || baseSample.length == 0) {
      throw new DriftInjectionException(
          "shift feature '"
              + shiftFeature
              + "' is not a continuous baseline feature; available continuous features: "
              + refs.continuous().keySet());
    }
    double mean = mean(baseSample);
    double std = std(baseSample, mean);
    double shiftedMean = mean + shiftSigmas * std;

    // Deterministic (seeded) so a re-run is reproducible; the row identity (UUIDs) still varies.
    Random rng = new Random(42);
    Instant now = Instant.now();
    Instant windowStart = now.minus(lookbackHours, ChronoUnit.HOURS);
    long spanMillis = now.toEpochMilli() - windowStart.toEpochMilli();

    List<PredictionLogEvent> batch = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Map<String, Object> features = sampleBaselineFeatures(refs, rng);
      // Override the one shifted feature: a fresh Gaussian around the shifted mean.
      features.put(shiftFeature, round3(shiftedMean + rng.nextGaussian() * std));
      Instant requestAt = now.minusMillis((long) (rng.nextDouble() * spanMillis));
      batch.add(
          new PredictionLogEvent(
              UUID.randomUUID(),
              requestAt,
              champ.modelName(),
              champ.version(),
              champ.id(),
              PredictionLogEvent.Role.CHAMPION,
              champ.featureSchemaHash(),
              serialize(features),
              "{}", // inert: battedball has no live prediction-PSI / calibration lane (see javadoc)
              0.0f,
              DRILL_CORRELATION_PREFIX + UUID.randomUUID()));
    }
    try {
      writer.writeBatch(batch);
    } catch (SQLException e) {
      throw new DriftInjectionException("prediction_log write failed: " + rootMessage(e), e);
    }

    log.warn(
        "DRIFT INJECTION: wrote {} synthetic prediction_log rows for {} (id={}) - shifted {} by"
            + " {} sigma (baseline mean={} std={} -> shifted mean={}), request_at in [{}, {}],"
            + " tag='{}',"
            + " correlation prefix='{}'. This is a DRILL ([175]); clean up with the cleanup endpoint"
            + " after the postmortem.",
        n,
        champ.naturalKey(),
        champ.id(),
        shiftFeature,
        shiftSigmas,
        round3(mean),
        round3(std),
        round3(shiftedMean),
        windowStart,
        now,
        driftTag,
        DRILL_CORRELATION_PREFIX);

    return new InjectionResult(
        champ.modelName(),
        champ.version(),
        champ.id(),
        n,
        shiftFeature,
        round3(mean),
        round3(std),
        shiftSigmas,
        round3(shiftedMean),
        driftTag,
        windowStart,
        now);
  }

  /**
   * Cleanup: {@code ALTER TABLE prediction_log DELETE WHERE correlation_id LIKE 'drill:%'}. Returns
   * the count of drill rows present at call time (the mutation itself is async in ClickHouse and
   * settles within seconds). Idempotent - safe to call more than once.
   */
  public long cleanup() {
    Long present = clickhouse.queryForObject(COUNT_DRILL_SQL, Long.class);
    long count = present == null ? 0L : present;
    clickhouse.execute(CLEANUP_SQL);
    log.warn(
        "DRIFT INJECTION CLEANUP: issued DELETE for {} drill-tagged prediction_log row(s) (async"
            + " mutation; settles in seconds). drift_metrics drill rows are excluded separately via"
            + " the V027 tag column (WHERE tag = '').",
        count);
    return count;
  }

  // --- helpers ------------------------------------------------------------

  /**
   * Draw every baseline feature from its own reference: continuous features bootstrap-sample a
   * value from the baseline sample (so their PSI stays ~0), categorical features weighted-pick a
   * category from the baseline counts, emitted with the natural JSON type (an int-coded token as a
   * JSON number, a string token as a JSON string) so it round-trips through {@link
   * RealFeatureDistributionFetcher#normalizeCategoryToken}. Using the baseline's OWN feature keys
   * guarantees the injected {@code features} JSON keys match what the PSI job extracts.
   */
  private static Map<String, Object> sampleBaselineFeatures(
      ReferenceDistributions refs, Random rng) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, double[]> e : refs.continuous().entrySet()) {
      double[] sample = e.getValue();
      out.put(e.getKey(), round3(sample[rng.nextInt(sample.length)]));
    }
    for (Map.Entry<String, Map<String, Integer>> e : refs.categorical().entrySet()) {
      out.put(e.getKey(), pickCategory(e.getValue(), rng));
    }
    return out;
  }

  /** Weighted pick from categorical counts; int-coded tokens become JSON numbers, else strings. */
  private static Object pickCategory(Map<String, Integer> counts, Random rng) {
    long total = 0;
    for (int c : counts.values()) {
      total += c;
    }
    if (total <= 0) {
      // Degenerate baseline: fall back to the first key so the row still carries the feature.
      return asToken(counts.keySet().iterator().next());
    }
    long target = (long) (rng.nextDouble() * total);
    long cum = 0;
    for (Map.Entry<String, Integer> e : counts.entrySet()) {
      cum += e.getValue();
      if (target < cum) {
        return asToken(e.getKey());
      }
    }
    return asToken(counts.keySet().iterator().next());
  }

  /**
   * An int-coded categorical key (baseState "0-7", outs "0-2") becomes a JSON number; else a
   * string.
   */
  private static Object asToken(String key) {
    try {
      return Integer.valueOf(key);
    } catch (NumberFormatException notAnInt) {
      return key;
    }
  }

  private String serialize(Map<String, Object> features) {
    try {
      return mapper.writeValueAsString(features);
    } catch (JsonProcessingException e) {
      // Only String/Number values are put into the map, so this is unreachable in practice. It is
      // an internal invariant break, not caller-fixable input, so it stays a 500 (IllegalState) -
      // NOT a DriftInjectionException, which the controller would mislabel as a 400.
      throw new IllegalStateException("features serialization failed: " + e.getMessage(), e);
    }
  }

  private static double mean(double[] xs) {
    double sum = 0.0;
    for (double x : xs) {
      sum += x;
    }
    return sum / xs.length;
  }

  private static double std(double[] xs, double mean) {
    double ss = 0.0;
    for (double x : xs) {
      double d = x - mean;
      ss += d * d;
    }
    double variance = ss / xs.length;
    double sd = Math.sqrt(variance);
    // A degenerate (zero-variance) baseline would make the shift a no-op; fall back to a small
    // positive std so the drill still produces a detectable shift.
    return sd < 1e-9 ? 1.0 : sd;
  }

  private static double round3(double v) {
    return Math.round(v * 1000.0) / 1000.0;
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
  }

  /** Result summary echoed to the operator (and asserted in tests). */
  public record InjectionResult(
      String modelName,
      String modelVersion,
      long modelVersionId,
      int rowsWritten,
      String shiftFeature,
      double baselineMean,
      double baselineStd,
      double shiftSigmas,
      double shiftedMean,
      String tag,
      Instant windowStart,
      Instant windowEnd) {}

  /** Caller-fixable injection failure - the controller maps it to 400. */
  public static final class DriftInjectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DriftInjectionException(String message) {
      super(message);
    }

    public DriftInjectionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
