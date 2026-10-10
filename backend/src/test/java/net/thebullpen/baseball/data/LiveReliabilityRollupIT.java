package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.domain.ReliabilityBinRow;
import net.thebullpen.baseball.domain.ReliabilityWindow;
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
 * Real-ClickHouse IT for the {@code live_reliability_daily} rollup (V035) write + read paths.
 *
 * <p>Mutation ledger:
 *
 * <ul>
 *   <li>drop the zero grid (INSERT only the aggregate rows): {@code
 *       aTruthCorrectionThatEmptiesABinLeavesNoStaleCount} and {@code aRelogThatMovesBinsLeaves
 *       NoStaleCount} red (the stale bin-6 row survives FINAL).
 *   <li>filter {@code n > 0} inside the FINAL subquery instead of outside: NOTHING reds on 24.12
 *       (verified) - the server applies a non-key WHERE after the FINAL merge. The outer filter is
 *       defence in depth against a PREWHERE-under-FINAL regression, not a fix for a live bug.
 *   <li>read {@code game_date <= today}: {@code theReadIsThroughYesterday} reds.
 *   <li>drop the days-of-play fallback: {@code anEmptyCalendarWindowFallsBackToTheLastDaysOfPlay}.
 * </ul>
 *
 * <p>Dates are computed server-side in ET ({@code toDate(now('America/New_York')) - k}) and
 * round-tripped; never a Java-side date (the TODAY_ET trap documented in {@code
 * RollingAccuracyRepositoryIT}).
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
class LiveReliabilityRollupIT {

  private static final String PRE = "pitch_outcome_pre";

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
                "bullpen-reliability-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private LiveReliabilityRepository rollup;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private javax.sql.DataSource clickhouseDs;

