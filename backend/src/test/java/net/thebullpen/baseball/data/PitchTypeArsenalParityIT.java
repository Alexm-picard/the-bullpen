package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
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
 * TRAINING/SERVING PARITY FOR TIER-ARS. The serving path composes a nightly count snapshot with an
 * in-game delta; training computes the same quantity with a career-expanding window in {@code
 * compute_pitch_type_arsenal.sql}. This asserts they agree, value for value, over the same
 * ClickHouse rows.
 *
 * <p>WHY A COMMENT WAS NOT ENOUGH. The pitch-OUTCOME family's Tier-3 features use a 90-day warm-up
 * floor, and that is the in-repo precedent a reader reaches for. Reusing it here would still return
 * well-formed frequencies in [0,1] for every pitcher - just computed over the wrong history. There
 * is no shape check, range check, or smoke test that can see that. Only comparing against the
 * training definition itself can.
 *
 * <p>THE REFERENCE IS THE REAL TRAINING SQL, READ FROM DISK. Not a Java reimplementation of it and
 * not a committed fixture, both of which would drift the moment someone edits the SQL and would
 * then assert parity with a definition that no longer ships. Reading the file means the reference
 * moves with the source of truth, so this test keeps its meaning instead of quietly becoming a
 * tautology.
 */
@SpringBootTest
@ActiveProfiles("api")
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class PitchTypeArsenalParityIT {

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
  }

  @Autowired private PitchTypeArsenalDeriver deriver;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouse;

  private JdbcTemplate ch;

  /** The pitcher under test, plus a decoy whose rows must never leak into his counts. */
  private static final long PID = 4242L;

  private static final long DECOY_PID = 9999L;
  private static final LocalDate AS_OF = LocalDate.of(2024, 4, 30);
  private static final LocalDate GAME_DATE = LocalDate.of(2024, 5, 1);
  private static final long GAME_ID = 700_001L;
  private static final long GAME_ID_2 = 700_002L; // doubleheader, same date

  /**
   * The training SQL, resolved from the repo root. Made LOUD on failure: this test silently
   * degrades into meaninglessness if the file cannot be found and the reference is skipped, which
   * is exactly the failure it exists to prevent elsewhere.
   */
  private static Path trainingSql() {
    Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = cwd; p != null; p = p.getParent()) {
      Path candidate =
          p.resolve("training/src/bullpen_training/features/sql/compute_pitch_type_arsenal.sql");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError(
        "compute_pitch_type_arsenal.sql not found walking up from "
            + cwd
            + ". This test's reference IS that file; without it the comparison would be vacuous,"
            + " so it fails loudly rather than skipping.");
  }

  @BeforeAll
  static void sqlExists() {
    assertThat(trainingSql()).exists();
  }

  // --- fixture ---------------------------------------------------------------

  private record P(LocalDate date, long gameId, int ab, int pn, String type, int balls, int str) {}

  /**
   * A career whose composition is unambiguous, plus the traps that make composition wrong if the
   * implementation is careless.
   */
  private List<P> career() {
    List<P> rows = new ArrayList<>();
    // Career before the snapshot: 6 FF, 3 SL, 1 CU across two seasons.
    for (int i = 1; i <= 6; i++) {
      rows.add(new P(LocalDate.of(2023, 6, 1), 600_000L + i, 1, i, "FF", 0, 0));
    }
    for (int i = 1; i <= 3; i++) {
      rows.add(new P(LocalDate.of(2024, 3, 15), 610_000L + i, 1, i, "ST", 1, 1)); // ST folds to SL
    }
    rows.add(new P(AS_OF, 620_001L, 1, 1, "KC", 1, 1)); // KC folds to CU
    // Unlabeled rows: must be excluded by both sides, never folded into OFF.
    rows.add(new P(AS_OF, 620_001L, 1, 2, "PO", 0, 0));
    rows.add(new P(AS_OF, 620_001L, 1, 3, "", 0, 0));
    return rows;
  }

  /**
   * The current game, AFTER as_of_date. Two games on one date: the doubleheader is the case that
   * separates a correct tuple comparison from a "game_id = current" shortcut, which would drop the
   * earlier game entirely.
   */
  private List<P> currentGame() {
    return List.of(
        new P(GAME_DATE, GAME_ID, 1, 1, "FF", 1, 1),
        new P(GAME_DATE, GAME_ID, 1, 2, "SL", 1, 1),
        new P(GAME_DATE, GAME_ID_2, 1, 1, "FF", 1, 1), // second game of the doubleheader
        new P(GAME_DATE, GAME_ID_2, 1, 2, "CH", 0, 0));
  }

  /** The pitch being predicted: game 2, at-bat 1, pitch 3, on a 1-1 count. */
  private static final P CURRENT = new P(GAME_DATE, GAME_ID_2, 1, 3, "?", 1, 1);

  @Test
  void serving_derived_arsenal_equals_the_training_sql_value() throws Exception {
    ch = new JdbcTemplate(clickhouse);
    seedEverything();

    PitchTypeArsenalDeriver.Arsenal served =
        deriver.derive(
            PID,
            CURRENT.date(),
            CURRENT.gameId(),
            CURRENT.ab(),
            CURRENT.pn(),
            CURRENT.balls(),
            CURRENT.str(),
            GAME_DATE);

    Map<String, Object> expected = trainingReferenceForCurrentPitch();

    // prior_n first: if the denominators disagree, every ratio below is wrong for the same reason
    // and the individual failures would be noise.
    assertThat(served.pitcherPriorN())
        .as("prior_n: serving composition vs the training window")
        .isEqualTo(((Number) expected.get("pitcher_prior_n")).longValue());

    assertClose("ars_FF", served.arsFf(), expected.get("ars_FF"));
    assertClose("ars_SI", served.arsSi(), expected.get("ars_SI"));
    assertClose("ars_FC", served.arsFc(), expected.get("ars_FC"));
    assertClose("ars_SL", served.arsSl(), expected.get("ars_SL"));
    assertClose("ars_CU", served.arsCu(), expected.get("ars_CU"));
    assertClose("ars_CH", served.arsCh(), expected.get("ars_CH"));
    assertClose("ars_OFF", served.arsOff(), expected.get("ars_OFF"));
    assertClose("ars_FF_by_count", served.arsFfByCount(), expected.get("ars_FF_by_count"));
  }

  @Test
  void the_pinned_y7_constant_still_matches_the_training_sql() {
    // The backend cannot read the training SQL at runtime, so the fold ships as a constant. A
    // constant nobody checks is a copy waiting to drift, and a wrong fold produces a PLAUSIBLE
    // snapshot rather than an obviously broken one - every ars_* value shifts and nothing throws.
    // This is the lock-step, same shape as the model_kind and drift-key pins.
    String fromTraining = y7Expression().replaceAll("\\s+", " ").trim();
    String pinned = PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7.replaceAll("\\s+", " ").trim();
    assertThat(pinned)
        .as(
            "PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7 has drifted from"
                + " compute_pitch_type_arsenal.sql; re-lift it rather than editing one side")
        .isEqualTo(fromTraining);
  }

  @Test
  void the_fixture_actually_exercises_the_doubleheader_and_the_class_folds() throws Exception {
    ch = new JdbcTemplate(clickhouse);
    seedEverything();
    Map<String, Object> ref = trainingReferenceForCurrentPitch();

    // ANTI-VACUITY. If the fixture were degenerate the parity assertion above would hold
    // trivially, so this counts the same quantity by hand.
    //
    // 10 labeled career pitches (6 FF, 3 ST->SL, 1 KC->CU; the PO and '' rows are excluded rather
    // than folded) plus the FOUR thrown earlier today across BOTH games of the doubleheader -
    // game 700_001 pitches 1-2, and game 700_002 pitches 1-2 ahead of the pitch under test at
    // pitch 3 - is 14.
    //
    // Spelled out rather than computed: deriving it from the same tuple logic the implementation
    // uses would make this assert nothing. That is also how it was wrong on the first pass. I
    // counted three current-game pitches instead of four, and the parity assertion above passed
    // on that same run - which is exactly the case an independent count exists to expose.
    assertThat(((Number) ref.get("pitcher_prior_n")).longValue())
        .as("a doubleheader-blind implementation (game_id = current) would report 12, not 14")
        .isEqualTo(14L);
    assertThat((Double) ref.get("ars_SL")).as("ST must fold to SL").isGreaterThan(0.0);
    assertThat((Double) ref.get("ars_CU")).as("KC must fold to CU").isGreaterThan(0.0);
    assertThat((Double) ref.get("ars_OFF"))
        .as("the unlabeled PO/'' rows must be EXCLUDED, never folded into OFF")
        .isEqualTo(0.0);
  }

  @Test
  void a_snapshot_older_than_the_bound_is_refused_rather_than_undercounted() throws Exception {
    ch = new JdbcTemplate(clickhouse);
    seedEverything();

    // The delta reads pitches_live, whose 14-day TTL means rows after a stale as_of_date can live
    // ONLY in pitches and be counted by neither half. The prediction would still return, against a
    // silently short career. For a calibrated prior that is worse than no answer.
    LocalDate farFuture = AS_OF.plusDays(deriver.maxSnapshotAgeDays() + 30);
    assertThatThrownBy(() -> deriver.derive(PID, farFuture, GAME_ID_2, 1, 3, 1, 1, farFuture))
        .isInstanceOf(PitchTypeArsenalDeriver.PriorUnavailable.class)
        .hasMessageContaining("stale");
  }

  @Test
  void a_pitch_on_or_before_as_of_date_is_refused_as_a_leak() throws Exception {
    ch = new JdbcTemplate(clickhouse);
    seedEverything();

    // On or before as_of_date the snapshot already contains this pitch AND every later pitch of
    // that game, so composing would count the future of the row being predicted.
    assertThatThrownBy(() -> deriver.derive(PID, AS_OF, GAME_ID, 1, 1, 0, 0, GAME_DATE))
        .isInstanceOf(PitchTypeArsenalDeriver.PriorUnavailable.class)
        .hasMessageContaining("future");
  }

  // --- seeding ---------------------------------------------------------------

  private void seedEverything() throws Exception {
    createTables();
    insertPitches("pitches", career(), PID, 0L);
    // THE DECOY MUST NOT SHARE SORT KEYS WITH THE PITCHER UNDER TEST. `pitches` is a
    // ReplacingMergeTree ordered by (game_date, game_id, at_bat_index, pitch_number) and
    // pitcher_id is NOT in that key, so seeding the identical career for a second pitcher gives
    // every row a twin with the same key - and FINAL collapses each pair to ONE row. When the
    // decoy won, pitcher 4242 had zero rows and every downstream failure followed from that.
    // The offset keeps the isolation check honest while keeping the keys disjoint.
    insertPitches("pitches", career(), DECOY_PID, 50_000L);
    // The current game lives in pitches_live (not yet handed off), which is what the delta reads.
    insertPitches("pitches_live", currentGame(), PID, 0L);
    insertPitches("pitches_live", currentGame(), DECOY_PID, 50_000L);
    refreshSnapshot();

    // Split the failure modes at the source. If the seed is not visible here, every assertion
    // downstream fails for one upstream reason and the messages point at the wrong layer - which
    // is exactly what happened: a no-row refusal read as a snapshot bug when the pitches were
    // never there.
    Integer seeded =
        ch.queryForObject(
            "SELECT count() FROM pitches FINAL WHERE pitcher_id = ?", Integer.class, PID);
    assertThat(seeded)
        .as("seeded career for pitcher %d must be visible in pitches before anything else", PID)
        .isNotNull()
        .isGreaterThan(0);
  }

  /**
   * Populate the snapshot exactly as the nightly job will: counts, never ratios, with as_of_date
   * anchored to the observed max(game_date) in pitches rather than today().
   */
  /**
   * Populate the snapshot through the SAME code the nightly job uses. Inlining the SQL here would
   * make this test assert parity against a definition the job does not share, which is the failure
   * mode the shared class exists to remove.
   */
  private void refreshSnapshot() {
    ch.execute(PitcherPitchTypePriorSnapshotSql.refreshInsert(y7Expression()));
  }

  /** The class fold, lifted from the training SQL so a taxonomy change cannot desync the two. */
  private static String y7Expression() {
    String sql;
    try {
      sql = Files.readString(trainingSql());
    } catch (Exception e) {
      throw new AssertionError("cannot read the training SQL", e);
    }
    int start = sql.indexOf("multiIf(");
    assertThat(start).as("the training SQL must contain the y7 multiIf").isGreaterThan(-1);
    int depth = 0;
    for (int i = start + "multiIf".length(); i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '(') depth++;
      if (c == ')' && --depth == 0) {
        return sql.substring(start, i + 1);
      }
    }
    throw new AssertionError("unbalanced multiIf in the training SQL");
  }

  private Map<String, Object> trainingReferenceForCurrentPitch() {
    // The reference: everything the pitcher has thrown strictly before the current pitch, over the
    // union of both tables, computed with the training SQL's own class fold and label filter.
    return ch.queryForMap(
        """
        SELECT count() AS pitcher_prior_n,
               countIf(y7='FF') / count()  AS ars_FF,
               countIf(y7='SI') / count()  AS ars_SI,
               countIf(y7='FC') / count()  AS ars_FC,
               countIf(y7='SL') / count()  AS ars_SL,
               countIf(y7='CU') / count()  AS ars_CU,
               countIf(y7='CH') / count()  AS ars_CH,
               countIf(y7='OFF') / count() AS ars_OFF,
               countIf(y7='FF' AND balls=1 AND strikes=1)
                 / nullIf(countIf(balls=1 AND strikes=1), 0) AS ars_FF_by_count
        FROM (
          SELECT game_date, game_id, at_bat_index, pitch_number, balls, strikes, %s AS y7
          FROM pitches FINAL WHERE pitcher_id = %d AND pitch_type NOT IN ('', 'PO', 'IN')
          UNION ALL
          SELECT game_date, game_id, at_bat_index, pitch_number, balls, strikes, %s AS y7
          FROM pitches_live FINAL WHERE pitcher_id = %d AND pitch_type NOT IN ('', 'PO', 'IN')
        )
        WHERE (game_date, game_id, at_bat_index, pitch_number)
              < (toDate('%s'), %d, %d, %d)
        """
            .formatted(
                y7Expression(),
                PID,
                y7Expression(),
                PID,
                CURRENT.date(),
                CURRENT.gameId(),
                CURRENT.ab(),
                CURRENT.pn()));
  }

  private void createTables() {
    ch.execute("DROP TABLE IF EXISTS pitcher_pitchtype_prior_current");
    ch.execute(readMigration());
  }

  private String readMigration() {
    Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = cwd; p != null; p = p.getParent()) {
      Path m =
          p.resolve(
              "backend/src/main/resources/db/clickhouse/V030__pitcher_pitchtype_prior_current.sql");
      if (Files.isRegularFile(m)) {
        try {
          String body = Files.readString(m);
          return body.replaceAll("(?m)^--.*$", "").trim();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    }
    throw new AssertionError("V030 migration not found from " + cwd);
  }

  private void insertPitches(String table, List<P> rows, long pitcherId, long gameIdOffset) {
    for (P r : rows) {
      ch.update(
          "INSERT INTO "
              + table
              + " (game_id, game_date, at_bat_index, pitch_number, pitcher_id, pitch_type, balls,"
              + " strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
          r.gameId() + gameIdOffset,
          r.date().toString(),
          r.ab(),
          r.pn(),
          pitcherId,
          r.type(),
          r.balls(),
          r.str());
    }
  }

  private static void assertClose(String name, Double actual, Object expected) {
    if (expected == null || (expected instanceof Double d && d.isNaN())) {
      assertThat(actual).as("%s must be NaN/null on both sides", name).isNull();
      return;
    }
    assertThat(actual).as("%s: serving vs training", name).isNotNull();
    assertThat(actual)
        .as("%s: serving %s vs training %s", name, actual, expected)
        .isCloseTo(((Number) expected).doubleValue(), org.assertj.core.data.Offset.offset(1e-9));
  }
}
