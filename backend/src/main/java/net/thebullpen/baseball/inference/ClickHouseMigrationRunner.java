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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * no-ops. Phase 3+ replaces this with the real ClickHouse Flyway runner once we have a stronger
 * reason to depend on it.
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
      Set<String> applied = appliedVersions(conn);
      for (Path script : sortedScripts()) {
        Path fileName = script.getFileName();
        if (fileName == null) {
          throw new IllegalStateException("migration script path has no file name: " + script);
        }
        String version = fileName.toString().replace(".sql", "");
        if (applied.contains(version)) {
          log.debug("clickhouse migration already applied version={}", version);
          continue;
        }
        String sql = readResource(script);
        String checksum = sha256Hex(sql);
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

  private static Set<String> appliedVersions(Connection conn) throws SQLException {
    Set<String> out = new HashSet<>();
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT version FROM _schema_migrations FINAL")) {
      while (rs.next()) {
        out.add(rs.getString(1));
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

  private static List<String> splitStatements(String sql) {
    StringBuilder noComments = new StringBuilder();
    for (String line : sql.split("\n")) {
      if (!line.trim().startsWith("--")) {
        noComments.append(line).append('\n');
      }
    }
    List<String> out = new ArrayList<>();
    for (String chunk : noComments.toString().split(";")) {
      String trimmed = chunk.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
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
