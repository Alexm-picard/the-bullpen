package net.thebullpen.baseball.registry.experiment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Real {@link PairedPredictionFetcher} (3c): the ClickHouse-joining impl the stub deferred. Pivots
 * one model's CHAMPION-version rows against its CHALLENGER/SHADOW-version rows in {@code
 * prediction_log} onto the live pitch key {@code (game_id, at_bat_index, pitch_number)}, LEFT JOINs
 * the {@code pitches_live} observed outcome, and returns the scorable paired predictions {@link
 * ExperimentService#evaluate} feeds {@link MetricsComputer}.
 *
 * <p>Join discipline (the hard-won lessons):
 *
 * <ul>
 *   <li>{@code SETTINGS join_use_nulls = 1} - a ClickHouse LEFT JOIN defaults a non-joined row to
 *       the column's TYPE DEFAULT, not NULL (the #25 catch). With it on, an unmatched truth row's
 *       {@code description} comes back NULL (not {@code ''}), so a truth-missing pair is
 *       distinguishable and dropped here, never silently scored.
 *   <li>{@code pitches_live FINAL} - it is a ReplacingMergeTree(ingested_at); FINAL collapses a
 *       re-ingested pitch to its latest row before the join (the dsla-gate ghost lesson).
 *   <li>{@code game_id IS NOT NULL} on both prediction sides - HTTP-path predictions carry NULL
 *       live keys by definition and cannot be paired to a live pitch; they are excluded.
 *   <li>Window bounds bind as epoch-millis longs reconstructed with {@code
 *       fromUnixTimestamp64Milli}, never as bound {@code java.sql.Timestamp}s - clickhouse-jdbc
 *       mishandles a Timestamp param in a DateTime64 comparison (the {@code
 *       RealPredictionDistributionFetcher} lesson).
 * </ul>
 *
 * <p>{@link Primary} + gated on {@code bullpen.clickhouse.enabled} so it supersedes {@link
 * StubPairedPredictionFetcher} exactly when ClickHouse is configured; the stub stays the dev/CI
 * default (and {@code @ConditionalOnProperty}, NOT {@code @ConditionalOnBean} - the bean-ordering
 * guard crash-looped the worker for ~4 days, per PitcherFormRepository).
 *
 * <p>Leakage-clean: a read of already-logged predictions joined to already-observed truth inside an
 * explicit {@code [since, until)} window ({@code until = now - 24h} upstream, so truth is settled).
 */
@Component
@Primary
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class ClickHousePairedPredictionFetcher implements PairedPredictionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(ClickHousePairedPredictionFetcher.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Canonical 5-class pitch-outcome order (decision [34]; mirrors {@link
   * net.thebullpen.baseball.simulation.PitchOutcome} and the training {@code LABEL_CLASSES}).
   * {@code championProbs[i]} / {@code challengerProbs[i]} are this class {@code i}; {@code
   * truthClass} is the index of the observed {@code pitches_live.description} in this list.
   * Out-of-vocabulary descriptions ({@code hit_by_pitch} / {@code unknown}) and unmatched-NULL
   * truth resolve to index {@code -1}, and the pair is dropped (cannot be scored).
   */
  static final List<String> OUTCOME_CLASSES =
      List.of("ball", "called_strike", "swinging_strike", "foul", "in_play");

  // prediction_log is a plain MergeTree that ACCUMULATES rows: a worker restart re-runs the poll
  // and
  // re-logs the same (game_id, at_bat_index, pitch_number) for a version, so a raw read
  // double-counts
  // a pitch and biases the paired Brier/ECE the promotion gate reads. Collapse to one row per pitch
  // via argMax(prediction, request_at) GROUP BY the pitch key - the exact idiom the display path
  // (PredictionLogRepository.SELECT_TRUTH_JOIN) uses. model_name / model_version_id / role stay
  // WHERE
  // filters (each subquery is already scoped to one version + one role-set), NOT group keys:
  // grouping
  // the challenger side by role would keep a shadow->challenger transition of the SAME pitch as two
  // rows and re-introduce the double-count, so the pitch key alone is the correct dedup key and the
  // latest request_at wins.
  private static final String SELECT_PAIRS =
      "SELECT c.prediction AS champion_prediction,"
          + "       h.prediction AS challenger_prediction,"
          + "       t.description AS truth_description"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number, argMax(prediction, request_at) AS"
          + "          prediction"
          + "   FROM prediction_log"
          + "   WHERE model_name = ? AND model_version_id = ? AND role = 'champion'"
          + "     AND game_id IS NOT NULL"
          + "     AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "     AND request_at <  fromUnixTimestamp64Milli(?, 'UTC')"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS c"
          + " INNER JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number, argMax(prediction, request_at) AS"
          + "          prediction"
          + "   FROM prediction_log"
          + "   WHERE model_name = ? AND model_version_id = ? AND role IN ('challenger', 'shadow')"
          + "     AND game_id IS NOT NULL"
          + "     AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "     AND request_at <  fromUnixTimestamp64Milli(?, 'UTC')"
          + "   GROUP BY game_id, at_bat_index, pitch_number"
          + " ) AS h"
          + "   ON c.game_id = h.game_id"
          + "      AND c.at_bat_index = h.at_bat_index"
          + "      AND c.pitch_number = h.pitch_number"
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number, description"
          + "   FROM pitches_live FINAL"
          + " ) AS t"
          + "   ON c.game_id = t.game_id"
          + "      AND c.at_bat_index = t.at_bat_index"
          + "      AND c.pitch_number = t.pitch_number"
          + " SETTINGS join_use_nulls = 1";

  private final JdbcTemplate jdbc;

  public ClickHousePairedPredictionFetcher(
      @Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  @Override
  public List<PairedPrediction> fetch(
      String modelName,
      String championVersion,
      String challengerVersion,
      Instant since,
      Instant until) {
    // ExperimentService.fetchPairs passes the registry version_ids stringified; prediction_log keys
    // versions by the numeric model_version_id, so parse them back. A non-numeric value means the
    // caller handed a version string we cannot resolve - return empty (the stub's fail-closed
    // shape)
    // rather than guess.
    final long championVersionId;
    final long challengerVersionId;
    try {
      championVersionId = Long.parseLong(championVersion);
      challengerVersionId = Long.parseLong(challengerVersion);
    } catch (NumberFormatException e) {
      log.warn(
          "paired fetch: non-numeric version id(s) for {} (champ={} chall={}); returning empty",
          modelName,
          championVersion,
          challengerVersion);
      return List.of();
    }

    long sinceMs = since.toEpochMilli();
    long untilMs = until.toEpochMilli();
    List<JoinedRow> rows =
        jdbc.query(
            SELECT_PAIRS,
            (rs, n) ->
                new JoinedRow(
                    rs.getString("champion_prediction"),
                    rs.getString("challenger_prediction"),
                    rs.getString("truth_description")),
            modelName,
            championVersionId,
            sinceMs,
            untilMs,
            modelName,
            challengerVersionId,
            sinceMs,
            untilMs);

    List<PairedPrediction> pairs = new ArrayList<>(rows.size());
    int droppedNoTruth = 0;
    int droppedOutOfVocab = 0;
    int droppedUnparseable = 0;
    long pairSeq = 0;
    for (JoinedRow r : rows) {
      // NULL truth = no pitches_live row joined (join_use_nulls=1 makes this NULL, not ''); the
      // pair has no settled ground truth and is excluded. Tracked separately from out-of-vocab so a
      // regression that dropped join_use_nulls (truth would arrive as '') is observable in the
      // drop-reason counts, not silently rebucketed.
      if (r.truthDescription() == null) {
        droppedNoTruth++;
        continue;
      }
      int truthClass = OUTCOME_CLASSES.indexOf(r.truthDescription());
      if (truthClass < 0) {
        droppedOutOfVocab++; // hit_by_pitch / unknown / any non-5-class description
        continue;
      }
      double[] champ = probsOf(r.championPrediction());
      double[] chall = probsOf(r.challengerPrediction());
      if (champ == null || chall == null) {
        droppedUnparseable++; // not a 5-class pitch payload (e.g. a batted-ball {"prob_hr":x})
        continue;
      }
      pairs.add(new PairedPrediction(pairSeq++, champ, chall, truthClass));
    }

    log.info(
        "paired fetch {}: {} scorable pair(s) over [{}, {}) (champ v{} vs chall v{}); dropped"
            + " no-truth={} out-of-vocab={} unparseable={}",
        modelName,
        pairs.size(),
        since,
        until,
        championVersionId,
        challengerVersionId,
        droppedNoTruth,
        droppedOutOfVocab,
        droppedUnparseable);
    return pairs;
  }

  /**
   * Ordered probability array in {@link #OUTCOME_CLASSES} order from a {@code {"probabilities":
   * {class -> p, ...}}} payload, or {@code null} if the JSON is unparseable, carries no {@code
   * probabilities} object, or is missing any of the 5 classes (i.e. not a pitch-outcome payload).
   */
  static double[] probsOf(String predictionJson) {
    if (predictionJson == null || predictionJson.isBlank()) {
      return null;
    }
    try {
      JsonNode probs = MAPPER.readTree(predictionJson).get("probabilities");
      if (probs == null || !probs.isObject()) {
        return null;
      }
      double[] out = new double[OUTCOME_CLASSES.size()];
      for (int i = 0; i < OUTCOME_CLASSES.size(); i++) {
        JsonNode p = probs.get(OUTCOME_CLASSES.get(i));
        if (p == null || !p.isNumber()) {
          return null; // a missing class means this is not a 5-class pitch-outcome distribution
        }
        out[i] = p.doubleValue();
      }
      return out;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /** One joined champion/challenger/truth row before parsing + validation. */
  private record JoinedRow(
      String championPrediction, String challengerPrediction, String truthDescription) {}
}
