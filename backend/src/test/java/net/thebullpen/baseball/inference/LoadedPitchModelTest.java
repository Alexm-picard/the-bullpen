package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import net.thebullpen.baseball.registry.SnapshotStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit test for {@link LoadedPitchModel} (W1): a full snapshot-resolved pre-head bundle (committed
 * ORT fixture + committed contract + identity calibrator + minimal Tier-2 lookups) loaded from a
 * temp snapshot directory. Pure ORT-Java (no Spring, no ClickHouse), so it runs in the normal
 * unit-test set.
 *
 * <p>Proves the two W1 disciplines on the inference side: (1) the ONNX I/O name + calibrator are
 * resolved from the snapshot, never hardcoded; (2) a snapshot missing its calibrator fails loud at
 * load time rather than serving uncalibrated.
 */
class LoadedPitchModelTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT = REPO_ROOT.resolve("contracts/feature_pipeline.json");
  private static final String[] LABELS = {
    "ball", "called_strike", "swinging_strike", "foul", "in_play"
  };

  @Test
  void loadPre_resolves_everything_from_the_snapshot_and_serves_calibrated(@TempDir Path snapshot)
      throws Exception {
    writeFullSnapshot(snapshot);

    try (LoadedPitchModel model =
        LoadedPitchModel.loadPre(7L, "pitch_outcome_pre", "v1", "schema-hash-7", snapshot)) {
      assertThat(model.head()).isEqualTo(Head.PRE);
      assertThat(model.versionId()).isEqualTo(7L);
      assertThat(model.classLabels()).containsExactly(LABELS);

      Map<String, Double> probs = model.predictPre(sampleRequest());
      assertThat(probs).containsOnlyKeys(LABELS);
      double sum = probs.values().stream().mapToDouble(Double::doubleValue).sum();
      assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
      probs.values().forEach(p -> assertThat(p).isBetween(0.0, 1.0));
    }
  }

  @Test
  void loadPre_fails_loud_when_calibrator_missing(@TempDir Path snapshot) throws Exception {
    writeFullSnapshot(snapshot);
    Files.delete(snapshot.resolve(SnapshotStorage.CALIBRATOR_FILE));
    // metadata still points at calibrator.json, but the file is gone -> hard fail, not silent
    // uncalibrated serving.
    assertThatThrownBy(
            () ->
                LoadedPitchModel.loadPre(7L, "pitch_outcome_pre", "v1", "schema-hash-7", snapshot))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("calibrator");
  }

  // --- helpers ----------------------------------------------------------

  private static void writeFullSnapshot(Path snapshot) throws Exception {
    var url = LoadedPitchModelTest.class.getResource("/onnx/pitch_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(url, "pitch fixture missing").toURI()),
        snapshot.resolve(SnapshotStorage.ARTIFACT_FILE));
    Files.copy(CONTRACT, snapshot.resolve(SnapshotStorage.FEATURE_PIPELINE_FILE));
    Files.writeString(
        snapshot.resolve(SnapshotStorage.METADATA_FILE),
        "{\"model_name\":\"pitch_outcome_pre\",\"calibrator\":{\"path\":\"calibrator.json\"}}");
    Files.writeString(snapshot.resolve(SnapshotStorage.CALIBRATOR_FILE), identityCalibrator());
    Files.writeString(
        snapshot.resolve("park_id_mapping.json"), "{\"park_id\":{},\"missing_value\":-1}");
    Files.writeString(snapshot.resolve("pitcher_te.json"), teLookup("pitcher_id"));
    Files.writeString(snapshot.resolve("batter_te.json"), teLookup("batter_id"));
  }

  private static FeaturePipelinePitchPre.Request sampleRequest() {
    return new FeaturePipelinePitchPre.Request(
        1, 1, 1, 4, 0, 0, 3, "R", "L", "NYY", 545361L, 605141L, null, null, null, null, null, null,
        null, null, null, null, null);
  }

  private static String identityCalibrator() {
    StringBuilder labels = new StringBuilder("[");
    StringBuilder bps = new StringBuilder("[");
    for (int i = 0; i < LABELS.length; i++) {
      if (i > 0) {
        labels.append(",");
        bps.append(",");
      }
      labels.append("\"").append(LABELS[i]).append("\"");
      bps.append("{\"x_thresholds\":[0.0,1.0],\"y_thresholds\":[0.0,1.0]}");
    }
    labels.append("]");
    bps.append("]");
    return "{\"class_labels\":" + labels + ",\"breakpoints\":" + bps + "}";
  }

  private static String teLookup(String entityCol) {
    return "{\"entity_col\":\""
        + entityCol
        + "\",\"prior\":{\"ball\":0.0,\"called_strike\":0.0,\"swinging_strike\":0.0,"
        + "\"foul\":0.0,\"in_play\":0.0},\"rows\":[]}";
  }
}
