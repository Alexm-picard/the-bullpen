package net.thebullpen.baseball.inference.routing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import net.thebullpen.baseball.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the fail-the-boot half of the task-#94 integrity check actually fires: a REAL application
 * boot against a pre-seeded corrupt {@code registry.sqlite} must refuse to start. {@code
 * RoutingServiceIT} pins the bean's detection logic by calling it directly; this test pins the
 * framework contract the javadoc relies on (a {@code SmartInitializingSingleton} throw aborts
 * context refresh) - this arc's history includes checks that could not fire, so the abort is
 * proven, not narrated.
 *
 * <p>Shape: boot once against a fresh temp SQLite (Flyway migrates to head; the clean boot doubles
 * as the anti-vacuity arm - an always-throwing check would fail HERE), close it, corrupt the file
 * OUT-OF-BAND with raw JDBC (a legal champion + routing row, then the stage flipped under it - the
 * exact hand-edit scenario the check exists for; the routing-side V020 triggers make a
 * directly-corrupt INSERT impossible), and assert the second boot dies with the integrity message.
 * CH-free (clickhouse disabled), so it runs on every CI pass, not just docker-gated.
 */
@EnabledIf("toyModelPresent")
class RoutingIntegrityBootIT {

  /**
   * Same guard as {@code ApiPairTwoInstanceIT}: the api context cannot start without the on-disk
   * toy model ({@code ToyBattedBallInference.@PostConstruct} throws), which is gitignored. CI
   * generates it; a fresh clone skips this class rather than failing on the CLEAN boot - which
   * would otherwise read as the integrity check being broken when it is the fixture that is
   * missing.
   */
  static boolean toyModelPresent() {
    return Files.exists(
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("training/artifacts/_toy/v0/model.onnx"));
  }

  @Test
  void boot_refuses_a_pre_seeded_corrupt_routing_row() throws Exception {
    Path dbFile =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-integrity-boot-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbFile + "?foreign_keys=true&busy_timeout=5000";

    // Clean boot: migrates the schema and proves the check passes an empty table.
    boot(url).close();

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
        Statement s = c.createStatement()) {
      s.executeUpdate(
          "INSERT INTO model_versions (model_name, version, artifact_path, metadata_path,"
              + " training_data_hash, training_data_window, feature_schema_hash, eval_metrics,"
              + " trained_at, stage) VALUES ('boot_it_model', 'v1', '/x/model.onnx',"
              + " '/x/metadata.json', 'h', '[2024-01-01,2024-12-31]', 'fh', '{}',"
              + " '2026-01-01 00:00:00', 'champion')");
      s.executeUpdate(
          "INSERT INTO model_routing (model_name, champion_version_id)"
              + " VALUES ('boot_it_model', last_insert_rowid())");
      s.executeUpdate(
          "UPDATE model_versions SET stage = 'archived' WHERE model_name = 'boot_it_model'");
    }

    // hasStackTraceContaining rather than a typed assertion: the IllegalStateException may reach
    // us wrapped by the Spring startup machinery, and the contract under test is "the boot dies
    // naming the violation", not the wrapper type.
    assertThatThrownBy(() -> boot(url))
        .hasStackTraceContaining("routing integrity")
        .hasStackTraceContaining("boot_it_model");
  }

  private static ConfigurableApplicationContext boot(String url) {
    return new SpringApplicationBuilder(Application.class)
        .run(
            "--spring.profiles.active=api",
            "--server.port=0",
            "--bullpen.clickhouse.enabled=false",
            "--bullpen.ratelimit.enabled=false",
            "--spring.datasource.url=" + url,
            "--spring.flyway.url=" + url,
            "--bullpen.admin.basicauth=boot-it:boot-it-pass",
            "--bullpen.snapshot.local-base-path="
                + Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "bullpen-integrity-boot-snapshots-" + UUID.randomUUID()));
  }
}
