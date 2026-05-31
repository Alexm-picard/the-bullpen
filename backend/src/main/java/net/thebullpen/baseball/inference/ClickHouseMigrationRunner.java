package net.thebullpen.baseball.inference;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the {@code backend/src/main/resources/db/clickhouse/V*.sql} migrations against the
 * ClickHouse DataSource on boot (Phase 1.7). Mirrors the Python {@code migrations.py} that the
 * training side uses for local dev.
 *
 * <p>Tracks applied versions in a {@code _schema_migrations} ReplacingMergeTree so re-runs are
 * no-ops, and (A5 audit-remediation) validates the recorded SHA-256 on every re-run — editing an
 * already-applied migration now fails loud instead of silently skipping the changed body.
 *
 * <p>Versions are keyed by full filename, so the two grandfathered duplicate version NUMBERS (V012,
 * V013 each appear on two distinct files) apply correctly here. They must be renumbered when this
 * is replaced with the real ClickHouse Flyway runner (Flyway enforces globally-unique versions);
 * {@code ClickHouseMigrationRunnerTest#noNewDuplicateMigrationVersionNumbers} guards against
 * introducing any NEW collisions in the meantime.
 */
public class ClickHouseMigrationRunner {

  private static final Logger log = LoggerFactory.getLogger(ClickHouseMigrationRunner.class);

  private final DataSource clickhouse;

  public ClickHouseMigrationRunner(DataSource clickhouse) {
    this.clickhouse = clickhouse;
  }

  @PostConstruct
  public void apply() throws SQLException {
    try (Connection conn = clickhouse.getConnection()) {
      ensureTrackingTable(conn);
      Map<String, String> applied = appliedChecksums(conn);
      for (Path script : sortedScripts()) {
        Path fileName = script.getFileName();
        if (fileName == null) {
          throw new IllegalStateException("migration script path has no file name: " + script);
        }
        String version = fileName.toString().replace(".sql", "");
        String sql = readResource(script);
        String checksum = sha256Hex(sql);
        if (applied.containsKey(version)) {
          // Already applied — verify the file hasn't drifted since. Migrations are
          // immutable once applied; a changed body means someone edited an applied
          // migration (forbidden — add a new V*.sql instead). The checksum was recorded
          // but never compared before this (A5 audit-remediation); now we fail loud
          // rather than silently skip the new content. A blank stored checksum is a
          // grandfathered row, so we skip the comparison for it.
          String priorChecksum = applied.get(version);
          if (priorChecksum != null
              && !priorChecksum.isBlank()
              && !priorChecksum.equals(checksum)) {
            throw new IllegalStateException(
                "clickhouse migration checksum drift for "
                    + version
                    + ": applied checksum starts "
                    + priorChecksum.substring(0, Math.min(12, priorChecksum.length()))
                    + ", current file checksum starts "
                    + checksum.substring(0, 12)
                    + " — migrations are immutable once applied; add a new V*.sql instead of"
                    + " editing this one.");
          }
          log.debug("clickhouse migration already applied version={}", version);
          continue;
        }
        for (String statement : splitStatements(sql)) {
          try (Statement st = conn.createStatement()) {
            st.execute(statement);
          }
        }
        try (PreparedStatement ps =
            conn.prepareStatement(
                "INSERT INTO _schema_migrations (version, checksum) VALUES (?, ?)")) {
          ps.setString(1, version);
          ps.setString(2, checksum);
          ps.executeUpdate();
        }
        log.info(
            "clickhouse migration applied version={} checksum={}",
            version,
            checksum.substring(0, 12));
      }
    }
  }

  private static void ensureTrackingTable(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS _schema_migrations ("
              + "version String, checksum String, applied_at DateTime DEFAULT now()"
              + ") ENGINE = ReplacingMergeTree(applied_at) ORDER BY version");
    }
  }

  /** version → checksum for every applied migration (newest row wins via {@code FINAL}). */
  private static Map<String, String> appliedChecksums(Connection conn) throws SQLException {
    Map<String, String> out = new HashMap<>();
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT version, checksum FROM _schema_migrations FINAL")) {
      while (rs.next()) {
        out.put(rs.getString(1), rs.getString(2));
      }
    }
    return out;
  }

  private static List<Path> sortedScripts() {
    URL dirUrl =
        Objects.requireNonNull(
            ClickHouseMigrationRunner.class.getClassLoader().getResource("db/clickhouse"),
            "db/clickhouse missing from classpath");
    try {
      Path dir = Paths.get(dirUrl.toURI());
      try (Stream<Path> entries = Files.list(dir)) {
        return entries
            .filter(
                p -> p.getFileName().toString().startsWith("V") && p.toString().endsWith(".sql"))
            .sorted()
            .toList();
      }
    } catch (Exception ex) {
      throw new UncheckedIOException(new IOException("cannot enumerate db/clickhouse", ex));
    }
  }

  private static String readResource(Path script) {
    try {
      return Files.readString(script, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /**
   * Split a migration file into individual statements on {@code ;}.
   *
   * <p>Package-private so it can be unit-tested without a ClickHouse container.
   *
   * <p>A5 hardening: strips {@code --} line comments before splitting, recognising {@code --} even
   * mid-line (e.g. a trailing {@code column UInt8, -- note}) but NOT when it sits inside a
   * single-quoted string literal. The old version only dropped lines that *started* with {@code
   * --}, leaving inline comments in place — harmless until a comment contained a {@code ;}, which
   * would have split a statement in two. The {@code ;} split is likewise quote-aware so a semicolon
   * inside a string default can't mis-split. Block ({@code /* *\/}) comments are not used in these
   * migrations and are not handled.
   */
  static List<String> splitStatements(String sql) {
    StringBuilder cleaned = new StringBuilder(sql.length());
    for (String line : sql.split("\n", -1)) {
      cleaned.append(stripInlineComment(line)).append('\n');
    }
    List<String> out = new ArrayList<>();
    boolean inString = false;
    StringBuilder current = new StringBuilder();
    String body = cleaned.toString();
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '\'') {
        inString = !inString;
      }
      if (c == ';' && !inString) {
        addIfNotBlank(out, current);
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    addIfNotBlank(out, current);
    return out;
  }

  /** Truncate a line at the first {@code --} that is not inside a single-quoted string. */
  private static String stripInlineComment(String line) {
    boolean inString = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'') {
        inString = !inString;
      } else if (c == '-' && !inString && i + 1 < line.length() && line.charAt(i + 1) == '-') {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static void addIfNotBlank(List<String> out, StringBuilder sb) {
    String trimmed = sb.toString().trim();
    if (!trimmed.isEmpty()) {
      out.add(trimmed);
    }
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
