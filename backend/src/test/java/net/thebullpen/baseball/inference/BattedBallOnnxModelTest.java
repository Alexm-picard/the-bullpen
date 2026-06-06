package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link BattedBallOnnxModel} (B-workstream B1) against a tiny deterministic fixture.
 *
 * <p>The fixture ({@code /onnx/battedball_park_outcome_fixture.onnx}) is a {@code [None,15] ->
 * [None,30,5]} graph that slices the first 5 input features and tiles them across 30 parks, so for
 * input {@code [0,1,...,14]} every park's row is {@code [0,1,2,3,4]}. That lets us assert exact
 * values through the real ORT-Java 3D-tensor read path - the shape the MLP / LGBM / LR baseline all
 * emit. Pure ORT-Java (no ClickHouse), so it runs in the normal unit-test set.
 */
class BattedBallOnnxModelTest {

  private static Path fixture() throws Exception {
    var url =
        BattedBallOnnxModelTest.class.getResource("/onnx/battedball_park_outcome_fixture.onnx");
    return Path.of(Objects.requireNonNull(url, "fixture missing from test classpath").toURI());
  }

  @Test
  void predict_reads_the_full_park_outcome_distribution() throws Exception {
    try (BattedBallOnnxModel model = new BattedBallOnnxModel(fixture())) {
      float[] features = new float[15];
      for (int i = 0; i < features.length; i++) {
        features[i] = i; // [0, 1, ..., 14]
      }

      float[][] dist = model.predict(features);

      assertEquals(30, dist.length, "park axis (nParks)");
      assertEquals(5, dist[0].length, "outcome axis (nOutcomes)");
      // The fixture tiles the first 5 features across every park, so each park row is [0,1,2,3,4].
      for (int park = 0; park < 30; park++) {
        for (int outcome = 0; outcome < 5; outcome++) {
          assertEquals(
              (float) outcome, dist[park][outcome], 1e-6f, "park " + park + " outcome " + outcome);
        }
      }
    }
  }

  @Test
  void predictBatch_returns_one_park_outcome_grid_per_row() throws Exception {
    try (BattedBallOnnxModel model = new BattedBallOnnxModel(fixture())) {
      float[][] batch = {new float[15], new float[15]};
      batch[1][0] = 9f; // row 1's first feature surfaces at outcome 0 across every park

      float[][][] out = model.predictBatch(batch);

      assertEquals(2, out.length, "one grid per input row");
      assertEquals(30, out[0].length);
      assertEquals(5, out[0][0].length);
      assertEquals(0f, out[0][0][0], 1e-6f); // row 0: all features 0
      assertEquals(9f, out[1][0][0], 1e-6f); // row 1: feature[0]=9 -> outcome 0
    }
  }
}
