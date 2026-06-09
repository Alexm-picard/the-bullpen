package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.api.dto.TruthJoinedPrediction;
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
 * Real-ClickHouse round-trip for the W3 truth-join (issue #1): {@code prediction_log} LEFT JOINed
 * to {@code pitches_live} on the V017 natural key (game_id, at_bat_index, pitch_number).
 *
 * <p>Seeds a keyed live prediction with a matching pitch (the join must populate the truth
 * columns), plus an orphan prediction with no matching pitch (it stays in the full reconcile read
 * as unmatched, but is EXCLUDED from {@link PredictionLogRepository#findCalibrationSet} per the
 * V017 contract). Also seeds an HTTP-path / shadow row with NULL key columns to prove it never
 * leaks into either read.
 *
 * <p>Real ClickHouse (Testcontainers), per the testing posture - mocking the ClickHouse boundary is
 * exactly the divergence that hides query bugs (the LivePitchesRepository date-binding Code 43 bug
 * shipped behind a mocked repo). Docker-gated like {@link LivePitchesRepositoryIT}: on macOS Docker
 * Desktop returns malformed /info responses to Testcontainers, so this runs in CI with {@code
 * -Dbullpen.it.docker=true}. The query is written to that standard regardless.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class PredictionLogReconciliationIT {

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
                "bullpen-recon-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PredictionLogRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    // pitches_live (V015) + prediction_log (V004/V012/V017) are created by
    // ClickHouseMigrationRunner.
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE TABLE IF EXISTS pitches_live");
      stmt.execute("TRUNCATE TABLE IF EXISTS prediction_log");
    }
  }

  /** Insert a champion prediction keyed to a live pitch (game_id/at_bat_index/pitch_number). */
  private void insertLivePrediction(long gameId, int atBat, int pitch, String predictionJson)
      throws Exception {
    exec(
        String.format(
            "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                + " game_id, at_bat_index, pitch_number) VALUES (generateUUIDv4(), now64(3),"
                + " 'pitch_outcome_pre', 'v1', 'champion', 'h', '{}', '%s', 1.0, '', %d, %d, %d)",
            predictionJson, gameId, atBat, pitch));
  }

  /** Insert an HTTP-path / shadow prediction whose key columns are NULL (no live pitch). */
  private void insertNonLivePrediction(String predictionJson) throws Exception {
    exec(
        String.format(
            "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                + " game_id, at_bat_index, pitch_number) VALUES (generateUUIDv4(), now64(3),"
                + " 'pitch_outcome_pre', 'v1', 'champion', 'h', '{}', '%s', 1.0, '',"
                + " NULL, NULL, NULL)",
            predictionJson));
  }

  private void insertPitch(long gameId, int atBat, int pitch, String description, String pitchType)
      throws Exception {
    exec(
        String.format(
            "INSERT INTO pitches_live (game_id, at_bat_index, pitch_number, game_date,"
                + " pitcher_id, batter_id, description, pitch_type, balls, strikes, outs, inning,"
                + " home_score, away_score, home_team, away_team) VALUES"
                + " (%d, %d, %d, '2026-06-08', 1, 1, '%s', '%s', 0, 0, 0, 1, 0, 0, 'BOS', 'NYY')",
            gameId, atBat, pitch, description, pitchType));
  }

  private void exec(String sql) throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  private static TruthJoinedPrediction at(List<TruthJoinedPrediction> rows, int atBat, int pitch) {
    return rows.stream()
        .filter(r -> r.atBatIndex() == atBat && r.pitchNumber() == pitch)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void truth_join_populates_for_a_keyed_prediction_with_a_matching_pitch() throws Exception {
    long gameId = 101L;
    insertLivePrediction(
        gameId,
        1,
        1,
        "{\"probabilities\":{\"ball\":0.6,\"called_strike\":0.4},\"winner\":\"ball\"}");
    insertPitch(gameId, 1, 1, "called_strike", "FF");

    List<TruthJoinedPrediction> rows = repo.reconcileGamePredictions(gameId);

    assertThat(rows).hasSize(1);
    TruthJoinedPrediction joined = rows.get(0);
    assertThat(joined.gameId()).isEqualTo(gameId);
    assertThat(joined.atBatIndex()).isEqualTo(1);
    assertThat(joined.pitchNumber()).isEqualTo(1);
    assertThat(joined.modelName()).isEqualTo("pitch_outcome_pre");
    assertThat(joined.matched()).isTrue();
    assertThat(joined.actualDescription()).isEqualTo("called_strike");
    assertThat(joined.actualPitchType()).isEqualTo("FF");
    assertThat(joined.predictionJson()).contains("\"winner\":\"ball\"");
  }

  @Test
  void orphan_prediction_is_kept_in_reconcile_but_excluded_from_the_calibration_set()
      throws Exception {
    long gameId = 202L;
    // Pitch 1: predicted AND thrown -> matched, in the calibration set.
    insertLivePrediction(gameId, 1, 1, "{\"probabilities\":{\"ball\":0.7},\"winner\":\"ball\"}");
    insertPitch(gameId, 1, 1, "ball", "SL");
    // Pitch 2: predicted but NEVER thrown (orphan: intentional walk / pitch-clock auto-ball).
    insertLivePrediction(
        gameId, 1, 2, "{\"probabilities\":{\"in_play\":0.5},\"winner\":\"in_play\"}");
    // No pitches_live row for (202, 1, 2).

    List<TruthJoinedPrediction> reconciled = repo.reconcileGamePredictions(gameId);
    assertThat(reconciled).hasSize(2);
    assertThat(at(reconciled, 1, 1).matched()).isTrue();
    TruthJoinedPrediction orphan = at(reconciled, 1, 2);
    assertThat(orphan.matched()).as("the orphan stays in the full reconcile read").isFalse();
    assertThat(orphan.actualDescription()).isNull();
    assertThat(orphan.actualPitchType()).isNull();

    // The calibration set excludes the orphan per the V017 contract.
    List<TruthJoinedPrediction> calibration = repo.findCalibrationSet(gameId);
    assertThat(calibration).hasSize(1);
    assertThat(calibration.get(0).pitchNumber()).isEqualTo(1);
    assertThat(calibration).allMatch(TruthJoinedPrediction::matched);
  }

  @Test
  void non_live_predictions_with_null_keys_never_appear_in_either_read() throws Exception {
    long gameId = 303L;
    insertLivePrediction(gameId, 1, 1, "{\"probabilities\":{\"ball\":0.8},\"winner\":\"ball\"}");
    insertPitch(gameId, 1, 1, "ball", "CH");
    // An HTTP-path prediction with NULL key columns - must be pruned by game_id IS NOT NULL.
    insertNonLivePrediction("{\"probabilities\":{\"foul\":0.9},\"winner\":\"foul\"}");

    assertThat(repo.reconcileGamePredictions(gameId)).hasSize(1);
    assertThat(repo.findCalibrationSet(gameId)).hasSize(1);
  }

  @Test
  void re_predicted_pitch_collapses_to_the_latest_by_request_at() throws Exception {
    // decision [143]: predict-next re-logs the same key each poll. The truth-join must keep one row
    // per key (the latest by request_at), not one per poll.
    long gameId = 404L;
    insertLivePrediction(gameId, 1, 1, "{\"probabilities\":{\"ball\":0.9},\"winner\":\"ball\"}");
    Thread.sleep(5); // ensure a strictly later request_at
    insertLivePrediction(
        gameId, 1, 1, "{\"probabilities\":{\"in_play\":0.7},\"winner\":\"in_play\"}");
    insertPitch(gameId, 1, 1, "in_play", "FF");

    List<TruthJoinedPrediction> rows = repo.reconcileGamePredictions(gameId);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).predictionJson())
        .as("argMax(request_at) keeps the latest prediction for the key")
        .contains("\"winner\":\"in_play\"");
  }

  @Test
  void empty_for_a_game_with_no_live_predictions() throws Exception {
    assertThat(repo.reconcileGamePredictions(999L)).isEmpty();
    assertThat(repo.findCalibrationSet(999L)).isEmpty();
  }
}
