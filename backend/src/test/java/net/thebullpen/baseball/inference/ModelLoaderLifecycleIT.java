package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The eviction-close discipline under test for the first time (task #87): before this file, the
 * removalListener path - the mechanism the ModelLoader javadoc calls the discipline - had zero
 * coverage. Real registry (temp SQLite), real ONNX fixture sessions, a purpose-built small-cache
 * {@code ModelLoader} per test (constructed directly - the Spring bean's cache is sized for prod).
 *
 * <p>Eviction close is ASYNCHRONOUS (Caffeine notifies on its executor), so retirement is polled
 * with a bounded loop, never asserted immediately.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class ModelLoaderLifecycleIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loader-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loader-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_pitchtype.json");
  private static final String MODEL_NAME = "pitch_type_pre";

  @Autowired private RegistryService registryService;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path sourceDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_versions");
  }

  @Test
  void invalidate_retires_the_bundle_and_a_stale_reference_refuses_typed() throws Exception {
    long v1 = registerVersion("v1").id();
    ModelLoader loader = new ModelLoader(registryService, 1);
    try {
      LoadedPitchTypeModel b1 = loader.loadPitchType(v1);
      Map<String, Double> probs = b1.predict(sampleRequest()); // anti-vacuity: live bundle serves
      assertThat(probs).hasSize(7);

      loader.invalidate(v1);
      awaitRetired(b1);
      assertThatThrownBy(() -> b1.predict(sampleRequest()))
          .isInstanceOf(ModelUnavailableException.class)
          .hasMessageContaining("retired");

      // A reload after retirement serves fresh - the stale reference is the only casualty.
      LoadedPitchTypeModel fresh = loader.loadPitchType(v1);
      assertThat(fresh.isRetired()).isFalse();
      assertThat(fresh.predict(sampleRequest())).hasSize(7);
    } finally {
      loader.close();
    }
  }

  @Test
  void size_eviction_closes_a_bundle_without_breaking_the_survivors() throws Exception {
    // cacheSize=1 -> the shared pitch-type cache caps at 2, so three loads force an eviction.
    long v1 = registerVersion("v1").id();
    long v2 = registerVersion("v2").id();
    long v3 = registerVersion("v3").id();
    ModelLoader loader = new ModelLoader(registryService, 1);
    try {
      LoadedPitchTypeModel[] bundles = {
        loader.loadPitchType(v1), loader.loadPitchType(v2), loader.loadPitchType(v3)
      };
      // Which entry W-TinyLFU evicts is not contract; that SOME bundle retires (and refuses
      // typed rather than crashing native) is.
      LoadedPitchTypeModel evicted = awaitAnyRetired(bundles);
      assertThatThrownBy(() -> evicted.predict(sampleRequest()))
          .isInstanceOf(ModelUnavailableException.class)
          .hasMessageContaining("retired");
      // getLive: a retired bundle never escapes the loader - reloading its id serves live.
      LoadedPitchTypeModel reloaded = loader.loadPitchType(evicted.versionId());
      assertThat(reloaded.isRetired()).isFalse();
      assertThat(reloaded.predict(sampleRequest())).hasSize(7);
    } finally {
      loader.close();
    }
  }

  @Test
  void shutdown_close_retires_every_cached_bundle_without_waiting_on_the_listener()
      throws Exception {
    long v1 = registerVersion("v1").id();
    ModelLoader loader = new ModelLoader(registryService, 1);
    LoadedPitchTypeModel b1 = loader.loadPitchType(v1);
    assertThat(b1.predict(sampleRequest())).hasSize(7);

    // Direct close path: retirement is SYNCHRONOUS here (no async-listener poll needed) - that
    // is the point of the @PreDestroy rewrite; the listener alone gave no such guarantee.
    loader.close();
    assertThat(b1.isRetired()).isTrue();
    assertThatThrownBy(() -> b1.predict(sampleRequest()))
        .isInstanceOf(ModelUnavailableException.class)
        .hasMessageContaining("retired");
  }

  @Test
  void corrupt_calibrator_fails_the_load_typed() throws Exception {
    ModelVersion mv = registerVersion("v-corrupt");
    Path calibrator = Path.of(mv.artifactPath()).getParent().resolve("calibrator.json");
    Files.writeString(calibrator, "{not json");
    ModelLoader loader = new ModelLoader(registryService, 1);
    try {
      assertThatThrownBy(() -> loader.loadPitchType(mv.id()))
          .isInstanceOf(ModelUnavailableException.class)
          .hasMessageContaining("failed to load pitch-type");
    } finally {
      loader.close();
    }
  }

  // --- helpers ----------------------------------------------------------

  private static void awaitRetired(LoadedPitchTypeModel bundle) throws InterruptedException {
    for (int i = 0; i < 200 && !bundle.isRetired(); i++) {
      Thread.sleep(25);
    }
    assertThat(bundle.isRetired())
        .as("bundle should retire after eviction (async removalListener)")
        .isTrue();
  }

  private static LoadedPitchTypeModel awaitAnyRetired(LoadedPitchTypeModel[] bundles)
      throws InterruptedException {
    for (int i = 0; i < 200; i++) {
      for (LoadedPitchTypeModel b : bundles) {
        if (b.isRetired()) {
          return b;
        }
      }
      Thread.sleep(25);
    }
    throw new AssertionError("no bundle retired after a size-forcing third load");
  }

  private static FeaturePipelinePitchType.Request sampleRequest() {
    return new FeaturePipelinePitchType.Request(
        1, 1, 1, 3, 0, "R", "R", "NYY", 1.0, 5.0, 1.0, 0.35, 0.2, 0.1, 0.15, 0.1, 0.08, 0.02, 0.4,
        250, 0, 3, 0, 12);
  }

  /** Same fixture shape as {@code PredictPitchTypeRoutingIT}: real ONNX, real contract. */
  private ModelVersion registerVersion(String version) throws Exception {
    Path src = Files.createDirectories(sourceDir.resolve(MODEL_NAME + "-" + version));
    URL onnx = getClass().getResource("/onnx/pitch_type_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch-type fixture missing").toURI()),
        src.resolve("model.onnx"));
    Files.writeString(
        src.resolve("metadata.json"),
        "{\"model_name\":\""
            + MODEL_NAME
            + "\",\"model_kind\":\"pitch_type\",\"model_version\":\""
            + version
            + "\",\"calibrator\":{\"path\":\"calibrator.json\"}}");
    Files.copy(CONTRACT, src.resolve("feature_pipeline.json"));
    Files.writeString(
        src.resolve("calibrator.json"),
        "{\"kind\":\"temperature\",\"class_labels\":"
            + "[\"FF\",\"SI\",\"FC\",\"SL\",\"CU\",\"CH\",\"OFF\"],\"temperature\":1.0}");
    Files.writeString(
        src.resolve("park_id_mapping.json"), "{\"park_id\":{\"NYY\":0},\"missing_value\":-1}");

    return registryService.register(
        new RegisterRequest(
            MODEL_NAME,
            version,
            src.resolve("model.onnx").toString(),
            src.resolve("metadata.json").toString(),
            src.resolve("feature_pipeline.json").toString(),
            "train-h-lifecycle-" + version,
            "[2015-01-01,2023-12-31]",
            "{\"ece\":0.0036}",
            Instant.now(),
            "lifecycle-it",
            "registered by ModelLoaderLifecycleIT"));
  }
}
