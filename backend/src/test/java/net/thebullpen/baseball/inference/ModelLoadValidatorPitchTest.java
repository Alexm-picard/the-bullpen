package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.thebullpen.baseball.registry.RegistryException;
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
 * Change 1 (PR-1): the promotion load-gate must resolve a registered PITCH snapshot to the pitch
 * loader ({@code head=pre|post} from snapshot metadata -> {@code loadPitchPre}/{@code
 * loadPitchPost}) and run one forward pass. Before this, a pitch model had no {@code park_order}
 * and fell into the batted-ball branch, 422ing at CANDIDATE->SHADOW.
 *
 * <p>Real ORT-Java session loading the committed [N,31]->[N,5] pitch fixture (no mocked ONNX, per
 * the testing posture). Isolated tmp SQLite + tmp snapshot base, same pattern as {@code
 * PitchSnapshotLookupRegistrationIT}. The metadata carries {@code "head":"pre"} exactly as
 * register_snapshot.py writes it - that is the discriminator the validator reads.
 *
 * <p>The POST branch ({@code head="post"} -> {@code loadPitchPost}) is the structural sibling of
 * the PRE branch (same loader resolution, the {@code FeaturePipelinePitchPost.Request} shape is
 * verified at compile time by {@code PITCH_POST_DUMMY}); it is not exercised end-to-end here
 * because no committed 41-feature post fixture exists.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class ModelLoadValidatorPitchTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT = REPO_ROOT.resolve("contracts/feature_pipeline.json");
  private static final String MODEL_NAME = "pitch_outcome_pre";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loadgate-pitch-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loadgate-pitch-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RegistryService registryService;
  @Autowired private ModelLoadValidator modelLoadValidator;
  @Autowired private JdbcTemplate jdbc;

  @TempDir Path sourceDir;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM experiment_results");
    jdbc.update("DELETE FROM model_routing");
    jdbc.update("DELETE FROM model_versions");
  }

  @Test
  void validate_passes_for_a_pre_head_snapshot() throws Exception {
    Path src = stageSnapshotSources("v1", /* corruptOnnx= */ false);
    ModelVersion mv = registryService.register(buildRequest("v1", src));
    // head=pre routes to loadPitchPre; a real forward pass on the committed fixture succeeds.
    assertThatCode(() -> modelLoadValidator.validate(mv)).doesNotThrowAnyException();
  }

  @Test
  void validate_throws_ModelLoadFailed_on_a_corrupt_onnx() throws Exception {
    Path src = stageSnapshotSources("v-corrupt", /* corruptOnnx= */ true);
    ModelVersion mv = registryService.register(buildRequest("v-corrupt", src));
    // The corrupt ONNX passes registration (schema-hash is on the pipeline, not the graph) but
    // fails the load gate's forward pass: a 422 at promote-time, not a 500 live at serving.
    assertThatThrownBy(() -> modelLoadValidator.validate(mv))
        .isInstanceOf(RegistryException.ModelLoadFailed.class);
  }

  // --- helpers ----------------------------------------------------------

  private Path stageSnapshotSources(String version, boolean corruptOnnx) throws Exception {
    Path src = Files.createDirectories(sourceDir.resolve(version));
    if (corruptOnnx) {
      Files.writeString(src.resolve("model.onnx"), "not a valid onnx graph");
    } else {
      URL onnx = getClass().getResource("/onnx/pitch_outcome_fixture.onnx");
      Files.copy(
          Path.of(Objects.requireNonNull(onnx, "pitch fixture missing from classpath").toURI()),
          src.resolve("model.onnx"));
    }
    Files.writeString(src.resolve("metadata.json"), metadataJson());
    Files.copy(CONTRACT, src.resolve("feature_pipeline.json"));
    Files.writeString(src.resolve("calibrator.json"), identityCalibratorJson());
    Files.writeString(src.resolve("park_id_mapping.json"), parkLookupJson());
    Files.writeString(src.resolve("pitcher_te.json"), teLookupJson("pitcher_id"));
    Files.writeString(src.resolve("batter_te.json"), teLookupJson("batter_id"));
    return src;
  }

  private RegisterRequest buildRequest(String version, Path src) {
    return new RegisterRequest(
        MODEL_NAME,
        version,
        src.resolve("model.onnx").toString(),
        src.resolve("metadata.json").toString(),
        src.resolve("feature_pipeline.json").toString(),
        "train-h-loadgate-" + version,
        "[2015-01-01,2023-12-31]",
        "{\"ece\":0.0035}",
        Instant.now(),
        "loadgate-pitch-it",
        "registered by ModelLoadValidatorPitchTest");
  }

  /** Metadata exactly as register_snapshot.py writes it: head=pre + the calibrator pointer. */
  private static String metadataJson() {
    return "{\"model_name\":\"pitch_outcome_pre\",\"model_version\":\"v1\",\"head\":\"pre\","
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
