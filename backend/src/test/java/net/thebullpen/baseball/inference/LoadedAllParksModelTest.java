package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Composition test for {@link LoadedAllParksModel} (B-workstream B4): wires the committed
 * batted-ball contract + an identity scaler + the {@code [None,15] -> [None,30,5]} ONNX fixture +
 * identity per-park calibrators into a snapshot directory, then asserts {@code predict()} runs the
 * full transform -> ONNX -> per-park calibrate -> renormalize chain. Self-contained (Mac-runnable;
 * no desktop artifacts).
 *
 * <p>The fixture tiles the first 5 input features across all 30 parks, so each park's raw row is
 * {@code [feat0..feat4]}. With an identity scaler and identity calibrators the calibrated
 * distribution is the L1-normalized first-5 feature vector - a deterministic, non-uniform
 * expectation that exercises the per-outcome transform + renormalize, not just a trivial uniform.
 */
class LoadedAllParksModelTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_battedball.json");
  private static final int N_PARKS = 30;
  private static final int N_OUTCOMES = 5;

  @Test
  void predict_runs_transform_onnx_calibrate_per_park(@TempDir Path snapshotDir) throws Exception {
    stageSnapshot(snapshotDir);

    try (LoadedAllParksModel model =
        LoadedAllParksModel.load(7L, "battedball_outcome", "v1", "53be50f9", snapshotDir)) {

      assertEquals(N_PARKS, model.parkOrder().size());
      assertIterableEquals(List.of("out", "1b", "2b", "3b", "hr"), model.outcomeOrder());

      // feat0..feat4 = [launch_speed, launch_angle, spray, hit_distance, stand_R]. In-[0,1] values
      // so the identity calibrators don't all clamp to 1.0 -> a non-uniform distribution. stand=R
      // makes stand_R (feat4) = 1.0.
      FeaturePipelineBattedBall.Request req =
          new FeaturePipelineBattedBall.Request(0.5, 0.3, 0.1, 0.8, "R", 3, 1);
      Map<String, float[]> byPark = model.predict(req);

      assertEquals(N_PARKS, byPark.size());
      assertIterableEquals(model.parkOrder(), byPark.keySet());

      double sumRaw = 0.5 + 0.3 + 0.1 + 0.8 + 1.0;
      float[] expected = {
        (float) (0.5 / sumRaw),
        (float) (0.3 / sumRaw),
        (float) (0.1 / sumRaw),
        (float) (0.8 / sumRaw),
        (float) (1.0 / sumRaw)
      };
      for (Map.Entry<String, float[]> e : byPark.entrySet()) {
        float[] dist = e.getValue();
        assertEquals(N_OUTCOMES, dist.length, "park " + e.getKey());
        double sum = 0;
        for (float v : dist) {
          sum += v;
        }
        assertEquals(1.0, sum, 1e-5, "park " + e.getKey() + " distribution must sum to 1");
        assertArrayEquals(expected, dist, 1e-4f, "park " + e.getKey() + " calibrated distribution");
      }
    }
  }

  private void stageSnapshot(Path dir) throws Exception {
    Files.copy(CONTRACT, dir.resolve("feature_pipeline.json"));
    URL onnx = getClass().getResource("/onnx/battedball_park_outcome_fixture.onnx");
    Files.copy(
        Path.of(Objects.requireNonNull(onnx, "fixture missing from classpath").toURI()),
        dir.resolve("model.onnx"));
    Files.writeString(dir.resolve("metadata.json"), metadataJson());
    Files.writeString(dir.resolve("calibrator.json"), calibratorJson());
  }

  /** model_name + a 15-feature identity scaler (means 0 / stds 1) so raw features pass through. */
  private static String metadataJson() {
    StringBuilder means = new StringBuilder("[");
    StringBuilder stds = new StringBuilder("[");
    for (int i = 0; i < 15; i++) {
      means.append(i == 0 ? "0.0" : ",0.0");
      stds.append(i == 0 ? "1.0" : ",1.0");
    }
    means.append("]");
    stds.append("]");
    return "{\"model_name\":\"battedball_outcome\",\"feature_scaler\":{\"means\":"
        + means
        + ",\"stds\":"
        + stds
        + ",\"is_continuous\":[]}}";
  }

  /** 30 parks x 5 identity isotonic calibrators (x_thresholds == y_thresholds == [0,1]). */
  private static String calibratorJson() {
    String identity = "{\"x_thresholds\":[0.0,1.0],\"y_thresholds\":[0.0,1.0]}";
    StringBuilder parkOrder = new StringBuilder("[");
    StringBuilder parks = new StringBuilder("{");
    for (int p = 0; p < N_PARKS; p++) {
      String name = String.format("PARK%02d", p);
      if (p > 0) {
        parkOrder.append(",");
        parks.append(",");
      }
      parkOrder.append("\"").append(name).append("\"");
      parks.append("\"").append(name).append("\":[");
      for (int o = 0; o < N_OUTCOMES; o++) {
        parks.append(o == 0 ? "" : ",").append(identity);
      }
      parks.append("]");
    }
    parkOrder.append("]");
    parks.append("}");
    return "{\"park_order\":"
        + parkOrder
        + ",\"outcome_order\":[\"out\",\"1b\",\"2b\",\"3b\",\"hr\"],\"parks\":"
        + parks
        + "}";
  }
}
