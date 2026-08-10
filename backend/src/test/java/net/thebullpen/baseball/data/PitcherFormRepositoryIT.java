package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
 * Real-ClickHouse round-trip for {@link PitcherFormRepository} (DP2 / WS3). Inserts synthetic
 * pitches across the 28-day boundary and asserts {@code refreshCurrentForm()} materialises one
 * current-form row per ACTIVE pitcher with rates matching compute_tier3.sql's definitions, and
 * excludes a pitcher whose only pitches fall outside the window. Docker-gated exactly like {@link
 * LivePitchesRepositoryIT}.
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
class PitcherFormRepositoryIT {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test")
          // Pin the server TZ to UTC so CH today() is deterministic; fixture dates use
          // LocalDate.now(ET) (line 578), so this pin is belt-and-suspenders, not load-bearing.
          .withEnv("TZ", "UTC");

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
                "bullpen-pitcherform-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> sqliteUrl);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> sqliteUrl);
  }

  @Autowired private PitcherFormRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  private JdbcTemplate ch;

  @BeforeEach
  void wipe() {
    ch = new JdbcTemplate(clickhouseDs);
    ch.execute("TRUNCATE TABLE IF EXISTS pitches");
    ch.execute("TRUNCATE TABLE IF EXISTS pitcher_form_current");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  @Test
  void refresh_materialises_current_form_for_active_pitchers_only() {
    // Pitcher 100, 3 days ago: 10 pitches -> 4 strikes (1 called + 2 swinging + 1 foul),
    // 2 swinging, 3 in_play. Pitcher 200's only pitch is 40 days ago -> outside the 28-day window.
    insertPitches(
        100,
        daysAgo(3),
        "called_strike",
        "swinging_strike",
        "swinging_strike",
        "foul",
        "ball",
        "ball",
        "ball",
        "in_play",
        "in_play",
        "in_play");
    insertPitches(200, daysAgo(40), "ball");

    long refreshed = repo.refreshCurrentForm();
    assertThat(refreshed).isEqualTo(1); // only pitcher 100 is active in the window

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, strike_rate_28d, swstrike_rate_28d, inplay_rate_28d,"
                + " days_since_last_appearance"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 100");
    assertThat(num(row, "pitches_last_28d")).isEqualTo(10.0);
    assertThat(num(row, "strike_rate_28d")).isCloseTo(0.4, within(1e-6)); // 4/10
    assertThat(num(row, "swstrike_rate_28d")).isCloseTo(0.2, within(1e-6)); // 2/10
    assertThat(num(row, "inplay_rate_28d")).isCloseTo(0.3, within(1e-6)); // 3/10
    assertThat(num(row, "days_since_last_appearance")).isEqualTo(3.0); // today - (today-3)

    // The out-of-window pitcher has no current-form row.
    Long n200 =
        ch.queryForObject(
            "SELECT count() FROM pitcher_form_current FINAL WHERE pitcher_id = 200", Long.class);
    assertThat(n200).isZero();
  }

  @Test
  void findCurrent_reads_the_refreshed_form_and_is_empty_for_an_unknown_pitcher() {
    insertPitches(100, daysAgo(3), "called_strike", "swinging_strike", "ball", "in_play");
    repo.refreshCurrentForm();

    PitcherForm form = repo.findCurrent(100).orElseThrow();
    assertThat(form.pitchesLast28d()).isEqualTo(4.0);
    assertThat(form.daysSinceLastAppearance()).isEqualTo(3.0);
    assertThat(form.pitchesInGame()).isZero(); // nightly snapshot: in-game count is intra-day only

    assertThat(repo.findCurrent(999)).isEmpty(); // a pitcher with no current-form row
  }

  @Test
  void intra_day_upsert_sets_in_game_count_and_zeroes_dsla_carrying_28d_forward() {
    // Nightly snapshot: 3 days ago, 10 pitches -> pitches_in_game=0, dsla=3, 28d rates set.
    insertPitches(
        100,
        daysAgo(3),
        "called_strike",
        "swinging_strike",
        "swinging_strike",
        "foul",
        "ball",
        "ball",
        "ball",
        "in_play",
        "in_play",
        "in_play");
    repo.refreshCurrentForm();
    assertThat(repo.findCurrent(100).orElseThrow().pitchesInGame()).isZero();

    // Tonight: 5 live pitches for pitcher 100 in game 555.
    long gameId = 555L;
    for (int i = 1; i <= 5; i++) {
      insertLivePitch(gameId, 100, i);
    }

    repo.upsertIntraDayForm(100, gameId);

    PitcherForm after = repo.findCurrent(100).orElseThrow();
    assertThat(after.pitchesInGame()).isEqualTo(5.0); // live in-game count
    assertThat(after.daysSinceLastAppearance()).isZero(); // appearing today
    assertThat(after.pitchesLast28d()).isEqualTo(10.0); // 28-day window carried forward unchanged
    assertThat(after.strikeRate28d()).isCloseTo(0.4, within(1e-6)); // 4/10, unchanged

    // Idempotent: a second upsert with the same live count leaves the FINAL read stable.
    repo.upsertIntraDayForm(100, gameId);
    PitcherForm again = repo.findCurrent(100).orElseThrow();
    assertThat(again.pitchesInGame()).isEqualTo(5.0);
    assertThat(again.daysSinceLastAppearance()).isZero();
    assertThat(again.pitchesLast28d()).isEqualTo(10.0);
  }

  @Test
  void intra_day_upsert_is_a_noop_for_a_pitcher_with_no_current_row() {
    insertLivePitch(555L, 777, 1); // pitcher 777 has live pitches but no nightly form row
    repo.upsertIntraDayForm(777, 555L);
    assertThat(repo.findCurrent(777)).isEmpty(); // no row fabricated
  }

  // --- the 2026-07-27 data-anchor fix ---------------------------------------

  @Test
  void refresh_stamps_as_of_date_from_the_corpus_not_the_clock() {
    // THE FIX UNDER TEST. The clock-anchored stamp said "fresh" for two months while the corpus
    // (last backfill ran 2026-05-28, loading games through 2026-05-25) had aged out of every 28-day
    // window and the nightly refresh
    // selected nothing. Seeding the corpus edge 3 days back keeps the window non-empty while
    // making the anchor distinguishable from today - so reverting the stamp to today() reds this.
    insertPitches(101, daysAgo(3), "called_strike", "ball");
    repo.refreshCurrentForm();

    Object stamped =
        ch.queryForMap("SELECT as_of_date FROM pitcher_form_current FINAL WHERE pitcher_id = 101")
            .get("as_of_date");
    assertThat(String.valueOf(stamped))
        .as("as_of_date must record what the DATA covers, not when the job ran")
        .isEqualTo(daysAgo(3))
        .isNotEqualTo(daysAgo(0));
  }

  @Test
  void intra_day_upsert_carries_as_of_date_forward() {
    // The column describes what the 28-day window values cover, and the upsert carries those
    // values unchanged - so it must carry their coverage date too. Stamping today() here was what
    // made the table read fresh WHOLESALE while the nightly leg was dead (the live strays wore
    // today's date). It also lands the replacement in the same monthly partition as the row it
    // supersedes, which background merges can actually collapse - merges never cross partitions.
    insertPitches(102, daysAgo(3), "swinging_strike", "in_play");
    repo.refreshCurrentForm();
    insertLivePitch(9001L, 102, 1);
    repo.upsertIntraDayForm(102, 9001L);

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT as_of_date, pitches_in_game, days_since_last_appearance"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 102");
    // pitches_in_game = 1 proves the row under assertion IS the upsert's - without it this test
    // passes identically if the upsert writes nothing, since the nightly row carries the same
    // anchor (a conjunction pin over both legs, not an isolated upsert pin).
    assertThat(num(row, "pitches_in_game")).isEqualTo(1.0);
    assertThat(num(row, "days_since_last_appearance")).isZero();
    assertThat(String.valueOf(row.get("as_of_date")))
        .as("the carried-forward window values still cover the corpus edge, not today")
        .isEqualTo(daysAgo(3));
  }

  @Test
  void corpus_max_game_date_fails_loud_on_empty_and_returns_the_edge() {
    // The empty case comes FIRST, and comes free: @BeforeEach wiped pitches. It matters because
    // ClickHouse returns the TYPE DEFAULT (1970-01-01), not NULL, for max() over an empty table -
    // so an empty corpus must be a loud fault, never an epoch-dated gauge reading of ~20k days.
    assertThatThrownBy(repo::windowSourceMaxGameDate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty");

    insertPitches(103, daysAgo(5), "ball");
    insertPitches(104, daysAgo(3), "ball");
    assertThat(repo.windowSourceMaxGameDate()).isEqualTo(LocalDate.parse(daysAgo(3)));
  }

  @Test
  void refresh_count_ignores_a_live_leg_row_stamped_today() {
    // THE LIE THE OLD COUNT TOLD. With the corpus outside every 28-day window, the nightly INSERT
    // selects nothing - but a live-leg stray stamped today() made the today()-based count report
    // "refreshed 1" (prod said "refreshed 7" the morning this was found). Here the table starts
    // empty so the anchor-based count reads zero; in steady state it reads the cohort AT the
    // anchor (earlier nights' rows persist there), which is why the GAUGE, not this count, is
    // the staleness signal - see runOnce's javadoc.
    insertPitches(105, daysAgo(60), "ball", "called_strike");
    ch.update(
        "INSERT INTO pitcher_form_current"
            + " (pitcher_id, as_of_date, pitches_in_game, pitches_last_28d,"
            + "  strike_rate_28d, swstrike_rate_28d, inplay_rate_28d, days_since_last_appearance)"
            + " VALUES (?, ?, 3, 10, 0.5, 0.2, 0.3, 0)",
        105L,
        // Bound as a STRING, deliberately: java.sql.Date is mis-bound by clickhouse-jdbc into
        // this Date column (the auditor probed the stored value: 1975-06-16, not today) - which
        // made this whole test VACUOUS, passing with or without the count fix because the "stray
        // stamped today" never existed. insertPitches binds its date as a String for the same
        // reason; insertLivePitch carries the same latent mis-bind, saved only because the
        // intra-day count keys on game_id + pitcher_id rather than the date.
        daysAgo(0));

    assertThat(repo.refreshCurrentForm())
        .as("a stray live-leg row must not be reported as a refreshed pitcher")
        .isZero();
  }

  // --- the [186] union window -------------------------------------------

  /**
   * THE [186] CORE PIN, expectations hand-computed FIRST: corpus (d-10): called_strike + ball; live
   * (d-2): swinging_strike + ball; plus an OVERLAP row sharing the corpus pitch's FULL key but
   * claiming 'in_play'. Deduped-with-historical-winning = 4 pitches: strike_rate 2/4, swstr 1/4,
   * inplay 0/4, anchor d-2, dsla 2. Every failure mode lands on a DIFFERENT number: no dedup -> 5
   * pitches; live-wins-overlap -> inplay 0.25; live leg ignored -> 2 pitches and anchor d-10.
   */
  @Test
  void refresh_window_reads_the_union_and_dedups_the_overlap_with_historical_winning() {
    insertPitches(106, daysAgo(10), "called_strike", "ball");
    insertLivePitch(9106L, 106, 1, 1, daysAgo(2), "swinging_strike");
    insertLivePitch(9106L, 106, 1, 2, daysAgo(2), "ball");
    // The overlap: insertPitches uses game_id = pitcherId, at_bat_index 1, pitch_number 1..n -
    // this live row collides with the FIRST corpus pitch's key and contradicts its description.
    insertLivePitch(106L, 106, 1, 1, daysAgo(10), "in_play");

    repo.refreshCurrentForm();

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, strike_rate_28d, swstrike_rate_28d, inplay_rate_28d,"
                + " as_of_date, days_since_last_appearance"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 106");
    assertThat(num(row, "pitches_last_28d")).as("union minus the overlap duplicate").isEqualTo(4.0);
    assertThat(num(row, "strike_rate_28d")).isEqualTo(0.5);
    assertThat(num(row, "swstrike_rate_28d")).isEqualTo(0.25);
    assertThat(num(row, "inplay_rate_28d"))
        .as("0 proves the HISTORICAL row won the overlap - the live twin said in_play")
        .isZero();
    assertThat(String.valueOf(row.get("as_of_date"))).isEqualTo(daysAgo(2));
    assertThat(num(row, "days_since_last_appearance")).isEqualTo(2.0);
  }

  /**
   * Strictly-before ([186]): a live pitch dated TODAY is excluded from the 28d window AND the
   * anchor - training's frame is RANGE ... 1 PRECEDING, and today's pitches belong to the intra-day
   * upsert. Load-bearing since the union: an intra-day runOnce would otherwise pull in-progress
   * pitches into rates training never sees.
   */
  @Test
  void refresh_window_excludes_todays_live_rows() {
    insertPitches(107, daysAgo(5), "ball", "called_strike");
    insertLivePitch(9107L, 107, 1, 1, daysAgo(0), "swinging_strike");

    repo.refreshCurrentForm();

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, swstrike_rate_28d, as_of_date"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 107");
    assertThat(num(row, "pitches_last_28d")).isEqualTo(2.0);
    assertThat(num(row, "swstrike_rate_28d"))
        .as("today's swinging_strike must not leak in")
        .isZero();
    assertThat(String.valueOf(row.get("as_of_date"))).isEqualTo(daysAgo(5));
  }

  /**
   * WITHIN-TABLE dedup of a re-polled pitch: same canonical key, later {@code ingested_at},
   * CORRECTED description (ml-leakage-auditor finding 3).
   *
   * <p>The two assertions are NOT equally strong, and saying so is the point. The COUNT pins the
   * dedup: removing {@code LIMIT 1 BY} reds this and the overlap test. The CORRECTION assertion
   * pins NOTHING, verified rather than assumed - I mutated away the leg {@code FINAL}, then {@code
   * ingested_at DESC}, then BOTH, and it passed every time, because the leg FINAL and the ordering
   * are mutually redundant and raw scan order happens to favor the later insert on top. It is kept
   * as a characterization of intended semantics (the ordering clause is what makes "the newest read
   * wins" GUARANTEED rather than incidental - ClickHouse promises nothing about duplicate order
   * without it), explicitly not as a regression pin. A future reader must not mistake this green
   * for evidence that the ordering clause is protected.
   */
  @Test
  void refresh_window_dedups_a_repolled_live_pitch_keeping_the_correction() {
    insertPitches(110, daysAgo(3), "ball");
    insertLivePitch(9110L, 110, 1, 1, daysAgo(2), "ball", "2026-01-01 00:00:00");
    insertLivePitch(9110L, 110, 1, 1, daysAgo(2), "swinging_strike", "2026-01-02 00:00:00");

    repo.refreshCurrentForm();

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, swstrike_rate_28d"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 110");
    assertThat(num(row, "pitches_last_28d"))
        .as("the re-poll is the same pitch, not a second one")
        .isEqualTo(2.0);
    assertThat(num(row, "swstrike_rate_28d"))
        .as("the LATER ingested_at wins - the correction, not the superseded read")
        .isEqualTo(0.5);
  }

  /**
   * The rider fix's own pin: {@code pitches_in_game} counts a re-polled pitch ONCE. This count has
   * no LIMIT 1 BY to fall back on, so FINAL is solely load-bearing here - dropping it made this
   * number 3 instead of 2 (mutation-verified).
   */
  @Test
  void intra_day_count_dedups_a_repolled_pitch() {
    insertPitches(111, daysAgo(3), "ball");
    repo.refreshCurrentForm();
    insertLivePitch(9111L, 111, 1, 1, daysAgo(0), "ball", "2026-01-01 00:00:00");
    insertLivePitch(9111L, 111, 1, 2, daysAgo(0), "ball", "2026-01-01 00:00:00");
    insertLivePitch(9111L, 111, 1, 2, daysAgo(0), "called_strike", "2026-01-02 00:00:00");

    repo.upsertIntraDayForm(111, 9111L);

    assertThat(
            num(
                ch.queryForMap(
                    "SELECT pitches_in_game FROM pitcher_form_current FINAL"
                        + " WHERE pitcher_id = 111"),
                "pitches_in_game"))
        .as("two distinct pitches thrown, one of them polled twice")
        .isEqualTo(2.0);
  }

  /**
   * The INCLUSIVE lower bound (finding 5): a pitch at exactly {@code TODAY_ET - 28} is INSIDE the
   * window. Regressing {@code >=} to {@code >} would drop it, and no other fixture sits on the edge
   * to notice.
   */
  @Test
  void refresh_window_includes_the_oldest_in_bound_day() {
    insertPitches(112, daysAgo(28), "called_strike");
    insertPitches(112, daysAgo(29), "ball");

    repo.refreshCurrentForm();

    Map<String, Object> row =
        ch.queryForMap(
            "SELECT pitches_last_28d, strike_rate_28d"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 112");
    assertThat(num(row, "pitches_last_28d")).as("d-28 is in bounds, d-29 is not").isEqualTo(1.0);
    assertThat(num(row, "strike_rate_28d")).isEqualTo(1.0);
  }

  /** The gauge source reads the union edge: a live row newer than the corpus moves it. */
  @Test
  void window_source_max_reads_the_union_edge() {
    insertPitches(108, daysAgo(6), "ball");
    insertLivePitch(9108L, 108, 1, 1, daysAgo(1), "ball");
    assertThat(repo.windowSourceMaxGameDate()).isEqualTo(LocalDate.parse(daysAgo(1)));
  }

  /**
   * THE [186] PARITY TEST: the serving union window vs the REAL training definition
   * (compute_tier3.sql, read from disk, binds substituted) on a fixed fixture. The fixture
   * duplicates one corpus row into pitches_live with an IDENTICAL description (the union must dedup
   * it away for parity to hold; the different-description overlap above pins who wins), and adds a
   * reference pitch dated TODAY in pitches: training's frame for that pitch is [today-28, today-1],
   * exactly the serving window - and the reference pitch is outside both (1 PRECEDING on the
   * training side, <= today()-1 on the serving side), which this parity would break on if either
   * bound regressed.
   */
  @Test
  void serving_union_window_matches_the_training_definition_on_fixed_rows() throws Exception {
    insertPitches(109, daysAgo(20), "called_strike", "in_play", "ball");
    insertPitches(109, daysAgo(4), "swinging_strike", "foul");
    // Same key, same description: pure duplicate across the tables.
    insertLivePitch(109L, 109, 1, 1, daysAgo(20), "called_strike");
    // The reference pitch (today, distinct game) whose training-side frame is the serving window.
    ch.update(
        "INSERT INTO pitches"
            + " (game_id, game_date, at_bat_index, pitch_number, pitcher_id, description)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        99109L,
        daysAgo(0),
        1,
        1,
        109,
        "ball");

    repo.refreshCurrentForm();
    Map<String, Object> serving =
        ch.queryForMap(
            "SELECT pitches_last_28d, strike_rate_28d, swstrike_rate_28d, inplay_rate_28d,"
                + " days_since_last_appearance"
                + " FROM pitcher_form_current FINAL WHERE pitcher_id = 109");

    String trainingSql =
        Files.readString(trainingSql("compute_tier3.sql"))
            .replace(":test_start", "'" + daysAgo(0) + "'")
            .replace(":test_end", "'" + daysAgo(0) + "'")
            .replace("SETTINGS max_memory_usage = 0", "");
    Map<String, Object> training =
        ch.queryForMap(
            "SELECT pitcher_pitches_last_28d, pitcher_strike_rate_28d,"
                + " pitcher_swstrike_rate_28d, pitcher_inplay_rate_28d,"
                + " days_since_last_appearance AS dsla"
                + " FROM ("
                + trainingSql
                + ") WHERE game_id = 99109");

    // Anti-vacuity: the hand-computed frame is 5 pitches (3 + 2, duplicate deduped);
    // strike 3/5 (called_strike, swinging_strike, foul), swstr 1/5, inplay 1/5, dsla 4.
    assertThat(num(serving, "pitches_last_28d")).isEqualTo(5.0);
    assertThat(num(training, "pitcher_pitches_last_28d")).isEqualTo(5.0);
    // Rates: BOTH sides anchored to the hand-computed expectation, with fp tolerance - the
    // serving column is Float32 while training's division result stays Float64, so exact
    // cross-equality would fail on representation, not semantics.
    assertParityRate(serving, "strike_rate_28d", training, "pitcher_strike_rate_28d", 3.0 / 5.0);
    assertParityRate(
        serving, "swstrike_rate_28d", training, "pitcher_swstrike_rate_28d", 1.0 / 5.0);
    assertParityRate(serving, "inplay_rate_28d", training, "pitcher_inplay_rate_28d", 1.0 / 5.0);
    assertThat(num(serving, "days_since_last_appearance")).isEqualTo(4.0);
    assertThat(num(training, "dsla")).isEqualTo(4.0);
  }

  private static void assertParityRate(
      Map<String, Object> serving,
      String servingCol,
      Map<String, Object> training,
      String trainingCol,
      double expected) {
    assertThat(num(serving, servingCol)).isCloseTo(expected, within(1e-6));
    assertThat(num(training, trainingCol)).isCloseTo(expected, within(1e-6));
  }

  private static java.nio.file.Path trainingSql(String name) {
    return java.nio.file.Path.of(System.getProperty("user.dir"))
        .getParent()
        .resolve("training/src/bullpen_training/features/sql")
        .resolve(name);
  }

  // --- helpers ----------------------------------------------------------

  private void insertPitches(int pitcherId, String gameDate, String... descriptions) {
    for (int i = 0; i < descriptions.length; i++) {
      // Distinct pitch_number per row so the ReplacingMergeTree ORDER BY key
      // (game_date, game_id, at_bat_index, pitch_number) keeps each pitch under FINAL.
      ch.update(
          "INSERT INTO pitches"
              + " (game_id, game_date, at_bat_index, pitch_number, pitcher_id, description)"
              + " VALUES (?, ?, ?, ?, ?, ?)",
          (long) pitcherId,
          gameDate,
          1,
          i + 1,
          pitcherId,
          descriptions[i]);
    }
  }

  /** Intra-day-count shape: today's date, description irrelevant to the count. */
  private void insertLivePitch(long gameId, int pitcherId, int pitchNumber) {
    insertLivePitch(gameId, pitcherId, 1, pitchNumber, daysAgo(0), "ball");
  }

  /**
   * One live pitch with the FULL canonical key {@code (game_id, at_bat_index, pitch_number)}
   * caller-controlled - the [186] union tests need key collisions with corpus rows (overlap dedup)
   * and non-today dates (window membership). {@code gameDate} is bound as a STRING for the same
   * clickhouse-jdbc mis-bind reason insertPitches documents (the old helper hardcoded a
   * java.sql.Date, harmless only while nothing filtered the live leg by date - the union does).
   */
  private void insertLivePitch(
      long gameId, int pitcherId, int atBatIndex, int pitchNumber, String gameDate, String desc) {
    insertLivePitch(
        gameId, pitcherId, atBatIndex, pitchNumber, gameDate, desc, "2026-01-01 00:00:00");
  }

  /**
   * Same, with an explicit {@code ingested_at} - the ReplacingMergeTree version column. A re-poll
   * of the same pitch is the SAME canonical key with a LATER ingested_at, which is the only way to
   * construct the correction case deterministically.
   */
  private void insertLivePitch(
      long gameId,
      int pitcherId,
      int atBatIndex,
      int pitchNumber,
      String gameDate,
      String desc,
      String ingestedAt) {
    ch.update(
        "INSERT INTO pitches_live"
            + " (game_id, pitch_number, pitcher_id, game_date,"
            + "  at_bat_index, ingested_at, batter_id, description, balls, strikes, outs, inning,"
            + "  home_score, away_score, home_team, away_team)"
            + " VALUES (?, ?, ?, ?, ?, ?, 200, ?, 0, 0, 0, 1, 0, 0, 'HOM', 'AWY')",
        gameId,
        pitchNumber,
        pitcherId,
        gameDate,
        atBatIndex,
        ingestedAt,
        desc);
  }

  /**
   * ET, matching the repository's {@code TODAY_ET} basis. NOT UTC: between 20:00 and 23:59 ET the
   * two calendars disagree, and a UTC fixture against ET bounds would make the strictly-before
   * tests pass or fail by wall-clock hour. (The container's TZ=UTC pin is now irrelevant to these
   * queries, which name their zone explicitly - it stays because other reads still use it.)
   */
  private static String daysAgo(int days) {
    return LocalDate.now(ET).minusDays(days).toString();
  }

  private static double num(Map<String, Object> row, String col) {
    return ((Number) row.get(col)).doubleValue();
  }
}
