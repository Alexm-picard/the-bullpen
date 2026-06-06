package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loads a small merged {@code calibrator.json} (the exporter format) and checks per-park
 * calibration + renormalization (B-workstream B3).
 */
class BattedBallCalibratorsTest {

  private static final String CALIBRATOR_JSON =
      """
      {
        "schema_version": 2,
        "model_name": "test_bb",
        "outcome_order": ["out", "hr"],
        "park_order": ["BOS", "NYY"],
        "parks": {
          "BOS": [
            {"outcome": "out", "x_thresholds": [0.0, 1.0], "y_thresholds": [0.0, 0.5], "out_of_bounds": "clip"},
            {"outcome": "hr",  "x_thresholds": [0.0, 1.0], "y_thresholds": [0.0, 1.0], "out_of_bounds": "clip"}
          ],
          "NYY": [
            {"outcome": "out", "x_thresholds": [0.0, 1.0], "y_thresholds": [0.0, 1.0], "out_of_bounds": "clip"},
            {"outcome": "hr",  "x_thresholds": [0.0, 1.0], "y_thresholds": [0.0, 1.0], "out_of_bounds": "clip"}
          ]
        }
      }
      """;

  private BattedBallCalibrators load(Path dir) throws Exception {
    Path p = dir.resolve("calibrator.json");
    Files.writeString(p, CALIBRATOR_JSON);
    return BattedBallCalibrators.load(p);
  }

  @Test
  void parses_park_and_outcome_order(@TempDir Path dir) throws Exception {
    BattedBallCalibrators c = load(dir);
    assertEquals(List.of("BOS", "NYY"), c.parkOrder());
    assertEquals(List.of("out", "hr"), c.outcomeOrder());
    assertEquals(0, c.parkIndex("BOS"));
    assertEquals(1, c.parkIndex("NYY"));
    assertEquals(-1, c.parkIndex("not_a_park"));
  }

  @Test
  void calibrates_per_park_then_renormalizes(@TempDir Path dir) throws Exception {
    BattedBallCalibrators c = load(dir);
    // BOS: out iso halves (transform(0.8)=0.4), hr iso identity (transform(0.2)=0.2)
    //   -> floor -> [0.4, 0.2] -> sum 0.6 -> renormalize -> [2/3, 1/3]
    float[] bos = c.calibrate("BOS", new float[] {0.8f, 0.2f});
    assertEquals(2.0 / 3.0, bos[0], 1e-6);
    assertEquals(1.0 / 3.0, bos[1], 1e-6);
    assertEquals(1.0f, bos[0] + bos[1], 1e-6f, "renormalized distribution sums to 1");

    // NYY: both identity -> [0.7, 0.3] already sums to 1 -> unchanged
    float[] nyy = c.calibrate("NYY", new float[] {0.7f, 0.3f});
    assertEquals(0.7, nyy[0], 1e-6);
    assertEquals(0.3, nyy[1], 1e-6);
  }
}
