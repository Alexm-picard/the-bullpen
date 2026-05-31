package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Fast (no-container) unit coverage for {@link ClickHouseMigrationRunner}'s statement splitter and
 * for the migration-version-collision invariant. The checksum-drift + idempotency behaviour needs a
 * real ClickHouse and lives in {@code ClickHouseMigrationRunnerIT}.
 */
class ClickHouseMigrationRunnerTest {

  @Test
  void splitsOnSemicolonAndDropsLeadingComments() {
    String sql = "-- header comment\nCREATE TABLE a (x Int32);\n\nCREATE TABLE b (y Int32);\n";
    List<String> stmts = ClickHouseMigrationRunner.splitStatements(sql);
    assertThat(stmts).hasSize(2);
    assertThat(stmts.get(0)).startsWith("CREATE TABLE a");
    assertThat(stmts.get(1)).startsWith("CREATE TABLE b");
  }

  @Test
  void stripsInlineCommentEvenWhenItContainsASemicolon() {
    // A5 regression: the old line-leading-only strip left this inline comment in place; its ';'
    // would have split one statement into two.
    String sql = "CREATE TABLE a (\n  flag UInt8 -- 0/1; boolean\n);\n";
    List<String> stmts = ClickHouseMigrationRunner.splitStatements(sql);
    assertThat(stmts).hasSize(1);
    assertThat(stmts.get(0)).contains("flag UInt8").doesNotContain("boolean");
  }

  @Test
  void doesNotSplitOnSemicolonInsideStringLiteral() {
    String sql = "INSERT INTO t VALUES ('a;b');\n";
    List<String> stmts = ClickHouseMigrationRunner.splitStatements(sql);
    assertThat(stmts).hasSize(1);
    assertThat(stmts.get(0)).contains("'a;b'");
  }

  @Test
  void doesNotTreatDashesInsideStringAsComment() {
    String sql = "INSERT INTO t VALUES ('em--dash');\n";
    List<String> stmts = ClickHouseMigrationRunner.splitStatements(sql);
    assertThat(stmts).hasSize(1);
    assertThat(stmts.get(0)).contains("'em--dash'");
  }

  /**
   * The custom runner tracks by full filename, so today's duplicate version NUMBERS are harmless —
   * but the planned Flyway swap requires globally-unique versions. Grandfather exactly the two
   * known collisions; any NEW collision fails here so the situation can't regress before the
   * renumber lands. Renumber V012/V013 dupes as part of the Flyway migration (see
   * ClickHouseMigrationRunner javadoc).
   */
  @Test
  void noNewDuplicateMigrationVersionNumbers() throws Exception {
    Path dir =
        Paths.get(
            Objects.requireNonNull(
                    getClass().getClassLoader().getResource("db/clickhouse"),
                    "db/clickhouse missing from test classpath")
                .toURI());
    Pattern vnum = Pattern.compile("^(V\\d+)__.*\\.sql$");
    Map<String, Long> countsByVersion;
    try (Stream<Path> entries = Files.list(dir)) {
      countsByVersion =
          entries
              .map(p -> vnum.matcher(p.getFileName().toString()))
              .filter(Matcher::matches)
              .collect(Collectors.groupingBy(m -> m.group(1), Collectors.counting()));
    }
    TreeSet<String> duplicates =
        countsByVersion.entrySet().stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toCollection(TreeSet::new));
    assertThat(duplicates)
        .as("grandfathered dupes only — renumber these at the Flyway swap, add no new ones")
        .containsExactly("V012", "V013");
  }
}
