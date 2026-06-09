package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PitchOnnxModel} against a tiny deterministic fixture (W1). Pure ORT-Java (no
 * ClickHouse, no Spring), so it runs in the normal unit-test set regardless of whether the multi-MB
 * production pitch artifact is on the box.
 *
 * <p>The fixture ({@code /onnx/pitch_outcome_fixture.onnx}) is a {@code [None,31] -> [None,5]}
 * graph that slices the first 5 input features and applies Softmax, so the output is an assertable
 * distribution. The input tensor is named {@code "input"}; the reader resolves the name from the
 * loaded session (decision [152]) rather than hardcoding it, which this test pins by reading {@link
 * PitchOnnxModel#inputName()}.
 */
class PitchOnnxModelTest {

  private static Path fixture() throws Exception {
    var url = PitchOnnxModelTest.class.getResource("/onnx/pitch_outcome_fixture.onnx");
    return Path.of(Objects.requireNonNull(url, "pitch fixture missing from classpath").toURI());
  }

  @Test
  void predict_reads_a_calibratable_five_class_distribution() throws Exception {
    try (PitchOnnxModel model = new PitchOnnxModel(fixture())) {
      // Input-name is resolved from the session, not hardcoded.
      assertEquals("input", model.inputName(), "fixture declares input tensor name 'input'");

      float[] features = new float[31];
      for (int i = 0; i < 5; i++) {
        features[i] = i; // first 5 -> softmax([0,1,2,3,4])
      }

      float[] probs = model.predict(features);

      assertEquals(5, probs.length, "5-class pitch output");
      double sum = 0.0;
      double prev = -1.0;
      for (float p : probs) {
        assertTrue(p >= 0.0f && p <= 1.0f, "each class prob in [0,1]");
        sum += p;
        assertTrue(p > prev, "softmax of increasing logits is strictly increasing");
        prev = p;
      }
      assertEquals(1.0, sum, 1e-5, "softmax output sums to 1");
    }
  }
}
