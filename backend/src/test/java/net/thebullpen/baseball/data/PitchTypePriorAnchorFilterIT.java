package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * Pins the anchor's label filter (#382 item 2). The snapshot anchor reports the newest game_date
 * with USABLE typed pitches. An unfiltered anchor can outrun the aggregate on a day whose only live
 * rows are untyped (GUMBO leaves pitch_type empty until it types a pitch), stranding those pitches
 * in neither the snapshot nor the delta leg once typed. This test seeds exactly that scenario and
 * asserts the anchor stays behind the untyped day.
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
class PitchTypePriorAnchorFilterIT {

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

  @Autowired private PitcherPitchTypePriorRepository repo;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouse;

  private JdbcTemplate ch;

  @BeforeEach
  void resetTables() {
    ch = new JdbcTemplate(clickhouse);
    ch.execute("DROP TABLE IF EXISTS pitcher_pitchtype_prior_current");
    ch.execute(readMigration());
    ch.execute("TRUNCATE TABLE IF EXISTS pitches");
    ch.execute("TRUNCATE TABLE IF EXISTS pitches_live");
  }

  @Test
  void anchor_does_not_advance_to_a_day_with_only_untyped_pitches() {
    // Read TODAY_ET from the database's own clock so the fixture is always relative to the bound.
    LocalDate todayEt =
        ch.queryForObject("SELECT toDate(now('America/New_York'))", LocalDate.class);
    LocalDate typedDay = todayEt.minusDays(3);
    LocalDate untypedDay = todayEt.minusDays(2);

    long pid = 1001L;

    // Day with typed pitches (in pitches, the historical table).
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        800_001L,
        typedDay.toString(),
        1,
        1,
        pid,
        "FF",
        0,
        0);
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        800_001L,
        typedDay.toString(),
        1,
        2,
        pid,
        "SL",
        0,
        1);

    // Day with ONLY untyped pitches (in pitches_live, simulating GUMBO delay).
    ch.update(
        "INSERT INTO pitches_live (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        800_002L,
        untypedDay.toString(),
        1,
        1,
        pid,
        "",
        0,
        0);
    ch.update(
        "INSERT INTO pitches_live (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        800_002L,
        untypedDay.toString(),
        1,
        2,
        pid,
        "",
        1,
        0);

    LocalDate asOf = repo.refreshSnapshot(PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7);

    // The anchor must report typedDay, NOT untypedDay: the untyped rows are excluded by the
    // label filter, so the anchor must not claim coverage of that day.
    assertThat(asOf)
        .as("anchor must not advance past the last day with typed pitches")
        .isEqualTo(typedDay);
  }

  @Test
  void anchor_advances_when_untyped_day_also_has_typed_pitches() {
    LocalDate todayEt =
        ch.queryForObject("SELECT toDate(now('America/New_York'))", LocalDate.class);
    LocalDate day1 = todayEt.minusDays(4);
    LocalDate day2 = todayEt.minusDays(3);

    long pid = 1002L;

    // Day 1: typed.
    ch.update(
        "INSERT INTO pitches (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        900_001L,
        day1.toString(),
        1,
        1,
        pid,
        "FF",
        0,
        0);

    // Day 2: mix of typed + untyped (GUMBO partially typed the inning).
    ch.update(
        "INSERT INTO pitches_live (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        900_002L,
        day2.toString(),
        1,
        1,
        pid,
        "CH",
        0,
        0);
    ch.update(
        "INSERT INTO pitches_live (game_id, game_date, at_bat_index, pitch_number, pitcher_id,"
            + " pitch_type, balls, strikes) VALUES (?, toDate(?), ?, ?, ?, ?, ?, ?)",
        900_002L,
        day2.toString(),
        1,
        2,
        pid,
        "",
        0,
        1);

    LocalDate asOf = repo.refreshSnapshot(PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7);

    // Day 2 has at least one typed pitch, so the anchor should advance to it.
    assertThat(asOf)
        .as("anchor should advance to a day that has at least one typed pitch")
        .isEqualTo(day2);
  }

  private String readMigration() {
    Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (Path p = cwd; p != null; p = p.getParent()) {
      Path m =
          p.resolve(
              "backend/src/main/resources/db/clickhouse/V030__pitcher_pitchtype_prior_current.sql");
      if (java.nio.file.Files.isRegularFile(m)) {
        try {
          return Files.readString(m).replaceAll("(?m)^--.*$", "").trim();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    }
    throw new AssertionError("V030 migration not found from " + cwd);
  }
}
