package net.thebullpen.baseball.drift;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Real {@link SegmentedTruthJoinedPredictionFetcher} (the segmented sibling the A2 follow-up filed
 * as issue #60): the per-segment-dimension version of {@link
 * ClickHouseTruthJoinedPredictionFetcher}. Un-stubs {@link
 * net.thebullpen.baseball.drift.jobs.WeeklySegmentJob} so the per-park / per-pitch-type / per-count
 * Brier breakdown stops returning {@code {}} and starts writing real {@code SEGMENT_BRIER} drift
 * rows the moment this deploys.
 *
 * <p>One aggregation query per dimension (per the interface contract), not a fan-out per segment
 * value: the truth join is computed once with a {@code segment_value} expression tagged onto each
 * row, then the rows are bucketed by that value in Java (Brier is a Java-side computation in {@code
 * WeeklySegmentJob}, so the grouping cannot be a SQL {@code GROUP BY}).
 *
 * <p>Join discipline is identical to {@link ClickHouseTruthJoinedPredictionFetcher} - {@code
 * SETTINGS join_use_nulls = 1} (unmatched truth NULL, not the {@code ''} type-default - the #25
 * lesson), {@code pitches_live FINAL} for the ReplacingMergeTree dedup, {@code game_id IS NOT NULL}
 * to drop HTTP-path predictions, window bounds bound as epoch-millis longs via {@code
 * fromUnixTimestamp64Milli} - and the 5-class vocabulary + prediction-JSON parse are reused
 * verbatim from that fetcher ({@code OUTCOME_CLASSES}, {@code probsOf}) so the locked outcome order
 * stays single-sourced across the package.
 *
 * <p>The {@code segment_value} expression is chosen per dimension from a fixed allow-list (the same
 * six {@code WeeklySegmentJob} requests); an unknown dimension is a fail-loud {@link
 * IllegalArgumentException}, never an injected fragment. {@code stand} / {@code park_id} / {@code
 * count_state} / {@code inning_bucket} read the prediction-time {@code features} JSON (the value
 * the model actually scored in); {@code pitch_type} is truth-side (only known post-pitch, and the
 * truth row is required for a {@code truthClass} anyway); {@code month} reads {@code request_at}.
 *
 * <p>{@link Primary} + gated on {@code bullpen.clickhouse.enabled} ({@code @ConditionalOnProperty},
 * never {@code @ConditionalOnBean} - the bean-ordering guard crash-looped the worker), so it
 * supersedes {@link StubSegmentedTruthJoinedPredictionFetcher} exactly when ClickHouse is
 * configured.
 */
