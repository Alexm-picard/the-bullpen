package net.thebullpen.baseball.drift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.drift.TruthJoinedPredictionFetcher.TruthJoinedRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-ClickHouse round-trip for {@link ClickHouseSegmentedTruthJoinedPredictionFetcher} (issue
 * #60). Confirms the {@code @Primary} wiring supersedes the stub; that each of the six segment
 * dimensions buckets correctly - {@code park_id} / {@code stand} / {@code count_state} / {@code
 * inning_bucket} from the prediction-time {@code features} JSON, {@code pitch_type} from truth,
 * {@code month} from {@code request_at}; that probs + truthClass round-trip exactly inside a
 * bucket; that every non-scorable row is dropped (unmatched truth, out-of-vocab, unparseable,
 * HTTP-path NULL-key, blank segment); that the version filter holds; and that an unknown dimension
 * fails loud. Docker-gated like {@link ClickHouseTruthJoinedPredictionFetcherIT}.
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
class ClickHouseSegmentedTruthJoinedPredictionFetcherIT {

  private static final String MODEL = "pitch_outcome_post";
  private static final long V = 2L;

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
                "bullpen-segjoin-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private SegmentedTruthJoinedPredictionFetcher fetcher;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS prediction_log");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  @Test
  void the_real_impl_supersedes_the_stub_when_clickhouse_is_enabled() {
    assertThat(fetcher).isInstanceOf(ClickHouseSegmentedTruthJoinedPredictionFetcher.class);
  }

  @Test
  void buckets_by_park_id_from_features_with_exact_roundtrip() {
    // Two NYY pitches, one BOS - all scorable.
    prediction(1, 1, 1, features("NYY", "R", 0, 0, 5), pitchJson(0.10, 0.10, 0.10, 0.20, 0.50));
    truth(1, 1, 1, "in_play", "FF");
    prediction(1, 1, 2, features("NYY", "L", 1, 1, 5), pitchJson(0.60, 0.10, 0.10, 0.10, 0.10));
    truth(1, 1, 2, "ball", "SL");
    prediction(1, 2, 1, features("BOS", "R", 2, 2, 7), pitchJson(0.10, 0.50, 0.10, 0.20, 0.10));
    truth(1, 2, 1, "called_strike", "FF");

    Map<String, List<TruthJoinedRow>> bySeg = fetch("park_id");

    assertThat(bySeg).containsOnlyKeys("NYY", "BOS");
    assertThat(bySeg.get("NYY")).hasSize(2);
    assertThat(bySeg.get("BOS")).hasSize(1);
    TruthJoinedRow inPlay =
        bySeg.get("NYY").stream().filter(r -> r.truthClass() == 4).findFirst().orElseThrow();
    // OUTCOME_CLASSES order: [ball, called_strike, swinging_strike, foul, in_play].
    assertThat(inPlay.probs())
        .containsExactly(new double[] {0.10, 0.10, 0.10, 0.20, 0.50}, within(1e-9));
  }

  @Test
  void buckets_by_pitch_type_from_truth_with_empty_relabelled_unknown() {
    prediction(2, 1, 1, features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(2, 1, 1, "ball", "FF");
    prediction(2, 1, 2, features("NYY", "R", 0, 1, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(2, 1, 2, "ball", "FF");
    prediction(2, 1, 3, features("NYY", "R", 0, 2, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(2, 1, 3, "ball", "SL");
    // Matched truth with empty pitch_type (the live feed had not set it yet) -> 'unknown' bucket.
    prediction(2, 1, 4, features("NYY", "R", 1, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(2, 1, 4, "ball", "");
    // Unmatched (no truth row) -> NULL pitch_type AND NULL truthClass -> dropped, no bucket.
    prediction(2, 1, 5, features("NYY", "R", 1, 1, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));

    Map<String, List<TruthJoinedRow>> bySeg = fetch("pitch_type");

    assertThat(bySeg).containsOnlyKeys("FF", "SL", "unknown");
    assertThat(bySeg.get("FF")).hasSize(2);
    assertThat(bySeg.get("SL")).hasSize(1);
    assertThat(bySeg.get("unknown")).hasSize(1);
  }

  @Test
  void buckets_by_count_state_and_inning_bucket_from_features() {
    prediction(3, 1, 1, features("NYY", "R", 1, 2, 2), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(3, 1, 1, "ball", "FF");
    prediction(3, 1, 2, features("NYY", "R", 1, 2, 5), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(3, 1, 2, "ball", "FF");
    prediction(3, 1, 3, features("NYY", "R", 3, 0, 8), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(3, 1, 3, "ball", "FF");

    // count_state: "1-2", "1-2", "3-0".
    Map<String, List<TruthJoinedRow>> byCount = fetch("count_state");
    assertThat(byCount).containsOnlyKeys("1-2", "3-0");
    assertThat(byCount.get("1-2")).hasSize(2);
    assertThat(byCount.get("3-0")).hasSize(1);

    // inning_bucket: inning 2 -> "1-3", 5 -> "4-6", 8 -> "7+".
    Map<String, List<TruthJoinedRow>> byInning = fetch("inning_bucket");
    assertThat(byInning).containsOnlyKeys("1-3", "4-6", "7+");
    assertThat(byInning.get("1-3")).hasSize(1);
    assertThat(byInning.get("4-6")).hasSize(1);
    assertThat(byInning.get("7+")).hasSize(1);
  }

  @Test
  void buckets_by_month_from_request_at() {
    Instant now = Instant.now();
    insertAt(V, 4, 1, 1, features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1), now);
    truth(4, 1, 1, "ball", "FF");
    insertAt(
        V,
        4,
        1,
        2,
        features("NYY", "R", 0, 0, 1),
        pitchJson(0.6, 0.1, 0.1, 0.1, 0.1),
        now.minus(45, ChronoUnit.DAYS));
    truth(4, 1, 2, "ball", "FF");

    Map<String, List<TruthJoinedRow>> bySeg =
        fetcher.fetchBySegment(MODEL, V, "month", now.minus(90, ChronoUnit.DAYS), now);

    assertThat(bySeg).hasSize(2); // two distinct YYYY-MM buckets
    assertThat(bySeg.values()).allSatisfy(rows -> assertThat(rows).hasSize(1));
  }

  @Test
  void drops_nonscorable_rows_for_a_features_dimension() {
    // scorable in NYY
    prediction(5, 1, 1, features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(5, 1, 1, "ball", "FF");
    // no truth -> dropped
    prediction(5, 1, 2, features("NYY", "R", 0, 0, 1), pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));
    // out-of-vocab truth -> dropped, never bucketed
    prediction(5, 1, 3, features("NYY", "R", 0, 0, 1), pitchJson(0.2, 0.2, 0.2, 0.2, 0.2));
    truth(5, 1, 3, "hit_by_pitch", "FF");
    // non-pitch payload -> unparseable -> dropped
    prediction(5, 1, 4, features("NYY", "R", 0, 0, 1), "{\"prob_hr\":0.4}");
    truth(5, 1, 4, "ball", "FF");
    // features JSON missing parkId -> blank segment -> dropped (no spurious "" bucket)
    prediction(
        5,
        1,
        5,
        "{\"batterStand\":\"R\",\"countBalls\":0,\"countStrikes\":0,\"inning\":1}",
        pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));
    truth(5, 1, 5, "ball", "FF");
    // HTTP-path NULL-key -> dropped (game_id IS NOT NULL)
    predictionNoKey(features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1));

    Map<String, List<TruthJoinedRow>> bySeg = fetch("park_id");

    assertThat(bySeg).containsOnlyKeys("NYY");
    assertThat(bySeg.get("NYY")).hasSize(1);
  }

  @Test
  void only_returns_the_requested_version() {
    prediction(6, 1, 1, features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1)); // V
    truth(6, 1, 1, "ball", "FF");
    insert(
        99L, 6, 1, 2, features("NYY", "R", 0, 0, 1), pitchJson(0.6, 0.1, 0.1, 0.1, 0.1)); // other
    truth(6, 1, 2, "ball", "FF");

    Map<String, List<TruthJoinedRow>> bySeg = fetch("park_id");

    assertThat(bySeg).containsOnlyKeys("NYY");
    assertThat(bySeg.get("NYY")).hasSize(1);
  }

  @Test
  void unknown_segment_dimension_fails_loud() {
    assertThatThrownBy(
            () ->
                fetcher.fetchBySegment(
                    MODEL,
                    V,
                    "pitcher_handedness",
                    Instant.now().minusSeconds(3600),
                    Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown segment dimension");
  }

  // --- seed helpers ----------------------------------------------------------

  private Map<String, List<TruthJoinedRow>> fetch(String dimension) {
    return fetcher.fetchBySegment(
        MODEL, V, dimension, Instant.now().minus(24, ChronoUnit.HOURS), Instant.now());
  }

  private void prediction(
      long gameId, int atBat, int pitch, String featuresJson, String predictionJson) {
    insert(V, gameId, atBat, pitch, featuresJson, predictionJson);
  }

  private void insert(
      long versionId,
      long gameId,
      int atBat,
      int pitch,
      String featuresJson,
      String predictionJson) {
    insertAt(
        versionId,
        gameId,
        atBat,
        pitch,
        featuresJson,
        predictionJson,
        Instant.now().minus(1, ChronoUnit.HOURS));
  }

  private void insertAt(
      long versionId,
      long gameId,
      int atBat,
      int pitch,
      String featuresJson,
      String predictionJson,
      Instant requestAt) {
    // role + every value bound (not inline): clickhouse-jdbc mishandles a literal interleaved among
    // ? placeholders in a VALUES list (it broke with 'shadow' mid-list).
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, feature_hash,"
            + "  features, prediction, game_id, at_bat_index, pitch_number)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        Timestamp.from(requestAt),
        MODEL,
        "v1",
        versionId,
        "shadow",
        "h",
        featuresJson,
        predictionJson,
        gameId,
        atBat,
        pitch);
  }

  private void predictionNoKey(String featuresJson, String predictionJson) {
    ch.update(
        "INSERT INTO prediction_log"
            + " (request_at, model_name, model_version, model_version_id, role, feature_hash,"
            + "  features, prediction)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
        MODEL,
        "v1",
        V,
        "shadow",
        "h",
        featuresJson,
        predictionJson);
  }

  private void truth(long gameId, int atBat, int pitch, String description, String pitchType) {
    ch.update(
        "INSERT INTO pitches_live"
            + " (game_id, at_bat_index, pitch_number, game_date, pitcher_id, batter_id,"
            + "  description, pitch_type, balls, strikes, outs, inning, home_score, away_score,"
            + "  home_team, away_team)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, 0, 0, 'HOM', 'AWY')",
        gameId,
        atBat,
        pitch,
        java.sql.Date.valueOf("2026-06-13"),
        100,
        200,
        description,
        pitchType);
  }

  private static String features(String parkId, String stand, int balls, int strikes, int inning) {
    return String.format(
        "{\"countBalls\":%d,\"countStrikes\":%d,\"inning\":%d,\"batterStand\":\"%s\","
            + "\"parkId\":\"%s\"}",
        balls, strikes, inning, stand, parkId);
  }

  private static String pitchJson(
      double ball, double calledStrike, double swStrike, double foul, double inPlay) {
    return String.format(
        "{\"probabilities\":{\"ball\":%s,\"called_strike\":%s,\"swinging_strike\":%s,"
            + "\"foul\":%s,\"in_play\":%s},\"winner\":\"ball\"}",
        ball, calledStrike, swStrike, foul, inPlay);
  }
}
