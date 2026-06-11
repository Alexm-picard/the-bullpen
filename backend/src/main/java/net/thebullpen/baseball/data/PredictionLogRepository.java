package net.thebullpen.baseball.data;

import java.util.List;
import javax.sql.DataSource;
import net.thebullpen.baseball.api.dto.LatencyStat;
import net.thebullpen.baseball.api.dto.TruthJoinedPrediction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read-side aggregate for the ClickHouse {@code prediction_log} table.
 *
 * <p>The write path is {@link net.thebullpen.baseball.inference.PredictionLogWriter} (api profile,
 * bulk INSERT). This repository owns the analytical reads so SQL against {@code prediction_log}
 * stays encapsulated behind a typed API rather than leaking raw {@code JdbcTemplate} into jobs —
 * the reconciliation job ({@link net.thebullpen.baseball.registry.ReconciliationJob}) used to build
 * its own {@code JdbcTemplate} and inline the query.
 *
 * <p>{@code @ConditionalOnBean(clickhouseDataSource)} so dev profiles without ClickHouse still
 * boot.
 */
@Repository
@ConditionalOnBean(name = "clickhouseDataSource")
public class PredictionLogRepository {

  private static final String DISTINCT_SERVED_PAIRS =
      "SELECT DISTINCT model_name, model_version FROM prediction_log"
          + " WHERE request_at > now() - INTERVAL ? DAY";

  private static final String LATENCY_QUANTILES =
      "SELECT model_name, model_version, count() AS n,"
          + " quantile(0.5)(latency_ms)   AS p50,"
          + " quantile(0.95)(latency_ms)  AS p95,"
          + " quantile(0.99)(latency_ms)  AS p99,"
          + " quantile(0.999)(latency_ms) AS p999"
          + " FROM prediction_log"
          + " WHERE request_at > now() - INTERVAL ? DAY"
          + " GROUP BY model_name, model_version"
          + " ORDER BY model_name, model_version";

  /**
   * Truth-join (W3, issue #1): reconcile live-keyed prediction_log rows against their real pitch in
   * pitches_live on the V017 natural key (game_id, at_bat_index, pitch_number).
   *
   * <p>Only live predictions participate: {@code game_id IS NOT NULL} prunes the HTTP-path / shadow
   * rows whose key columns are NULL by construction (they correspond to no live pitch). decision
   * [143]'s predict-the-next-pitch re-logs the same upcoming key on every poll, so the prediction
   * side is collapsed to the latest by request_at (argMax) before the join - one prediction per
   * pitch, not one per poll. The pitches side reads FINAL so the ReplacingMergeTree's corrected
   * re-polls collapse to one row per key.
   *
   * <p>A LEFT JOIN keeps orphan predictions (no matching pitch). ClickHouse fills unmatched
   * LEFT-JOIN right-side columns with the column type's default (0 / empty string), NOT NULL, so
   * the query runs with {@code SETTINGS join_use_nulls = 1}; that makes {@code (pl.game_id IS NOT
   * NULL)} resolve to 0 for orphans ({@code matched = false}) and the truth columns come back NULL.
   * The calibration query ({@link #SELECT_CALIBRATION_SET}) inner-joins instead, excluding orphans
   * per the V017 contract.
   */
  private static final String SELECT_TRUTH_JOIN =
      "SELECT pred.game_id AS game_id, pred.at_bat_index AS at_bat_index,"
          + " pred.pitch_number AS pitch_number, pred.model_name AS model_name,"
          + " pred.model_version AS model_version, pred.prediction AS prediction,"
          + " (pl.game_id IS NOT NULL) AS matched,"
          + " pl.description AS actual_description, pl.pitch_type AS actual_pitch_type"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(model_name, request_at)  AS model_name,"
          + "          argMax(model_version, request_at) AS model_version,"
          + "          argMax(prediction, request_at)  AS prediction"
          + "   FROM prediction_log"
          + "   WHERE game_id = ? AND game_id IS NOT NULL AND role = 'champion'"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pred"
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number, description, pitch_type"
          + "   FROM pitches_live FINAL WHERE game_id = ?"
          + " ) AS pl"
          + " ON pl.game_id = pred.game_id AND pl.at_bat_index = pred.at_bat_index"
          + "    AND pl.pitch_number = pred.pitch_number"
          + " ORDER BY pred.at_bat_index ASC, pred.pitch_number ASC"
          + " SETTINGS join_use_nulls = 1";