@Component
@Primary
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class ClickHouseSegmentedTruthJoinedPredictionFetcher
    implements SegmentedTruthJoinedPredictionFetcher {

  private static final Logger log =
      LoggerFactory.getLogger(ClickHouseSegmentedTruthJoinedPredictionFetcher.class);

  /**
   * SQL expression that yields the segment value for each joined row, keyed by dimension. The keys
   * are exactly {@code WeeklySegmentJob.SEGMENT_DIMENSIONS}; the values reference the {@code p}
   * (prediction) and {@code t} (truth) aliases of {@link #selectFor}. Not user input - a fixed
   * allow-list - but kept as a map so an unrecognised dimension fails loud rather than silently
   * injecting.
   */
  private static final Map<String, String> SEGMENT_EXPR =
      Map.of(
          "stand", "JSONExtractString(p.features, 'batterStand')",
          "park_id", "JSONExtractString(p.features, 'parkId')",
          "count_state",
              "concat(toString(JSONExtractInt(p.features, 'countBalls')), '-',"
                  + " toString(JSONExtractInt(p.features, 'countStrikes')))",
          "inning_bucket",
              "multiIf(JSONExtractInt(p.features, 'inning') <= 3, '1-3',"
                  + " JSONExtractInt(p.features, 'inning') <= 6, '4-6', '7+')",
          "pitch_type", "if(t.pitch_type = '', 'unknown', t.pitch_type)",
          "month", "formatDateTime(p.request_at, '%Y-%m')");

  private final JdbcTemplate jdbc;

  public ClickHouseSegmentedTruthJoinedPredictionFetcher(
      @Qualifier("clickhouseDataSource") DataSource clickhouse) {
    this.jdbc = new JdbcTemplate(clickhouse);
  }

  @Override
  public Map<String, List<TruthJoinedRow>> fetchBySegment(
      String modelName,
      long modelVersionId,
      String segmentDimension,
      Instant windowStart,
      Instant windowEnd) {
    String segmentExpr = SEGMENT_EXPR.get(segmentDimension);
    if (segmentExpr == null) {
      throw new IllegalArgumentException(
          "unknown segment dimension '"
              + segmentDimension
              + "'; expected one of "
              + SEGMENT_EXPR.keySet());
    }

    List<SegmentedRow> rows =
        jdbc.query(
            selectFor(segmentExpr),
            (rs, n) ->
                new SegmentedRow(
                    rs.getString("segment_value"),
                    rs.getString("prediction"),
                    rs.getString("truth_description")),
            modelName,
            modelVersionId,
            windowStart.toEpochMilli(),
            windowEnd.toEpochMilli());

    Map<String, List<TruthJoinedRow>> bySegment = new LinkedHashMap<>();
    int droppedNoTruth = 0;
    int droppedOutOfVocab = 0;
    int droppedUnparseable = 0;
    int droppedNoSegment = 0;
    for (SegmentedRow r : rows) {
      // Scorability gates first, identical to the unsegmented fetcher: NULL truth = no settled
      // outcome (join_use_nulls=1 keeps it NULL); out-of-vocab truth is dropped, never bucketed
      // into a 5-class bin; a non-pitch / malformed prediction payload is unparseable.
      if (r.truthDescription() == null) {
        droppedNoTruth++;
        continue;
      }
      int truthClass =
          ClickHouseTruthJoinedPredictionFetcher.OUTCOME_CLASSES.indexOf(r.truthDescription());
      if (truthClass < 0) {
        droppedOutOfVocab++;
        continue;
      }
      double[] probs = ClickHouseTruthJoinedPredictionFetcher.probsOf(r.prediction());
      if (probs == null) {
        droppedUnparseable++;
        continue;
      }
      // A blank / NULL segment value (a features JSON missing the key, or pitch_type unmatched)
      // has no bucket to belong to - drop it rather than create a spurious "" segment.
      if (r.segmentValue() == null || r.segmentValue().isBlank()) {
        droppedNoSegment++;
        continue;
      }
      bySegment
          .computeIfAbsent(r.segmentValue(), k -> new java.util.ArrayList<>())
          .add(new TruthJoinedRow(probs, truthClass));
    }

    log.info(
        "segmented truth-joined fetch {} v{} dim={}: {} segment(s) over [{}, {}]; dropped"
            + " no-truth={} out-of-vocab={} unparseable={} no-segment={}",
        modelName,
        modelVersionId,
        segmentDimension,
        bySegment.size(),
        windowStart,
        windowEnd,
        droppedNoTruth,
        droppedOutOfVocab,
        droppedUnparseable,
        droppedNoSegment);
    return bySegment;
  }

  /**
   * The truth-join query with {@code segmentExpr} spliced into the SELECT list. {@code p} projects
   * {@code features} + {@code request_at} (the features-side / month segment sources) alongside the
   * key + prediction; {@code t} projects {@code pitch_type} (the truth-side segment source)
   * alongside the description. {@code segmentExpr} comes only from {@link #SEGMENT_EXPR} - never a
   * caller string - so the splice is not an injection surface.
   */
  private static String selectFor(String segmentExpr) {
    return "SELECT "
        + segmentExpr
        + " AS segment_value, p.prediction AS prediction, t.description AS truth_description"
        + " FROM ("
        + "   SELECT game_id, at_bat_index, pitch_number, prediction, features, request_at"
        + "   FROM prediction_log"
        + "   WHERE model_name = ? AND model_version_id = ?"
        + "     AND game_id IS NOT NULL"
        + "     AND request_at >= fromUnixTimestamp64Milli(?, 'UTC')"
        + "     AND request_at <= fromUnixTimestamp64Milli(?, 'UTC')"
        + " ) AS p"
        + " LEFT JOIN ("
        + "   SELECT game_id, at_bat_index, pitch_number, description, pitch_type"
        + "   FROM pitches_live FINAL"
        + " ) AS t"
        + "   ON p.game_id = t.game_id"
        + "      AND p.at_bat_index = t.at_bat_index"
        + "      AND p.pitch_number = t.pitch_number"
        + " SETTINGS join_use_nulls = 1";
  }

  /** One joined row tagged with its segment value, before scorability filtering + bucketing. */
  private record SegmentedRow(String segmentValue, String prediction, String truthDescription) {}
}
