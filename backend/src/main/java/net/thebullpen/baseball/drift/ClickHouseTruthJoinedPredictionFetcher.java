package net.thebullpen.baseball.drift;

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
 * Real {@link TruthJoinedPredictionFetcher} (3c follow-up): the ClickHouse-joining impl the stub
 * deferred. Joins one model version's {@code prediction_log} rows to the observed {@code
 * pitches_live} outcome on the live pitch key {@code (game_id, at_bat_index, pitch_number)} and
 * maps each to a {@code (probs, truthClass)} pair for {@link
 * net.thebullpen.baseball.drift.jobs.CalibrationJob}.
 *
 * <p>The job iterates the champion AND every SHADOW serving version, so the moment this lands and
 * deploys, a SHADOW model (e.g. {@code pitch_outcome_post}) gets calibration-vs-truth Brier + ECE
 * accruing nightly from its live shadow predictions - no separate wiring.
 *
 * <p>Join discipline is identical to {@link
 * net.thebullpen.baseball.registry.experiment.ClickHousePairedPredictionFetcher} (the paired
 * sibling), just without the champion/challenger pivot: {@code SETTINGS join_use_nulls = 1} so an
 * unmatched truth row is NULL not the {@code ''} type-default (the #25 lesson); {@code pitches_live
 * FINAL} for the ReplacingMergeTree dedup; {@code game_id IS NOT NULL} to exclude HTTP-path
 * predictions; window bounds bound as epoch-millis longs via {@code fromUnixTimestamp64Milli}
 * (clickhouse-jdbc mishandles bound DateTime64 Timestamps).
 *
 * <p>{@link Primary} + gated on {@code bullpen.clickhouse.enabled} ({@code @ConditionalOnProperty},
 * never {@code @ConditionalOnBean} - the bean-ordering guard crash-looped the worker), so it
 * supersedes {@link StubTruthJoinedPredictionFetcher} exactly when ClickHouse is configured.
 */
@Component
@Primary
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class ClickHouseTruthJoinedPredictionFetcher implements TruthJoinedPredictionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(ClickHouseTruthJoinedPredictionFetcher.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Canonical 5-class pitch-outcome order (decision [34]) - mirrors {@code
   * ClickHousePairedPredictionFetcher.OUTCOME_CLASSES} and {@code PitchOutcome} / training {@code
   * LABEL_CLASSES}. The locked vocabulary; {@code probs[i]} is class {@code i} and {@code
   * truthClass} is the observed {@code description}'s index here.
   */
  static final List<String> OUTCOME_CLASSES =
      List.of("ball", "called_strike", "swinging_strike", "foul", "in_play");

  private static final String SELECT_TRUTH_JOINED =
      "SELECT p.prediction AS prediction, t.description AS truth_description"
          + " FROM ("
          + "   SELECT game_id, at_bat_index, pitch_number, prediction"
          + "   FROM prediction_log"
          + "   WHERE model_name = ? AND model_version_id = ?"
          + "     AND game_id IS NOT NULL"
          + "     AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
          + "     AND request_at <= fromUnixTimestamp64Milli(?, 'UTC')"
          + " ) AS p"
          + " LEFT JOIN ("
          + "   SELECT game_id, at_bat_index, pitch_number, description"
          + "   FROM pitches_live FINAL"
          + " ) AS t"
          + "   ON p.game_id = t.game_id"
          + "      AND p.at_bat_index = t.at_bat_index"
          + "      AND p.pitch_number = t.pitch_number"
          + " SETTINGS join_use_nulls = 1";

  private final JdbcTemplate jdbc;

  public ClickHouseTruthJoinedPredictionFetcher(
      @Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  @Override
  public List<TruthJoinedRow> fetch(
      String modelName, long modelVersionId, Instant windowStart, Instant windowEnd) {
    List<JoinedRow> rows =
        jdbc.query(
            SELECT_TRUTH_JOINED,
            (rs, n) -> new JoinedRow(rs.getString("prediction"), rs.getString("truth_description")),
            modelName,
            modelVersionId,
            windowStart.toEpochMilli(),
            windowEnd.toEpochMilli());

    List<TruthJoinedRow> joined = new ArrayList<>(rows.size());
    int droppedNoTruth = 0;
    int droppedOutOfVocab = 0;
    int droppedUnparseable = 0;
    for (JoinedRow r : rows) {
      // NULL truth = no pitches_live row (join_use_nulls=1 makes it NULL, not ''); excluded - no
      // settled outcome to calibrate against. Out-of-vocab (hit_by_pitch / unknown) is NOT silently
      // bucketed into a 5-class bin: it has no truthClass, so it is dropped, tracked separately.
      if (r.truthDescription() == null) {
        droppedNoTruth++;
        continue;
      }
      int truthClass = OUTCOME_CLASSES.indexOf(r.truthDescription());
      if (truthClass < 0) {
        droppedOutOfVocab++;
        continue;
      }
      double[] probs = probsOf(r.prediction());
      if (probs == null) {
        droppedUnparseable++;
        continue;
      }
      joined.add(new TruthJoinedRow(probs, truthClass));
    }

    log.info(
        "truth-joined fetch {} v{}: {} row(s) over [{}, {}]; dropped no-truth={} out-of-vocab={}"
            + " unparseable={}",
        modelName,
        modelVersionId,
        joined.size(),
        windowStart,
        windowEnd,
        droppedNoTruth,
        droppedOutOfVocab,
        droppedUnparseable);
    return joined;
  }

  /**
   * Ordered probability array in {@link #OUTCOME_CLASSES} order from a {@code {"probabilities":
   * {class -> p}}} payload, or {@code null} if unparseable / missing the probabilities object /
   * missing any of the 5 classes. Mirrors {@code ClickHousePairedPredictionFetcher.probsOf} (the
   * locked 5-class vocab is shared; the two fetchers live in separate packages).
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
          return null;
        }
        out[i] = p.doubleValue();
      }
      return out;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /** One joined prediction/truth row before parsing + validation. */
  private record JoinedRow(String prediction, String truthDescription) {}
}
