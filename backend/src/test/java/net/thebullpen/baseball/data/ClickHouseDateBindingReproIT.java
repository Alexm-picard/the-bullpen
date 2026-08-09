package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reproduction of the clickhouse-jdbc Date-parameter gotcha (O7 upstream candidate).
 *
 * <p>When {@code java.sql.Date} is bound as a parameter in a WHERE clause, clickhouse-jdbc (0.7.x)
 * inlines it UNQUOTED as {@code 2026-06-04}, which ClickHouse evaluates as arithmetic ({@code 2026
 * - 6 - 4 = 2016}). The query silently returns wrong results rather than failing.
 *
 * <p>This project works around it by binding dates as ISO-8601 strings ({@code
 * toDate('2026-06-04')} literal or {@code toDate(?)} with a String bind). Six+ call sites carry
 * this workaround.
 *
 * <p>This test reproduces the issue against the current classpath driver and serves as the
 * regression test for an upstream fix. Run against 0.7.2 (current) and 0.9.8 (latest) to determine
 * whether the issue persists upstream.
 *
 * <p>To test against the latest driver, temporarily edit build.gradle.kts:
 *
 * <pre>
 *   implementation("com.clickhouse:clickhouse-jdbc:0.9.8:all")
 *   // remove clickhouse-http-client line
 * </pre>
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class ClickHouseDateBindingReproIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() throws java.sql.SQLException {
    var ds = new com.clickhouse.jdbc.ClickHouseDataSource(CH.getJdbcUrl());
    jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS date_repro ("
            + "  id UInt32,"
            + "  d Date"
            + ") ENGINE = MergeTree() ORDER BY id");
    jdbc.execute("TRUNCATE TABLE date_repro");
    jdbc.update("INSERT INTO date_repro VALUES (1, '2026-06-04')");
    jdbc.update("INSERT INTO date_repro VALUES (2, '2025-01-15')");
    jdbc.update("INSERT INTO date_repro VALUES (3, '2024-03-20')");
  }

  @Test
  void string_bind_works_correctly() {
    // The WORKAROUND this project uses: bind the date as a String via toDate(?).
    Long count =
        jdbc.queryForObject(
            "SELECT count() FROM date_repro WHERE d = toDate(?)", Long.class, "2026-06-04");
    assertThat(count).as("string bind via toDate(?) must find exactly 1 row").isEqualTo(1L);
  }

  @Test
  void java_sql_date_bind_returns_correct_results() {
    // THE BUG: java.sql.Date bound as a parameter. On 0.7.x this inlines UNQUOTED as 2026-06-04,
    // which ClickHouse arithmetic-evaluates to 2016 (the integer 2026 minus 6 minus 4), matching
    // zero rows. The query succeeds with count=0 rather than the correct count=1.
    //
    // If this test PASSES: the driver handles Date parameters correctly (the bug is fixed).
    // If this test FAILS (count=0 instead of 1): the bug reproduces - file/fix upstream.
    Date sqlDate = Date.valueOf(LocalDate.of(2026, 6, 4));
    Long count =
        jdbc.queryForObject("SELECT count() FROM date_repro WHERE d = ?", Long.class, sqlDate);
    assertThat(count)
        .as(
            "java.sql.Date bound as a parameter must find 1 row, not 0"
                + " (0 means the driver inlined the date unquoted and ClickHouse evaluated it as"
                + " arithmetic: 2026-06-04 -> 2026 - 6 - 4 = 2016)")
        .isEqualTo(1L);
  }

  @Test
  void local_date_bind_returns_correct_results() {
    // java.time.LocalDate as a bound parameter - may behave differently from java.sql.Date.
    LocalDate localDate = LocalDate.of(2026, 6, 4);
    Long count =
        jdbc.queryForObject("SELECT count() FROM date_repro WHERE d = ?", Long.class, localDate);
    assertThat(count).as("java.time.LocalDate bound as a parameter must find 1 row").isEqualTo(1L);
  }
}
