package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
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
    try {
      LoadedPitchTypeModel b1 = loader.loadPitchType(v1);
      assertThat(b1.predict(sampleRequest())).hasSize(7);

      // Direct close path: retirement is SYNCHRONOUS here (no async-listener poll needed) - that
      // is the point of the @PreDestroy rewrite; the listener alone gave no such guarantee.
      loader.close();
      assertThat(b1.isRetired()).isTrue();
      // The DETERMINISTIC pin (registry-guard F2): a did-it-retire check races the async
      // listener, which wins ~90% of the time even on the reverted implementation. The claiming
      // THREAD cannot lie: the direct-close sweep claims on this thread; a listener-driven close
      // claims on a Caffeine executor thread.
      assertThat(b1.closedBy())
          .as("the @PreDestroy sweep itself must close, not the async removal listener")
          .isSameAs(Thread.currentThread());
      assertThatThrownBy(() -> b1.predict(sampleRequest()))
          .isInstanceOf(ModelUnavailableException.class)
          .hasMessageContaining("retired");
      // And the loader itself refuses further loads - a load racing the shutdown sweep would
      // otherwise repopulate the cache with a session nothing ever closes.
      assertThatThrownBy(() -> loader.loadPitchType(v1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("closed");
    } finally {
      loader.close(); // idempotent; keeps the leak-on-assertion-failure symmetry of the other tests
    }
  }

  /**
   * The 2x sizing's BITING test (registry-guard F3): with cacheSize=1 the shared pitch-type cache
   * caps at 2 because two registry families share it - both cached bundles must stay live. A
   * single-budget regression (cap 1) evicts v1 on v2's load, and 300ms is orders of magnitude above
   * Caffeine's dispatch latency, so the eviction would be observed.
   */
  @Test
  void shared_pitch_type_cache_holds_both_families_budgets() throws Exception {
    long v1 = registerVersion("v1").id();
    long v2 = registerVersion("v2").id();
    ModelLoader loader = new ModelLoader(registryService, 1);
    try {
      LoadedPitchTypeModel b1 = loader.loadPitchType(v1);
      LoadedPitchTypeModel b2 = loader.loadPitchType(v2);
      Thread.sleep(300);
      assertThat(b1.isRetired()).as("cap must fit both families' bundles").isFalse();
      assertThat(b2.isRetired()).isFalse();
    } finally {
      loader.close();
    }
  }

  /**
   * The getLive recheck's BITING test: retire a bundle while its cache mapping still exists (a
   * direct close, no eviction), then load the same id - a plain {@code cache.get} would hand back
   * the retired instance; the recheck must reload fresh.
   */
  @Test
  void getLive_reloads_a_bundle_retired_while_still_cached() throws Exception {
    long v1 = registerVersion("v1").id();
    ModelLoader loader = new ModelLoader(registryService, 1);
    try {
      LoadedPitchTypeModel b1 = loader.loadPitchType(v1);
      b1.close(); // retired, but the mapping is untouched - no eviction happened

      LoadedPitchTypeModel fresh = loader.loadPitchType(v1);
      assertThat(fresh).isNotSameAs(b1);
      assertThat(fresh.isRetired()).isFalse();
      assertThat(fresh.predict(sampleRequest())).hasSize(7);
    } finally {
      loader.close();
    }
  }

  /**
   * Pins fail-loud-typed on a corrupt calibrator for the pitch-type path - which was ALREADY
   * session-last on main, so this does NOT assert the three task-#87 reorders (LoadedAllParksModel,
   * LoadedPitchModel pre/post). Those are pinned by the SESSION LAST comments at each site and by
   * review; asserting "no session was created" would need live-session introspection the wrappers
   * deliberately do not expose.
   */
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
        // count/state: balls, strikes, outs, inning, baseState, stand, pThrows, parkId
        1,
        1,
        1,
        3,
        0,
        "R",
        "R",
        "NYY",
        // TTO: timesThroughOrder, atBatNumberInGame, timesFacedToday
        1.0,
        5.0,
        1.0,
        // ARS mix: arsFf, arsSi, arsFc, arsSl, arsCu, arsCh, arsOff, arsFfByCount, pitcherPriorN
        0.35,
        0.2,
        0.1,
        0.15,
        0.1,
        0.08,
        0.02,
        0.4,
        250,
        // SEQ: prev1PitchTypeInt, prev2PitchTypeInt, prev1Missing, pitchesIntoOuting
        0,
        3,
        0,
        12);
  }

  private ModelVersion registerVersion(String version) throws Exception {
    Path src =
        PitchTypeSnapshotFixtures.writeStandardSnapshot(
            sourceDir.resolve(MODEL_NAME + "-" + version), MODEL_NAME, version);

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
