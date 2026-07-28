package net.thebullpen.baseball.inference;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The canonical WELL-FORMED pitch-type snapshot fixture: real ONNX (the committed {@code
 * /onnx/pitch_type_fixture.onnx}), the real repo contract, a temperature calibrator, the NYY park
 * lookup, and metadata declaring {@code model_kind} per decision [184]. Extracted because {@code
 * PredictPitchTypeRoutingIT} and {@code ModelLoaderLifecycleIT} carried byte-identical copies - a
 * contract change that adds a required snapshot file must land in ONE place, or a stale copy
 * silently produces a differently-shaped snapshot.
 *
 * <p>Deliberately NOT used by {@code ModelLoadValidatorPitchTypeTest}: that test's parameterized
 * builder varies each part of the snapshot on purpose (absent model_kind, broken metadata, missing
 * onnx) - its variations are its charter, not drift from this fixture.
 */
public final class PitchTypeSnapshotFixtures {

  // Relative to the Gradle working dir (backend/), like the ITs' REPO_ROOT idiom but without
  // the nullable System.getProperty/getParent chain: this helper's name matches neither SpotBugs
  // test-exclusion pattern (~.*Test / ~.*IT), so unlike the ITs it IS analyzed, and the NP
  // detector rejects that chain in a static initializer. A wrong working dir fails loud at
  // Files.copy with the resolved path in the message.
  private static final Path CONTRACT =
      Path.of("..", "contracts", "feature_pipeline_pitchtype.json").normalize();

  private PitchTypeSnapshotFixtures() {}

  /**
   * Write the standard snapshot into {@code dir} (created if absent) and return it. The caller
   * still owns registration - the {@code RegisterRequest} fields (train hash, notes, registered-by)
   * are per-test identity, not fixture shape.
   */
  public static Path writeStandardSnapshot(Path dir, String modelName, String version)
      throws Exception {
    Files.createDirectories(dir);
    URL onnx = PitchTypeSnapshotFixtures.class.getResource("/onnx/pitch_type_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "pitch-type fixture missing").toURI()),
        dir.resolve("model.onnx"));
    Files.writeString(
        dir.resolve("metadata.json"),
        "{\"model_name\":\""
            + modelName
            + "\",\"model_kind\":\"pitch_type\",\"model_version\":\""
            + version
            + "\",\"calibrator\":{\"path\":\"calibrator.json\"}}");
    Files.copy(CONTRACT, dir.resolve("feature_pipeline.json"));
    Files.writeString(
        dir.resolve("calibrator.json"),
        "{\"kind\":\"temperature\",\"class_labels\":"
            + "[\"FF\",\"SI\",\"FC\",\"SL\",\"CU\",\"CH\",\"OFF\"],\"temperature\":1.0}");
    Files.writeString(
        dir.resolve("park_id_mapping.json"), "{\"park_id\":{\"NYY\":0},\"missing_value\":-1}");
    return dir;
  }
}
