package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.domain.RollingAccuracyBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-ClickHouse IT for the Live Scorecard truth join. The load-bearing case is the DUPLICATED ROW
 * fixture (the #341 class): {@code prediction_log} accumulates re-logs across worker restarts, and
 * the reported accuracy must be IDENTICAL before and after a simulated restart re-inserts every
 * prediction - a scorecard that moves on restarts instead of games is telemetry noise wearing a
 * percentage.
 *
 * <p>Also pinned: the SQL argmax agrees with the Java scorers' semantics on untied fixtures (the C1
 * single-implementation concern, bridged by test rather than by sharing code with SQL); orphan
 * predictions and out-of-vocabulary truths leave the denominator; the LATEST re-log wins the dedup
 * (a corrected prediction changes the score, proving which row is kept); the y7 fold reuses
 * CANONICAL_Y7 (a realized {@code ST} scores as predicted {@code SL}); an empty realized pitch_type
 * is excluded rather than folded to OFF.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles({"api", "registry-controller-it"})
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class RollingAccuracyRepositoryIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("bullpen.clickhouse.enabled", () -> "true");
    registry.add("bullpen.clickhouse.url", CH::getJdbcUrl);
    registry.add("bullpen.clickhouse.user", CH::getUsername);
    registry.add("bullpen.clickhouse.password", CH::getPassword);
    String sqliteUrl =
        "jdbc:sqlite:"
            + java.nio.file.Path.of(
                System.getProperty("java.io.tmpdir"),
                "bullpen-rollacc-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private RollingAccuracyRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS pitches_live");
      stmt.execute("TRUNCATE TABLE IF EXISTS prediction_log");
    }
  }

  // --- fixture helpers ----------------------------------------------------

  /**
   * A y5 prediction JSON in the SERVED shape: probabilities + the persisted winner the user was
   * shown (PitchPredictionService writes both; the y5 arm scores the persisted winner, never a
   * re-derived argmax - A3).
   */
  private static String y5Prediction(String winner) {
    StringBuilder sb = new StringBuilder("{\"probabilities\":{");
    String[] classes = {"ball", "called_strike", "swinging_strike", "foul", "in_play"};
    for (int i = 0; i < classes.length; i++) {
      double p = classes[i].equals(winner) ? 0.60 : 0.10;
      sb.append('"').append(classes[i]).append("\":").append(p);
      if (i < classes.length - 1) sb.append(',');
    }
    return sb.append("},\"winner\":\"").append(winner).append("\"}").toString();
  }

  /** A y7 probabilities JSON with a single UNTIED argmax at {@code winner}. */
  private static String y7Prediction(String winner) {
    StringBuilder sb = new StringBuilder("{\"probabilities\":{");
    String[] classes = {"FF", "SI", "FC", "SL", "CU", "CH", "OFF"};
    for (int i = 0; i < classes.length; i++) {
      double p = classes[i].equals(winner) ? 0.40 : 0.10;
      sb.append('"').append(classes[i]).append("\":").append(p);
      if (i < classes.length - 1) sb.append(',');
    }
    return sb.append("}}").toString();
  }

  private void insertPrediction(
      String modelName,
      long gameId,
      int abIndex,
      int pitchNumber,
      String prediction,
      int atOffsetSec)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                    + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                    + " game_id, at_bat_index, pitch_number) VALUES"
                    + " (generateUUIDv4(), now64(3) - ?, ?, 'v1', 'champion', 'h', '{}', ?, 1.0,"
                    + " 'cid', ?, ?, ?)")) {
      ps.setInt(1, atOffsetSec);
      ps.setString(2, modelName);
      ps.setString(3, prediction);
      ps.setLong(4, gameId);
      ps.setInt(5, abIndex);
      ps.setInt(6, pitchNumber);
      ps.execute();
    }
  }

  private void insertRealized(
      long gameId, int abIndex, int pitchNumber, String description, String pitchType)
      throws Exception {
    insertRealized(gameId, abIndex, pitchNumber, description, pitchType, 0);
  }

  private void insertRealized(
      long gameId,
      int abIndex,
      int pitchNumber,
      String description,
      String pitchType,
      int gameDateDaysAgo)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO pitches_live (game_id, at_bat_index, pitch_number, game_date,"
                    + " pitcher_id, batter_id, description, pitch_type, balls, strikes, outs,"
                    + " inning, home_score, away_score, home_team, away_team) VALUES"
                    + " (?, ?, ?, today() - ?, 1, 2, ?, ?, 0, 0, 0, 1, 0, 0, 'HOME', 'AWAY')")) {
      ps.setLong(1, gameId);
      ps.setInt(2, abIndex);
      ps.setInt(3, pitchNumber);
      ps.setInt(4, gameDateDaysAgo);
      ps.setString(5, description);
      ps.setString(6, pitchType);
      ps.execute();
    }
  }

  private static long n(List<RollingAccuracyBucket> buckets) {
    return buckets.stream().mapToLong(RollingAccuracyBucket::n).sum();
  }

  private static long hits(List<RollingAccuracyBucket> buckets) {
    return buckets.stream().mapToLong(RollingAccuracyBucket::hits).sum();
  }

  // --- the load-bearing case: restart-stable accuracy ----------------------

  @Test
  void accuracyIsStableAcrossASimulatedWorkerRestart() throws Exception {
    // Three realized pitches; the model got 2 of 3 right.
    insertRealized(100L, 1, 1, "ball", "FF");
    insertRealized(100L, 1, 2, "in_play", "SL");
    insertRealized(100L, 2, 1, "foul", "CH");
    insertPrediction("pitch_outcome_pre", 100L, 1, 1, y5Prediction("ball"), 60);
    insertPrediction("pitch_outcome_pre", 100L, 1, 2, y5Prediction("in_play"), 60);
    insertPrediction("pitch_outcome_pre", 100L, 2, 1, y5Prediction("swinging_strike"), 60);

    List<RollingAccuracyBucket> before = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(n(before)).isEqualTo(3);
    assertThat(hits(before)).isEqualTo(2);

    // Simulated restart: the poller re-logs every pitch (same predictions, later request_at).
    // prediction_log ACCUMULATES these; the LIMIT 1 BY dedup must keep the score identical.
    insertPrediction("pitch_outcome_pre", 100L, 1, 1, y5Prediction("ball"), 10);
    insertPrediction("pitch_outcome_pre", 100L, 1, 2, y5Prediction("in_play"), 10);
    insertPrediction("pitch_outcome_pre", 100L, 2, 1, y5Prediction("swinging_strike"), 10);

    List<RollingAccuracyBucket> after = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(n(after)).as("a restart must not double-count pitches").isEqualTo(3);
    assertThat(hits(after)).isEqualTo(2);
  }

  @Test
  void theLatestRelogWinsTheDedup_provenByAChangedPrediction() throws Exception {
    // Cardinality-stable is not enough (the P1 lesson: a pin must bite on WHICH row survives).
    // The re-log flips the prediction from wrong to right; if the dedup kept the OLD row the
    // score stays 0/1 and this reds.
    insertRealized(200L, 1, 1, "in_play", "FF");
    insertPrediction("pitch_outcome_pre", 200L, 1, 1, y5Prediction("ball"), 60);
    assertThat(hits(repo.pitchOutcomeDaily("pitch_outcome_pre", 7))).isZero();

    insertPrediction("pitch_outcome_pre", 200L, 1, 1, y5Prediction("in_play"), 5);
    List<RollingAccuracyBucket> after = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(n(after)).isEqualTo(1);
    assertThat(hits(after)).as("the LATEST re-log must be the scored row").isEqualTo(1);
  }

  // --- denominator honesty -------------------------------------------------

  @Test
  void orphansOutOfVocabAndUnparseableRowsLeaveTheDenominator() throws Exception {
    // 1 scorable hit...
    insertRealized(300L, 1, 1, "ball", "FF");
    insertPrediction("pitch_outcome_pre", 300L, 1, 1, y5Prediction("ball"), 60);
    // ...an ORPHAN prediction (pitch never landed - INNER JOIN excludes it)...
    insertPrediction("pitch_outcome_pre", 300L, 9, 9, y5Prediction("ball"), 60);
    // ...an out-of-vocabulary truth (feed oddity - excluded, not scored as a miss)...
    insertRealized(300L, 2, 1, "pitchout", "FF");
    insertPrediction("pitch_outcome_pre", 300L, 2, 1, y5Prediction("ball"), 60);
    // ...and an unparseable prediction JSON (excluded from n and hits alike).
    insertRealized(300L, 3, 1, "foul", "FF");
    insertPrediction("pitch_outcome_pre", 300L, 3, 1, "not-json", 60);

    List<RollingAccuracyBucket> buckets = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(n(buckets)).as("only the scorable row counts").isEqualTo(1);
    assertThat(hits(buckets)).isEqualTo(1);
  }

  @Test
  void modelsAreScoredIndependently() throws Exception {
    insertRealized(400L, 1, 1, "ball", "FF");
    insertPrediction("pitch_outcome_pre", 400L, 1, 1, y5Prediction("ball"), 60);
    insertPrediction("pitch_outcome_post", 400L, 1, 1, y5Prediction("foul"), 60);

    assertThat(hits(repo.pitchOutcomeDaily("pitch_outcome_pre", 7))).isEqualTo(1);
    assertThat(hits(repo.pitchOutcomeDaily("pitch_outcome_post", 7))).isZero();
    assertThat(n(repo.pitchOutcomeDaily("pitch_outcome_post", 7))).isEqualTo(1);
  }

  @Test
  void theModelAllowlistFailsLoud() {
    assertThatThrownBy(() -> repo.pitchOutcomeDaily("pitch_type_pre", 7))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bucketsKeyOnTheGameDate_soACrossMidnightRelogCannotMigrateThem() throws Exception {
    // Review A1, demonstrated against real CH: bucketing on the kept row's request_at let a
    // 23:58 -> 00:05 ET re-log MOVE a pitch between daily buckets (window total stable, so the
    // restart-stability pin alone could not see it). Buckets key on the truth side's game_date;
    // yesterday's game must stay in yesterday's bucket no matter when the poller re-logs.
    insertRealized(600L, 1, 1, "ball", "FF", 1); // game played YESTERDAY
    insertPrediction("pitch_outcome_pre", 600L, 1, 1, y5Prediction("ball"), 60);

    List<RollingAccuracyBucket> before = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(before).hasSize(1);
    java.time.LocalDate bucketDate = before.getFirst().date();

    // The "cross-midnight" re-log: same pitch, much later request_at (a different clock day in
    // the pathological case). The bucket date must NOT move.
    insertPrediction("pitch_outcome_pre", 600L, 1, 1, y5Prediction("ball"), 5);
    List<RollingAccuracyBucket> after = repo.pitchOutcomeDaily("pitch_outcome_pre", 7);
    assertThat(after).hasSize(1);
    assertThat(after.getFirst().date())
        .as("the game's day, not the re-log's")
        .isEqualTo(bucketDate);
    assertThat(n(after)).isEqualTo(1);
  }

  @Test
  void aPredictionWithoutAPersistedWinnerIsExcludedNotMiscounted() throws Exception {
    // The y5 arm scores the PERSISTED winner (the class the user was shown). A row carrying
    // probabilities but no winner field is unscorable - excluded from n and hits alike, never
    // re-derived.
    insertRealized(700L, 1, 1, "ball", "FF");
    insertPrediction("pitch_outcome_pre", 700L, 1, 1, "{\"probabilities\":{\"ball\":0.9}}", 60);

    assertThat(n(repo.pitchOutcomeDaily("pitch_outcome_pre", 7))).isZero();
  }

  // --- the y7 fold ---------------------------------------------------------

  @Test
  void pitchTypeTruthReusesTheCanonicalY7Fold() throws Exception {
    // Realized ST folds to SL (CANONICAL_Y7) - a predicted SL scores as a HIT. A second fold
    // implementation would be the C1 divergence class; this reds if the SQL stops reusing it.
    insertRealized(500L, 1, 1, "in_play", "ST");
    insertPrediction("pitch_type_pre", 500L, 1, 1, y7Prediction("SL"), 60);
    // Realized empty pitch_type is EXCLUDED, not folded to OFF (fabricated truth).
    insertRealized(500L, 2, 1, "ball", "");
    insertPrediction("pitch_type_pre", 500L, 2, 1, y7Prediction("OFF"), 60);

    List<RollingAccuracyBucket> buckets = repo.pitchTypeDaily(7);
    assertThat(n(buckets)).isEqualTo(1);
    assertThat(hits(buckets)).isEqualTo(1);
  }
}