  @BeforeEach
  void wipe() throws Exception {
    exec("TRUNCATE TABLE IF EXISTS pitches_live");
    exec("TRUNCATE TABLE IF EXISTS prediction_log");
    exec("TRUNCATE TABLE IF EXISTS live_reliability_daily");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void exec(String sql) throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement()) {
      stmt.execute(sql);
    }
  }

  private LocalDate etDaysAgo(int k) throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps = conn.prepareStatement("SELECT toDate(now('America/New_York')) - ?")) {
      ps.setInt(1, k);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getDate(1).toLocalDate();
      }
    }
  }

  private void realized(long gameId, int ab, int pitch, String description, int daysAgo)
      throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO pitches_live (game_id, at_bat_index, pitch_number, game_date,"
                    + " ingested_at, pitcher_id, batter_id, description, pitch_type, balls,"
                    + " strikes, outs, inning, home_score, away_score, home_team, away_team)"
                    + " VALUES (?, ?, ?, toDate(now('America/New_York')) - ?, now(), 1, 2, ?,"
                    + " 'FF', 0, 0, 0, 1, 0, 0, 'HOME', 'AWAY')")) {
      ps.setLong(1, gameId);
      ps.setInt(2, ab);
      ps.setInt(3, pitch);
      ps.setInt(4, daysAgo);
      ps.setString(5, description);
      ps.execute();
    }
  }

  /** A champion y5 prediction for "ball" at confidence {@code c}, logged {@code secAgo} ago. */
  private void predictBall(long gameId, int ab, int pitch, double c, int secAgo) throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO prediction_log (request_id, request_at, model_name, model_version,"
                    + " role, feature_hash, features, prediction, latency_ms, correlation_id,"
                    + " game_id, at_bat_index, pitch_number) VALUES"
                    + " (generateUUIDv4(), now64(3) - ?, ?, 'v1', 'champion', 'h', '{}', ?, 1.0,"
                    + " 'cid', ?, ?, ?)")) {
      ps.setInt(1, secAgo);
      ps.setString(2, PRE);
      ps.setString(3, "{\"probabilities\":{\"ball\":" + c + "},\"winner\":\"ball\"}");
      ps.setLong(4, gameId);
      ps.setInt(5, ab);
      ps.setInt(6, pitch);
      ps.execute();
    }
  }

  /** A rollup row written directly (read-path fixtures that pitches_live cannot hold). */
  private void rollupRow(int daysAgo, int bin, long n, long hits, double sc) throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var ps =
            conn.prepareStatement(
                "INSERT INTO live_reliability_daily"
                    + " (model_name, game_date, bin, n, hits, sum_confidence, computed_at)"
                    + " VALUES (?, toDate(now('America/New_York')) - ?, ?, ?, ?, ?,"
                    + " now64(3, 'UTC'))")) {
      ps.setString(1, PRE);
      ps.setInt(2, daysAgo);
      ps.setInt(3, bin);
      ps.setLong(4, n);
      ps.setLong(5, hits);
      ps.setDouble(6, sc);
      ps.execute();
    }
  }

  private long finalRowCount() throws Exception {
    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement();
        var rs = stmt.executeQuery("SELECT count() FROM live_reliability_daily FINAL")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static long n(ReliabilityWindow w) {
    return w.rows().stream().mapToLong(ReliabilityBinRow::n).sum();
  }

  // --- write path ------------------------------------------------------------------------------

  @Test
  void runningTwiceIsIdempotent_andWritesTheFullZeroGrid() throws Exception {
    realized(1L, 1, 1, "ball", 2);
    realized(1L, 1, 2, "foul", 2);
    predictBall(1L, 1, 1, 0.65, 60);
    predictBall(1L, 1, 2, 0.65, 60);

    rollup.rollup(PRE, 3);
    ReliabilityWindow first = rollup.window(PRE, 30);
    rollup.rollup(PRE, 3);
    ReliabilityWindow second = rollup.window(PRE, 30);

    assertThat(second).isEqualTo(first);
    ReliabilityBinRow row = first.rows().getFirst();
    assertThat(first.rows()).hasSize(1);
    assertThat(row.gameDate()).isEqualTo(etDaysAgo(2));
    assertThat(row.bin()).isEqualTo(6);
    assertThat(row.n()).isEqualTo(2);
    assertThat(row.hits()).isEqualTo(1);
    assertThat(finalRowCount()).as("3 days x 10 bins, zeros included").isEqualTo(30);
  }

  @Test
  void aTruthCorrectionThatEmptiesABinLeavesNoStaleCount() throws Exception {
    realized(2L, 1, 1, "ball", 1);
    predictBall(2L, 1, 1, 0.65, 60);
    rollup.rollup(PRE, 3);
    assertThat(n(rollup.window(PRE, 30))).isEqualTo(1);

    // The feed corrects the pitch to an out-of-vocabulary call: it leaves the scorable set, so
    // bin 6 must read zero after the next run - not keep its stale 1.
    Thread.sleep(1100); // ReplacingMergeTree(ingested_at) is second-resolution
    realized(2L, 1, 1, "pitchout", 1);
    rollup.rollup(PRE, 3);

    assertThat(rollup.window(PRE, 30).rows()).isEmpty();
  }

  @Test
  void aRelogThatMovesBinsLeavesNoStaleCount() throws Exception {
    realized(3L, 1, 1, "ball", 1);
    predictBall(3L, 1, 1, 0.65, 120);
    rollup.rollup(PRE, 3);

    predictBall(3L, 1, 1, 0.35, 60); // later re-log, still before ingest: the latest wins
    rollup.rollup(PRE, 3);

    ReliabilityWindow w = rollup.window(PRE, 30);
    assertThat(w.rows()).extracting(ReliabilityBinRow::bin).containsExactly(3);
    assertThat(n(w)).isEqualTo(1);
  }

  @Test
  void daysOlderThanTheRecomputeWindowAreFrozen() throws Exception {
    realized(4L, 1, 1, "ball", 5);
    predictBall(4L, 1, 1, 0.65, 60);
    rollup.rollup(PRE, 13);

    // pitches_live expires (TTL simulated): a 3-day recompute must not touch day -5.
    exec("TRUNCATE TABLE pitches_live");
    rollup.rollup(PRE, 3);

    ReliabilityWindow w = rollup.window(PRE, 30);
    assertThat(w.rows()).hasSize(1);
    assertThat(w.rows().getFirst().gameDate()).isEqualTo(etDaysAgo(5));
  }

  @Test
  void theFirstRunBackfillCoversThePitchesLiveSpan() throws Exception {
    assertThat(rollup.daysSinceLastRollup(PRE)).as("no row yet = first run").isEqualTo(-1);
    realized(5L, 1, 1, "ball", 12);
    predictBall(5L, 1, 1, 0.95, 60);

    rollup.rollup(PRE, 13);

    ReliabilityWindow w = rollup.window(PRE, 30);
    assertThat(w.rows()).hasSize(1);
    assertThat(w.rows().getFirst().gameDate()).isEqualTo(etDaysAgo(12));
    assertThat(w.rows().getFirst().bin()).isEqualTo(9);
    assertThat(rollup.daysSinceLastRollup(PRE)).as("the grid ends yesterday").isEqualTo(1);
  }

  @Test
  void theRollupNeverWritesToday() throws Exception {
    realized(6L, 1, 1, "ball", 0);
    predictBall(6L, 1, 1, 0.65, 60);
    rollup.rollup(PRE, 3);

    try (var conn = clickhouseDs.getConnection();
        var stmt = conn.createStatement();
        var rs =
            stmt.executeQuery(
                "SELECT count() FROM live_reliability_daily"
                    + " WHERE game_date >= toDate(now('America/New_York'))")) {
      rs.next();
      assertThat(rs.getLong(1)).isZero();
    }
  }

  // --- read path -------------------------------------------------------------------------------

  @Test
  void theReadIsThroughYesterday_withTruthDatesAndZeroRowsDropped() throws Exception {
    rollupRow(0, 6, 50, 30, 32.0); // today: excluded
    rollupRow(2, 6, 10, 6, 6.5);
    rollupRow(4, 3, 20, 7, 7.0);
    rollupRow(3, 5, 0, 0, 0.0); // zero row: dropped

    ReliabilityWindow w = rollup.window(PRE, 30);
    assertThat(w.kind()).isEqualTo(ReliabilityWindow.Kind.CALENDAR);
    assertThat(w.rows())
        .extracting(ReliabilityBinRow::gameDate)
        .containsExactly(etDaysAgo(4), etDaysAgo(2));
    assertThat(n(w)).isEqualTo(30);

    // The calendar window is exactly K days through yesterday: K = 2 keeps day -2, drops -4.
    assertThat(rollup.window(PRE, 2).rows())
        .extracting(ReliabilityBinRow::gameDate)
        .containsExactly(etDaysAgo(2));
  }

  @Test
  void anEmptyCalendarWindowFallsBackToTheLastDaysOfPlay() throws Exception {
    // Offseason: nothing in the last 30 days, three game days in October.
    rollupRow(60, 6, 100, 60, 65.0);
    rollupRow(61, 6, 100, 70, 65.0);
    rollupRow(65, 4, 100, 40, 45.0);
    rollupRow(62, 5, 0, 0, 0.0); // zero-grid day: not a day of play

    ReliabilityWindow w = rollup.window(PRE, 2);
    assertThat(w.kind()).isEqualTo(ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY);
    assertThat(w.rows())
        .extracting(ReliabilityBinRow::gameDate)
        .containsExactly(etDaysAgo(61), etDaysAgo(60));

    ReliabilityWindow all = rollup.window(PRE, 30);
    assertThat(all.kind()).isEqualTo(ReliabilityWindow.Kind.LAST_DAYS_OF_PLAY);
    assertThat(n(all)).isEqualTo(300);
  }

  @Test
  void anEmptyTableIsAnEmptyCalendarWindow() {
    ReliabilityWindow w = rollup.window(PRE, 30);
    assertThat(w.kind()).isEqualTo(ReliabilityWindow.Kind.CALENDAR);
    assertThat(w.rows()).isEqualTo(List.of());
  }
}
