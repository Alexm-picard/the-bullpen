package net.thebullpen.baseball.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Source-level guard: no bare {@code today()} in SQL string literals under {@code data/}. The
 * correct ClickHouse date basis is {@code toDate(now('America/New_York'))} - bare {@code today()}
 * resolves in the server's configured timezone, which silently diverges between 20:00 and 23:59 ET
 * if the container runs UTC.
 *
 * <p>As of #378 no bare {@code today()} survives in production SQL; this test locks that invariant.
 */
class SqlDisciplineTest {

  private static final Path DATA_PACKAGE = Path.of("src/main/java/net/thebullpen/baseball/data");

  // Matches today() NOT preceded by a word char (avoids matching `now('...')` in comments about
  // today()). Deliberately broad: any string containing today() in a .java file under data/ is
  // suspect. False positives in comments are acceptable - they keep the grep tight.
  private static final Pattern BARE_TODAY =
      Pattern.compile("\"[^\"]*\\btoday\\(\\)[^\"]*\"", Pattern.CASE_INSENSITIVE);

  @Test
  void no_bare_today_in_sql_string_literals() throws IOException {
    assertThat(DATA_PACKAGE).isDirectory();

    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(DATA_PACKAGE)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  List<String> lines = Files.readAllLines(path);
                  for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (BARE_TODAY.matcher(line).find()) {
                      violations.add(path.getFileName() + ":" + (i + 1) + " -> " + line.trim());
                    }
                  }
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }

    assertThat(violations)
        .as(
            "SQL string literals must use toDate(now('America/New_York')), never bare today()"
                + " - see #382 item 1")
        .isEmpty();
  }
}