  /**
   * The calibration set for a game: the truth-join restricted to MATCHED rows (an INNER JOIN), so
   * orphan predictions - a predicted pitch that never landed - are excluded from the empirical
   * frequency denominator (V017 contract). Same argMax-per-key collapse + FINAL pitches read as
   * {@link #SELECT_TRUTH_JOIN}.
   */
  private static final String SELECT_CALIBRATION_SET =
      "SELECT pred.game_id AS game_id, pred.at_bat_index AS at_bat_index,"
          + " pred.pitch_number AS pitch_number, pred.model_name AS model_name,"
          + " pred.model_version AS model_version, pred.prediction AS prediction,"
          + " 1 AS matched,"
          + " pl.description AS actual_description, pl.pitch_type AS actual_pitch_type"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number,"
          + "          argMax(model_name, request_at)  AS model_name,"
          + "          argMax(model_version, request_at) AS model_version,"
          + "          argMax(prediction, request_at)  AS prediction"
          + "   FROM prediction_log"
          + "   WHERE game_id = ? AND game_id IS NOT NULL AND role = 'champion'"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS pred"
          + " INNER JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number, description, pitch_type"
          + "   FROM pitches_live FINAL WHERE game_id = ?"
          + " ) AS pl"
          + " ON pl.game_id = pred.game_id AND pl.at_bat_index = pred.at_bat_index"
          + "    AND pl.pitch_number = pred.pitch_number"
          + " ORDER BY pred.at_bat_index ASC, pred.pitch_number ASC";

  private static final RowMapper<TruthJoinedPrediction> TRUTH_JOIN_MAPPER =
      (rs, n) ->
          new TruthJoinedPrediction(
              rs.getLong("game_id"),
              rs.getInt("at_bat_index"),
              rs.getInt("pitch_number"),
              rs.getString("model_name"),
              rs.getString("model_version"),
              rs.getString("prediction"),
              // ClickHouse returns the (pl.game_id IS NOT NULL) predicate as UInt8 0/1.
              rs.getInt("matched") != 0,
              emptyToNull(rs.getString("actual_description")),
              emptyToNull(rs.getString("actual_pitch_type")));

  private final JdbcTemplate jdbc;

  public PredictionLogRepository(@Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  /**
   * Reconcile every champion prediction logged for {@code gameId} against its real pitch (W3, issue
   * #1). Matched rows carry the realized outcome; orphan predictions come back with {@link
   * TruthJoinedPrediction#matched()} {@code = false} and null truth fields. Empty list for a game
   * with no live predictions.
   *
   * <p>Feeds the per-player history + reliability views the README flags as empty. Callers that
   * need the calibration set only should use {@link #findCalibrationSet(long)}, which excludes
   * orphans at the SQL level.
   */
  public List<TruthJoinedPrediction> reconcileGamePredictions(long gameId) {
    // Params: prediction-side game_id (prunes prediction_log), pitches-side game_id (prunes
    // pitches_live). Both bound to the same value.
    return jdbc.query(SELECT_TRUTH_JOIN, TRUTH_JOIN_MAPPER, gameId, gameId);
  }

  /**
   * The matched-only calibration set for {@code gameId}: predictions that have a real pitch in
   * pitches_live, orphans excluded (V017 contract). Every returned row has {@link
   * TruthJoinedPrediction#matched()} {@code = true} and non-null truth fields.
   */
  public List<TruthJoinedPrediction> findCalibrationSet(long gameId) {
    return jdbc.query(SELECT_CALIBRATION_SET, TRUTH_JOIN_MAPPER, gameId, gameId);
  }

  /** ClickHouse returns absent LowCardinality(String) values as empty strings, not SQL NULL. */
  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  /**
   * Every distinct {@code (model_name, model_version)} pair that produced a logged prediction
   * within the last {@code lookbackDays} days. Used by the weekly registry-reconciliation job to
   * find orphan ids in {@code prediction_log} that the registry doesn't know about.
   *
   * @return list of {@code [model_name, model_version]} pairs
   */
  public List<String[]> distinctServedModelVersions(int lookbackDays) {
    return jdbc.query(
        DISTINCT_SERVED_PAIRS,
        (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
        lookbackDays);
  }

  /**
   * Per-model serving-latency percentiles (p50 / p95 / p99, in ms) over the last {@code
   * lookbackDays} days, one row per {@code (model_name, model_version)} that logged a prediction.
   * Backs {@code GET /v1/ops/latency} — the Ops dashboard's fleet p99 column + Latency Detail
   * table. Empty list when no predictions fall in the window (the UI then shows its no-data state).
   */
  public List<LatencyStat> latencyQuantiles(int lookbackDays) {
    return jdbc.query(
        LATENCY_QUANTILES,
        (rs, n) ->
            new LatencyStat(
                rs.getString("model_name"),
                rs.getString("model_version"),
                rs.getLong("n"),
                finiteOrZero(rs.getDouble("p50")),
                finiteOrZero(rs.getDouble("p95")),
                finiteOrZero(rs.getDouble("p99")),
                finiteOrZero(rs.getDouble("p999"))),
        lookbackDays);
  }

  /**
   * L7 hardening: a ClickHouse quantile over a degenerate group can return NaN, and Jackson
   * serializes NaN/Infinity as bare tokens that are invalid JSON - the ops page's JSON.parse then
   * fails on the whole payload. Groups always have >= 1 row so this should not occur; if it does,
   * 0.0 renders as a visible zero rather than breaking the dashboard.
   */
  private static double finiteOrZero(double v) {
    return Double.isFinite(v) ? v : 0.0;
  }
}
