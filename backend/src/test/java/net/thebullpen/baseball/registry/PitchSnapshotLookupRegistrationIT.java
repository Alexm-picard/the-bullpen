package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.thebullpen.baseball.inference.Head;
import net.thebullpen.baseball.inference.LoadedPitchModel;
import net.thebullpen.baseball.inference.ModelLoader;
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
 * W4a (the BUG-1c-for-pitch blocker): registering a {@code pitch_outcome_pre} snapshot must copy
 * the Tier-2 lookups the serving pipeline resolves from the snapshot dir, so that {@link
 * ModelLoader#loadPitchPre} succeeds without a missing-file failure.
 *
 * <p>The lookups the pre pipeline needs ({@code park_id_mapping.json}, {@code pitcher_te.json},
 * {@code batter_te.json}) are DECLARED as {@code lookup_path} entries in the committed contract's
 * {@code preprocess} block, so {@link RegistryService} drives the copy off the pipeline itself - no
 * per-model-name branch. This IT is the registration-side mirror of {@code
 * PredictPitchRoutingIT.placeLookups}: there the test placed the lookups by hand post-registration;
 * here the registry must place them itself, and a snapshot that ends up missing a required lookup
 * fails LOUD rather than serving skewed features.
 *
 * <p>Two cases:
 *
 * <ul>
 *   <li>WITH all declared lookups present beside the model -> register copies them into the
 *       snapshot and {@code loadPitchPre} loads the bundle.
 *   <li>WITHOUT a required lookup (batter_te.json absent from the source dir) -> register copies
 *       only the lookups it found and {@code loadPitchPre} fails loud (the snapshot has no
 *       batter_te.json), so a bad export never serves a prediction. Registration stays decoupled
 *       from how the caller stages files - it copies what is co-located, the loader is the gate on
 *       completeness.
 * </ul>
 *
 * <p>Real ORT-Java session loading the committed pitch fixture (no mocked ONNX, per the testing
 * posture). Isolated tmp SQLite + tmp snapshot base, same pattern as {@link RegistryServiceIT}; R2
 * is intentionally NOT configured so the retention sweep no-ops.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class PitchSnapshotLookupRegistrationIT {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT = REPO_ROOT.resolve("contracts/feature_pipeline.json");
  private static final String MODEL_NAME = "pitch_outcome_pre";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-pitch-lookup-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-pitch-lookup-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RegistryService registryService;
  @Autowired private ModelLoader modelLoader;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path sourceDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
  }

  @Test
  void register_with_declared_lookups_present_copies_them_and_loadPitchPre_succeeds()
      throws Exception {
    Path versionSource = stageSnapshotSources("v1", true);
    ModelVersion mv = registryService.register(buildRequest("v1", versionSource));

    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    assertThat(snapshotDir).isNotNull();
    // The registry placed every declared lookup beside model.onnx (not just
    // model/metadata/pipeline)
    assertThat(snapshotDir.resolve("park_id_mapping.json")).exists();
    assertThat(snapshotDir.resolve("pitcher_te.json")).exists();
    assertThat(snapshotDir.resolve("batter_te.json")).exists();

    LoadedPitchModel loaded = modelLoader.loadPitchPre(mv.id());
    try {
      assertThat(loaded.modelName()).isEqualTo(MODEL_NAME);
      assertThat(loaded.version()).isEqualTo("v1");
      assertThat(loaded.head()).isEqualTo(Head.PRE);
    } finally {
      modelLoader.invalidate(mv.id());
    }
  }

  @Test
  void load_fails_loud_when_a_required_lookup_is_missing_from_the_snapshot() throws Exception {
    // batter_te.json is declared by the contract but absent from the source dir, so the registry
    // copies only park_id_mapping.json + pitcher_te.json. Registration still succeeds (it is not
    // the
    // completeness gate); the loader is.
    Path versionSource = stageSnapshotSources("v-missing", false);
    ModelVersion mv = registryService.register(buildRequest("v-missing", versionSource));

    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    assertThat(snapshotDir).isNotNull();
    assertThat(snapshotDir.resolve("park_id_mapping.json")).exists();
    assertThat(snapshotDir.resolve("pitcher_te.json")).exists();
    assertThat(snapshotDir.resolve("batter_te.json"))
        .as("the missing lookup was never staged, so it is absent from the snapshot")
        .doesNotExist();

    // loadPitchPre fail-loud: FeaturePipelinePitchPre.load reads batter_te.json from the snapshot
    // and
    // throws NoSuchFileException, which ModelLoader wraps as IllegalStateException. A bad export
    // thus
    // fails before serving a single prediction.
    assertThatThrownBy(() -> modelLoader.loadPitchPre(mv.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed to load pitch PRE model");
  }

  // --- helpers ----------------------------------------------------------

  /**
   * Lay down model.onnx + metadata.json + feature_pipeline.json + calibrator.json + the Tier-2
   * lookups in a fresh per-version source dir. When {@code allLookups} is false, batter_te.json is
   * deliberately omitted to exercise the fail-loud path.
   */
  private Path stageSnapshotSources(String version, boolean allLookups) throws Exception {
    Path versionSource = Files.createDirectories(sourceDir.resolve(version));
    URL onnx = getClass().getResource("/onnx/pitch_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch fixture missing from classpath").toURI()),
        versionSource.resolve("model.onnx"));
    Files.writeString(versionSource.resolve("metadata.json"), metadataJson());
    Files.copy(CONTRACT, versionSource.resolve("feature_pipeline.json"));
    Files.writeString(versionSource.resolve("calibrator.json"), identityCalibratorJson());
    Files.writeString(versionSource.resolve("park_id_mapping.json"), parkLookupJson());
    Files.writeString(versionSource.resolve("pitcher_te.json"), teLookupJson("pitcher_id"));
    if (allLookups) {
      Files.writeString(versionSource.resolve("batter_te.json"), teLookupJson("batter_id"));
    }
    return versionSource;
  }

  private RegisterRequest buildRequest(String version, Path versionSource) {
    return new RegisterRequest(
        MODEL_NAME,
        version,
        versionSource.resolve("model.onnx").toString(),
        versionSource.resolve("metadata.json").toString(),
        versionSource.resolve("feature_pipeline.json").toString(),
        "train-h-pitch-" + version,
        "[2015-01-01,2023-12-31]",
        "{\"ece\":0.0035}",
        Instant.now(),
        "pitch-lookup-it",
        "registered by PitchSnapshotLookupRegistrationIT");
  }

  private static String metadataJson() {
    return "{\"model_name\":\"pitch_outcome_pre\",\"model_version\":\"v1\","
        + "\"calibrator\":{\"path\":\"calibrator.json\"}}";
  }

  /** Identity isotonic calibrator over the 5 pitch classes (x == y == [0,1]). */
  private static String identityCalibratorJson() {
    String[] labels = {"ball", "called_strike", "swinging_strike", "foul", "in_play"};
    StringBuilder classLabels = new StringBuilder("[");
    StringBuilder breakpoints = new StringBuilder("[");
    for (int i = 0; i < labels.length; i++) {
      if (i > 0) {
        classLabels.append(",");
        breakpoints.append(",");
      }
      classLabels.append("\"").append(labels[i]).append("\"");
      breakpoints.append("{\"x_thresholds\":[0.0,1.0],\"y_thresholds\":[0.0,1.0]}");
    }
    classLabels.append("]");
    breakpoints.append("]");
    return "{\"class_labels\":" + classLabels + ",\"breakpoints\":" + breakpoints + "}";
  }

  /** Empty park mapping (forPark falls back to missing_value); the fixture ignores park_id_int. */
  private static String parkLookupJson() {
    return "{\"park_id\":{},\"missing_value\":-1}";
  }

  /** TE lookup with an all-zero prior and no rows (forEntity falls back to the prior). */
  private static String teLookupJson(String entityCol) {
    return "{\"entity_col\":\""
        + entityCol
        + "\",\"prior\":{\"ball\":0.0,\"called_strike\":0.0,\"swinging_strike\":0.0,"
        + "\"foul\":0.0,\"in_play\":0.0},\"rows\":[]}";
  }
}
