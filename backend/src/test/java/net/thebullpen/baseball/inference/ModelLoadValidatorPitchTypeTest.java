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
 * The pitch-TYPE load gate (decision [183]).
 *
 * <p>THE BUG THIS PINS: pitch-type metadata carries neither {@code park_order} nor {@code head}, so
 * before {@code model_kind} existed every pitch-type model fell through {@link
 * ModelLoadValidator}'s branches into the batted-ball loader, was handed a batted-ball dummy, and
 * 422'd at CANDIDATE -&gt; SHADOW and at every promotion. That would have stopped the Phase-3 box
 * run dead. {@link #validate_routes_a_pitch_type_snapshot_to_the_pitch_type_loader} is the proof it
 * now routes.
 *
 * <p>Real ORT-Java session over the committed {@code pitch_type_fixture.onnx} - no mocked ONNX, per
 * the testing posture. That fixture is deliberately TWO-output (label, probabilities), the shape
 * both real exports produce; no other committed fixture is, so this is the first Java test to
 * exercise {@code PitchOnnxModel}'s index-1 probability read at all.
 *
 * <p>The warm-up dummy carries nulls through the ARS and V013 blocks (a pitcher's first career
 * pitch), so the gate exercises the NaN path the LR baseline's in-graph Imputer exists to absorb
 * rather than only the dense path.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class ModelLoadValidatorPitchTypeTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_pitchtype.json");
  private static final String MODEL_NAME = "pitch_type_pre";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loadgate-pitchtype-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-loadgate-pitchtype-it-snapshots-" + UUID.randomUUID());
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
  void validate_routes_a_pitch_type_snapshot_to_the_pitch_type_loader() throws Exception {
    Path src =
        stageSnapshot("v1", metadataJson(MODEL_NAME, ModelLoadValidator.PITCH_TYPE_KIND), false);
    ModelVersion mv = registryService.register(request(MODEL_NAME, "v1", src));
    assertThatCode(() -> modelLoadValidator.validate(mv)).doesNotThrowAnyException();
  }

  @Test
  void validate_routes_the_rule9_lr_baseline_too() throws Exception {
    // The baseline is a SEPARATE registry row sharing the contract, and the [182] first-champion
    // gate binds its champion to a registered version of it - so it must pass the same gate.
    String name = "pitch_type_lr_baseline";
    Path src = stageSnapshot("v1b", metadataJson(name, ModelLoadValidator.PITCH_TYPE_KIND), false);
    ModelVersion mv = registryService.register(request(name, "v1b", src));
    assertThatCode(() -> modelLoadValidator.validate(mv)).doesNotThrowAnyException();
  }

  @Test
  void without_model_kind_the_snapshot_never_reaches_the_load_gate() throws Exception {
    // This test used to register a kind-less snapshot and prove it MISROUTED into the batted-ball
    // branch at load time. That hazard is now unreachable: decision [184]'s registration check
    // (RegistryService.doInsert) refuses the bundle before a row is ever written, so the load gate
    // is never asked. The guarantee got strictly stronger, and the test now pins the stronger one.
    //
    // The misroute branch itself is pinned separately, by
    // an_unarmed_family_still_misroutes_at_the_load_gate below - NOT by the sibling tests here,
    // which all pass PITCH_TYPE_KIND and therefore only exercise the positive branch.
    Path src = stageSnapshot("v-nokind", metadataJson(MODEL_NAME, null), false);
    assertThatThrownBy(() -> registryService.register(request(MODEL_NAME, "v-nokind", src)))
        .isInstanceOf(RegistryException.ModelKindMismatch.class)
        .hasMessageContaining("[184]");
  }

  @Test
  void validate_throws_ModelLoadFailed_on_a_corrupt_onnx() throws Exception {
    Path src =
        stageSnapshot(
            "v-corrupt", metadataJson(MODEL_NAME, ModelLoadValidator.PITCH_TYPE_KIND), true);
    ModelVersion mv = registryService.register(request(MODEL_NAME, "v-corrupt", src));
    assertThatThrownBy(() -> modelLoadValidator.validate(mv))
        .isInstanceOf(RegistryException.ModelLoadFailed.class);
  }

  @Test
  void validate_rejects_an_isotonic_calibrator_in_a_pitch_type_snapshot() throws Exception {
    // A pitch-OUTCOME calibrator.json has the same filename and would silently mis-calibrate the
    // prior. The temperature loader refuses it, so the gate 422s instead.
    Path src =
        stageSnapshot(
            "v-isotonic", metadataJson(MODEL_NAME, ModelLoadValidator.PITCH_TYPE_KIND), false);
    Files.writeString(
        src.resolve("calibrator.json"), "{\"class_labels\":[\"FF\"],\"breakpoints\":[]}");
    ModelVersion mv = registryService.register(request(MODEL_NAME, "v-isotonic", src));
    assertThatThrownBy(() -> modelLoadValidator.validate(mv))
        .isInstanceOf(RegistryException.ModelLoadFailed.class);
  }

  @Test
  void raw_probability_gate_bites_on_a_graph_that_is_not_emitting_probabilities() {
    String label = "pitch_type_pre/vX";
    // A well-formed distribution passes.
    assertThatCode(
            () ->
                ModelLoadValidator.assertRawProbabilitiesSane(
                    new float[] {0.4f, 0.2f, 0.15f, 0.1f, 0.07f, 0.05f, 0.03f}, label))
        .doesNotThrowAnyException();

    // THE case this gate exists for: a graph exported without its final softmax emits raw
    // scores. Temperature calibration would renormalise that into a plausible-looking prior,
    // so it is invisible after calibration - it has to be caught here.
    assertThatThrownBy(
            () ->
                ModelLoadValidator.assertRawProbabilitiesSane(
                    new float[] {2.4f, -1.2f, 0.9f, 0.3f, -0.1f, 1.1f, 0.6f}, label))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a probability");

    // In-range but not normalised (e.g. a dropped class or a sliced output).
    assertThatThrownBy(
            () ->
                ModelLoadValidator.assertRawProbabilitiesSane(
                    new float[] {0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f}, label))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sum to 1");

    // NaN output.
    assertThatThrownBy(
            () ->
                ModelLoadValidator.assertRawProbabilitiesSane(
                    new float[] {Float.NaN, 0.2f, 0.15f, 0.1f, 0.07f, 0.05f, 0.03f}, label))
        .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(() -> ModelLoadValidator.assertRawProbabilitiesSane(new float[0], label))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no output");
  }

  // --- helpers ----------------------------------------------------------

  @Test
  void an_unarmed_family_still_misroutes_at_the_load_gate() throws Exception {
    // RESTORES the branch the test above used to cover, and pins the defense-in-depth half of the
    // [184] scoping argument. A model name OUTSIDE CanonicalContracts' family table is UNARMED, so
    // registration does not require model_kind - but the load gate still sniffs metadata, so a
    // kind-less pitch-type bundle falls through to the batted-ball loader and can never reach a
    // serving stage. That dead-end is why scoping the registration check is safe rather than a
    // hole, and it should fail a test if it ever stops being true.
    Path src = stageSnapshot("v-unarmed", metadataJson("pitch_type_experimental", null), false);
    ModelVersion mv =
        registryService.register(request("pitch_type_experimental", "v-unarmed", src));
    assertThatThrownBy(() -> modelLoadValidator.validate(mv))
        .isInstanceOf(RegistryException.ModelLoadFailed.class);
  }

  private Path stageSnapshot(String version, String metadata, boolean corruptOnnx)
      throws Exception {
    Path src = Files.createDirectories(sourceDir.resolve(version));
    if (corruptOnnx) {
      Files.writeString(src.resolve("model.onnx"), "not a valid onnx graph");
    } else {
      URL onnx = getClass().getResource("/onnx/pitch_type_fixture.onnx");
      Files.copy(
          Path.of(Objects.requireNonNull(onnx, "pitch-type fixture missing").toURI()),
          src.resolve("model.onnx"));
    }
    Files.writeString(src.resolve("metadata.json"), metadata);
    Files.copy(CONTRACT, src.resolve("feature_pipeline.json"));
    Files.writeString(src.resolve("calibrator.json"), temperatureCalibratorJson());
    Files.writeString(src.resolve("park_id_mapping.json"), parkLookupJson());
    return src;
  }

  private RegisterRequest request(String modelName, String version, Path src) {
    return new RegisterRequest(
        modelName,
        version,
        src.resolve("model.onnx").toString(),
        src.resolve("metadata.json").toString(),
        src.resolve("feature_pipeline.json").toString(),
        "train-h-pitchtype-" + version,
        "[2015-01-01,2023-12-31]",
        "{\"ece\":0.0036}",
        Instant.now(),
        "loadgate-pitchtype-it",
        "registered by ModelLoadValidatorPitchTypeTest");
  }

  /** Metadata as pitch_type.persist writes it. {@code kind} null omits model_kind entirely. */
  private static String metadataJson(String modelName, String kind) {
    String kindField = kind == null ? "" : "\"model_kind\":\"" + kind + "\",";
    return "{\"model_name\":\""
        + modelName
        + "\","
        + kindField
        + "\"model_version\":\"v1\",\"calibrator\":{\"path\":\"calibrator.json\"}}";
  }

  /** T=1.0 (identity) over the y7 taxonomy, in the contract's label order. */
  private static String temperatureCalibratorJson() {
    return "{\"kind\":\"temperature\",\"class_labels\":"
        + "[\"FF\",\"SI\",\"FC\",\"SL\",\"CU\",\"CH\",\"OFF\"],\"temperature\":1.0}";
  }

  /** NYY present so the dummy's park resolves; missing_value matches the contract. */
  private static String parkLookupJson() {
    return "{\"park_id\":{\"NYY\":0},\"missing_value\":-1}";
  }
}
