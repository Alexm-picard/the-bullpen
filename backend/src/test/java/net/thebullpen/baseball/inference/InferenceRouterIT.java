package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.thebullpen.baseball.inference.routing.Role;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.EnabledIf;

/**
 * End-to-end test of the {@link InferenceRouter} against real registered models. Registers the toy
 * batted-ball model as two versions (v0 = champion, v1 = synthetic challenger with identical
 * artifacts) + flips routing into SHADOW mode + verifies that a route() call:
 *
 * <ul>
 *   <li>returns the champion's prediction,
 *   <li>has {@link Role#CHAMPION} as servingRole,
 *   <li>carries a non-empty shadowResponse (the challenger ran in parallel),
 *   <li>shadowVersionId points at the challenger's row.
 * </ul>
 *
 * <p>Self-disables when the toy ONNX artifact is absent so a fresh clone has green builds (same
 * {@code @EnabledIf} pattern as {@code PredictBattedBallControllerTest}).
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
@EnabledIf(
    expression =
        "#{T(java.nio.file.Files).exists(T(java.nio.file.Path).of(systemProperties['user.dir']).getParent().resolve('training/artifacts/_toy/v0/model.onnx'))}")
class InferenceRouterIT {

  private static final String MODEL_NAME = "_toy_batted_ball";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-router-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-router-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
    // BUG-1b: ModelLoader now resolves each version's contract from its OWN snapshot's
    // feature_pipeline.json (no process-wide default property). registerToyVersion copies the
    // canonical toy contract into each snapshot as feature_pipeline.json, so the snapshot-resolved
    // contract IS the toy contract - no property override needed.
  }

  @Autowired private RegistryService registryService;
  @Autowired private RoutingService routingService;
  @Autowired private InferenceRouter router;
  @Autowired private ModelLoader modelLoader;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private CacheManager cacheManager;

  @TempDir Path tempSource;

  private Path realArtifactsDir;

  @BeforeEach
  void reset() throws Exception {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
    // Routing cache survives across tests in the shared JVM context — purge so prior
    // RoutingConfig entries don't bleed into the next test's findRouting() lookup.
    cacheManager
        .getCacheNames()
        .forEach(
            name -> {
              var c = cacheManager.getCache(name);
              if (c != null) {
                c.clear();
              }
            });
    realArtifactsDir =
        Path.of(System.getProperty("user.dir")).getParent().resolve("training/artifacts/_toy/v0");
  }

  @Test
  void route_in_shadow_mode_returns_champion_prediction_and_records_shadow_response()
      throws Exception {
    // Promote v0 BEFORE registering v1 so the rule-5 promotion gate's bootstrap exemption
    // applies (only one ever-registered version → gate skipped). Register v1 + promote to
    // SHADOW after. Mode stays SHADOW (default after first promotion); traffic_pct stays 0.
    long v0Id = registerToyVersion("v0");
    registryService.transitionStage(v0Id, Stage.CHAMPION);
    long v1Id = registerToyVersion("v1");
    registryService.transitionStage(v1Id, Stage.SHADOW);
    routingService.setChallenger(MODEL_NAME, v1Id);

    // Copy park_hr_rate.json into the snapshot dirs that SnapshotStorage didn't move (it only
    // tracks model.onnx + metadata.json + feature_pipeline.json). Without this the
    // ModelLoader's FeaturePipeline.load can't find park_hr_rate.json.
    copyAuxFiles(v0Id);
    copyAuxFiles(v1Id);

    RoutedPrediction<Float> result =
        router.route(
            MODEL_NAME,
            42L,
            versionId -> {
              try {
                return modelLoader.loadBattedBall(versionId).predict(95.0, 28.0, 92.0, "NYY", "R");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            () -> {
              throw new AssertionError("legacy fallback should NOT fire when routing is set");
            });

    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.servingVersionId()).isEqualTo(v0Id);
    assertThat(result.servingResponse()).isNotNull();
    assertThat(result.hasShadowRow())
        .as("SHADOW mode must produce a shadow response from the parallel challenger run")
        .isTrue();
    assertThat(result.shadowVersionId()).contains(v1Id);
    // v0 and v1 have identical artifacts → predictions match exactly. F1.4: the shadow is a
    // fire-and-forget future now, so join() drives the challenger run to completion.
    assertThat(result.shadowFuture().orElseThrow().join())
        .as("identical artifacts produce identical predictions")
        .isEqualTo(result.servingResponse());
  }

  @Test
  void route_with_no_challenger_returns_champion_only_no_shadow_row() throws Exception {
    long v0Id = registerToyVersion("v0");
    registryService.transitionStage(v0Id, Stage.CHAMPION);
    copyAuxFiles(v0Id);

    RoutedPrediction<Float> result =
        router.route(
            MODEL_NAME,
            42L,
            versionId -> {
              try {
                return modelLoader.loadBattedBall(versionId).predict(95.0, 28.0, 92.0, "NYY", "R");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            () -> {
              throw new AssertionError("legacy fallback should NOT fire when routing is set");
            });

    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.servingVersionId()).isEqualTo(v0Id);
    assertThat(result.hasShadowRow()).isFalse();
  }

  @Test
  void route_with_no_registered_model_falls_back_to_legacy_supplier() {
    // No registration → no routing config → router invokes the legacy supplier.
    RoutedPrediction<Float> result =
        router.route(
            MODEL_NAME,
            42L,
            versionId -> {
              throw new AssertionError(
                  "versioned predictor should NOT fire when no routing exists");
            },
            () -> 0.42f);

    assertThat(result.servingRole()).isEqualTo(Role.CHAMPION);
    assertThat(result.servingVersionId()).isEqualTo(-1L);
    assertThat(result.servingResponse()).isEqualTo(0.42f);
    assertThat(result.hasShadowRow()).isFalse();
  }

  @Test
  void route_in_ab_mode_bucketed_to_challenger_serves_challenger() throws Exception {
    long v0Id = registerToyVersion("v0");
    registryService.transitionStage(v0Id, Stage.CHAMPION);
    long v1Id = registerToyVersion("v1");
    registryService.transitionStage(v1Id, Stage.SHADOW);
    routingService.setChallenger(MODEL_NAME, v1Id);
    routingService.setMode(MODEL_NAME, RoutingMode.AB);
    routingService.setTrafficPct(MODEL_NAME, 100.0); // every request → challenger
    copyAuxFiles(v0Id);
    copyAuxFiles(v1Id);

    RoutedPrediction<Float> result =
        router.route(
            MODEL_NAME,
            42L,
            versionId -> {
              try {
                return modelLoader.loadBattedBall(versionId).predict(95.0, 28.0, 92.0, "NYY", "R");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            () -> {
              throw new AssertionError("legacy fallback should NOT fire");
            });

    assertThat(result.servingRole()).isEqualTo(Role.CHALLENGER);
    assertThat(result.servingVersionId()).isEqualTo(v1Id);
    // AB-routed-to-challenger: no separate shadow row (challenger IS the served prediction).
    assertThat(result.hasShadowRow()).isFalse();
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Register a synthetic toy version using the real on-disk artifacts. Each version's source dir is
   * per-test-temp so SnapshotStorage's placeArtifacts can copy without collision.
   */
  private long registerToyVersion(String version) throws Exception {
    Path versionSourceDir = Files.createDirectories(tempSource.resolve(version));
    Files.copy(
        realArtifactsDir.resolve("model.onnx"),
        versionSourceDir.resolve("model.onnx"),
        StandardCopyOption.REPLACE_EXISTING);
    Files.copy(
        realArtifactsDir.resolve("metadata.json"),
        versionSourceDir.resolve("metadata.json"),
        StandardCopyOption.REPLACE_EXISTING);
    // The toy contract is the same regardless of version — the toy pipeline doesn't vary by
    // version in this test. We use the project's canonical toy contract verbatim.
    Path contractSource =
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("contracts/feature_pipeline_toy.json");
    Files.copy(
        contractSource,
        versionSourceDir.resolve("feature_pipeline.json"),
        StandardCopyOption.REPLACE_EXISTING);
    RegisterRequest req =
        new RegisterRequest(
            MODEL_NAME,
            version,
            versionSourceDir.resolve("model.onnx").toString(),
            versionSourceDir.resolve("metadata.json").toString(),
            versionSourceDir.resolve("feature_pipeline.json").toString(),
            "toy-train-h-" + version,
            "[2024-01-01,2024-12-31]",
            "{\"auc\":0.987}",
            Instant.now(),
            "router-it",
            "registered by InferenceRouterIT");
    ModelVersion mv = registryService.register(req);
    return mv.id();
  }

  /**
   * Copy the auxiliary {@code park_hr_rate.json} into the snapshot dir post-registration. The
   * registry's {@code placeArtifacts} only relocates the 3 canonical-named files; auxiliary lookups
   * specific to the toy pipeline land here.
   */
  private void copyAuxFiles(long versionId) throws Exception {
    ModelVersion mv = registryService.getById(versionId).orElseThrow();
    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    Files.copy(
        realArtifactsDir.resolve("park_hr_rate.json"),
        snapshotDir.resolve("park_hr_rate.json"),
        StandardCopyOption.REPLACE_EXISTING);
  }

  /** Visible to assist debugging — unused for now but useful when a test fails. */
  @SuppressWarnings("unused")
  private List<String[]> dumpRoutingRows() {
    return jdbc.query(
        "SELECT model_name, champion_version_id || '', challenger_version_id || '', mode"
            + " FROM model_routing",
        (rs, n) ->
            new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
  }
}
