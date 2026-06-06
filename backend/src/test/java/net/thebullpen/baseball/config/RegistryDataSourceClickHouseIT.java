package net.thebullpen.baseball.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
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
 * Regression guard for the registry/ClickHouse datasource collision that {@link
 * RegistryDataSourceConfig} fixes.
 *
 * <p>The registry repositories inject an unqualified {@link
 * org.springframework.jdbc.core.JdbcTemplate}. When ClickHouse is enabled, its {@code
 * clickhouseDataSource} bean made Spring Boot's {@code DataSourceAutoConfiguration} back off the
 * SQLite auto-config, so the unqualified {@code JdbcTemplate} + {@code @Transactional} rebound to
 * ClickHouse and every {@code model_versions} query 500'd with {@code UNKNOWN_TABLE} (Code 60). The
 * existing registry ITs only ever booted a single datasource (SQLite), so CI never saw the
 * ambiguity. This boots BOTH datasources (a real ClickHouse container + SQLite) and registers a
 * model end-to-end, exercising the {@code @Transactional} INSERT + {@code findByNameAndVersion}
 * paths that broke; {@link RegistryDataSourceConfig}'s {@code @Primary} SQLite datasource is what
 * makes it pass.
 *
 * <p>Docker-gated like the other ClickHouse ITs.
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
class RegistryDataSourceClickHouseIT {

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    // Enabling ClickHouse is the whole point: its clickhouseDataSource bean is what made Boot back
    // off the SQLite auto-config and rebind the registry's unqualified JdbcTemplate to ClickHouse.
    registry.add("bullpen.clickhouse.enabled", () -> "true");
    registry.add("bullpen.clickhouse.url", CH::getJdbcUrl);
    registry.add("bullpen.clickhouse.user", CH::getUsername);
    registry.add("bullpen.clickhouse.password", CH::getPassword);

    String url =
        "jdbc:sqlite:"
            + Path.of(
                System.getProperty("java.io.tmpdir"),
                "bullpen-registry-ds-it-" + UUID.randomUUID() + ".sqlite");
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> "it-admin:it-password");
    // Keep SnapshotStorage's artifact copies out of ./data/models.
    registry.add(
        "bullpen.snapshot.local-base-path",
        () ->
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "bullpen-registry-ds-it-snap-" + UUID.randomUUID())
                .toString());
  }

  @Autowired private RegistryService service;

  @Autowired
  @Qualifier("registryDataSource")
  private DataSource registryDs;

  @Autowired
  @Qualifier("clickhouseDataSource")
  private DataSource clickhouseDs;

  @TempDir Path artifactDir;

  @Test
  void register_routes_to_sqlite_when_clickhouse_is_also_present() throws Exception {
    // Both datasources are wired: the @Primary SQLite registry datasource and the analytical
    // ClickHouse one (the bug trigger). The registry must bind to SQLite.
    assertNotNull(clickhouseDs, "clickhouse datasource should be present (the bug trigger)");
    try (var conn = registryDs.getConnection()) {
      assertTrue(
          conn.getMetaData().getURL().startsWith("jdbc:sqlite"),
          "the registry's @Primary datasource must be SQLite, not ClickHouse");
    }

    RegisterRequest req =
        new RegisterRequest(
            "ds_it",
            "v1",
            write("ds_it-v1-model.onnx", "stub").toString(),
            write("ds_it-v1-meta.json", "{}").toString(),
            write(
                    "ds_it-v1-pipeline.json",
                    "{\"model_name\":\"ds_it\",\"pipeline_version\":\"1.0.0\","
                        + "\"feature_order\":[\"a\",\"b\"],\"schema_hash\":\"\"}")
                .toString(),
            "train-hash",
            "[2024-01-01,2024-12-31]",
            "{\"brier\":0.18}",
            Instant.now(),
            "ds-it",
            "registry/clickhouse datasource-collision regression");

    // Before RegistryDataSourceConfig this @Transactional register (findByNameAndVersion + INSERT)
    // ran against ClickHouse -> UNKNOWN_TABLE (Code 60). Now it lands a CANDIDATE row in SQLite.
    ModelVersion inserted = service.register(req);
    assertEquals("ds_it", inserted.modelName());
    assertEquals(Stage.CANDIDATE, inserted.stage());

    // Re-registering is idempotent: it returns the existing row via findByNameAndVersion - the
    // exact read in the 500 stack trace. Same id proves the read path also resolved to SQLite.
    ModelVersion existing = service.register(req);
    assertEquals(inserted.id(), existing.id());
  }

  private Path write(String name, String content) throws Exception {
    Path p = artifactDir.resolve(name);
    Files.writeString(p, content);
    return p;
  }
}
