package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Real {@link FeatureDistributionFetcher}: extracts one feature's observed distribution out of the
 * {@code prediction_log.features} JSON column for one model version over a time window, so {@link
 * net.thebullpen.baseball.drift.jobs.PsiFeatureJob} can compute per-feature PSI / chi-squared
 * against the training-time reference. This closes the per-feature drift lane that the stub left
 * dead end-to-end (stub returned empty, the job skipped every feature, {@code drift_metrics} got
 * zero PSI_FEATURE rows, and the evaluator's feature-drift NOTICE could never fire).
 *
 * <p>Parsing happens in SQL ({@code JSONExtractFloat} / {@code JSONExtractRaw}), exactly as the
 * seam's contract promised (see {@link FeatureDistributionFetcher}). Type discipline is enforced
 * server-side with {@code JSONType}: continuous fetches accept only numeric values (a missing key
 * or a JSON null must NOT become a fake 0.0 sample), and categorical fetches skip null / array /
 * object values. Categorical tokens come back as raw JSON ({@code "L"} with quotes for strings,
 * {@code 7} bare for numbers) and are normalized in {@link #normalizeCategoryToken(String)} so
 * int-coded categoricals compare against the training counts' string keys.
 *
 * <p>Window bounds are bound as epoch-millis longs and reconstructed server-side with {@code
 * fromUnixTimestamp64Milli}, NOT as bound java.sql.Timestamps - clickhouse-jdbc mishandles a
 * Timestamp param in a DateTime64 WHERE comparison (same workaround as {@link
 * RealPredictionDistributionFetcher}).
 *
 * <p>Gated on {@code bullpen.clickhouse.enabled} (same property as the datasource, deterministic
 * wiring) and {@link Primary} so it supersedes {@link StubFeatureDistributionFetcher} whenever
 * ClickHouse is configured; the stub remains the no-ClickHouse fallback.
 *
 * <p>Leakage-clean: a read of already-logged features inside an explicit {@code [windowStart,
 * windowEnd]} request_at window. No future data, no per-pitch cutoff surface.
 */
@Component
@Primary
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class RealFeatureDistributionFetcher implements FeatureDistributionFetcher {

  private static final Logger log = LoggerFactory.getLogger(RealFeatureDistributionFetcher.class);

  private static final String SELECT_CONTINUOUS =
      "SELECT JSONExtractFloat(features, ?) FROM prediction_log"
          + " WHERE model_name = ? AND model_version_id = ?"
          + "   AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "   AND request_at <= fromUnixTimestamp64Milli(?, 'UTC')"
          // Numeric-only: JSONType of a missing key or non-numeric value falls outside this set,
          // so absent features and JSON nulls can never surface as fake 0.0 samples.
          + "   AND JSONType(features, ?) IN ('Int64', 'UInt64', 'Double')";

  private static final String SELECT_CATEGORICAL =
      "SELECT JSONExtractRaw(features, ?) AS v, toInt32(count()) AS c FROM prediction_log"
          + " WHERE model_name = ? AND model_version_id = ?"
          + "   AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "   AND request_at <= fromUnixTimestamp64Milli(?, 'UTC')"
          + "   AND JSONHas(features, ?)"
          + "   AND JSONType(features, ?) NOT IN ('Null', 'Array', 'Object')"
          + " GROUP BY v";

  private final JdbcTemplate jdbc;

  public RealFeatureDistributionFetcher(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  @Override
  public List<Double> fetchContinuous(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd) {
    List<Double> sample =
        jdbc.query(
            SELECT_CONTINUOUS,
            (rs, n) -> rs.getDouble(1),
            featureName,
            modelName,
            modelVersionId,
            windowStart.toEpochMilli(),
            windowEnd.toEpochMilli(),
            featureName);
    log.debug(
        "FeatureDistributionFetcher (continuous): {}/{} feature={} window=[{}, {}] -> {} sample(s)",
        modelName,
        modelVersionId,
        featureName,
        windowStart,
        windowEnd,
        sample.size());
    return sample;
  }

  @Override
  public Map<String, Integer> fetchCategorical(
      String modelName,
      long modelVersionId,
      String featureName,
      Instant windowStart,
      Instant windowEnd) {
    // Insertion-ordered so the category order is stable for logging / tests.
    Map<String, Integer> counts = new LinkedHashMap<>();
    jdbc.query(
        SELECT_CATEGORICAL,
        rs -> {
          String category = normalizeCategoryToken(rs.getString(1));
          if (category != null) {
            counts.merge(category, rs.getInt(2), Integer::sum);
          }
        },
        featureName,
        modelName,
        modelVersionId,
        windowStart.toEpochMilli(),
        windowEnd.toEpochMilli(),
        featureName,
        featureName);
    log.debug(
        "FeatureDistributionFetcher (categorical): {}/{} feature={} window=[{}, {}] ->"
            + " {} category(ies)",
        modelName,
        modelVersionId,
        featureName,
        windowStart,
        windowEnd,
        counts.size());
    return counts;
  }

  /**
   * Normalizes a {@code JSONExtractRaw} token to a category key: strips the surrounding quotes from
   * a JSON string ({@code "L"} -> {@code L}), passes bare numeric tokens through unchanged ({@code
   * 7} -> {@code 7}, matching the string keys training-side counts use for int-coded categoricals),
   * and returns null for anything empty or a literal JSON null (defense in depth behind the
   * SQL-side type filter). Category values in this domain (park codes, handedness, pitch types, int
   * codes) carry no embedded quotes, so plain quote-stripping is sufficient.
   */
  static String normalizeCategoryToken(String raw) {
    if (raw == null || raw.isEmpty() || "null".equals(raw)) {
      return null;
    }
    if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
      String unquoted = raw.substring(1, raw.length() - 1);
      return unquoted.isEmpty() ? null : unquoted;
    }
    return raw;
  }
}
