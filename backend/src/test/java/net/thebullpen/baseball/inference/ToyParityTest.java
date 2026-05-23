package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Java side of the Python↔Java parity contract (Phase 1.4).
 *
 * <p>Reads the same fixture the Python parity test reads ({@code
 * training/tests/fixtures/parity_toy_001*.json}), runs each input row through Java's ONNX session +
 * FeaturePipeline, and asserts the resulting probability matches the expected value within the
 * fixture's tolerance (1e-6 today). Drift here means the Java preprocess has silently diverged from
 * Python.
 *
 * <p>The test self-disables (via {@link EnabledIf}) when the fixture + artifact files are missing —
 * keeps {@code ./gradlew test} green on a fresh clone where the Python export step hasn't been run
 * yet. CI runs the Python export job before the Java tests so the assertions actually fire.
 */
class ToyParityTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path FIXTURE_DIR = REPO_ROOT.resolve("training/tests/fixtures");
  private static final Path INPUT_PATH = FIXTURE_DIR.resolve("parity_toy_001.json");
  private static final Path EXPECTED_PATH = FIXTURE_DIR.resolve("parity_toy_001_expected.json");
  private static final Path ARTIFACT_DIR = REPO_ROOT.resolve("training/artifacts/_toy/v0");
  private static final Path ONNX_PATH = ARTIFACT_DIR.resolve("model.onnx");
  private static final Path CONTRACT_PATH = REPO_ROOT.resolve("contracts/feature_pipeline.json");
  private static final Path PARK_PATH = ARTIFACT_DIR.resolve("park_hr_rate.json");

  @SuppressWarnings("unused")
  static boolean fixtureAndArtifactsPresent() {
    return Files.exists(INPUT_PATH)
        && Files.exists(EXPECTED_PATH)
        && Files.exists(ONNX_PATH)
        && Files.exists(CONTRACT_PATH)
        && Files.exists(PARK_PATH);
  }

  @Test
  @EnabledIf("fixtureAndArtifactsPresent")
  void schemaHashesMatchBetweenInputAndExpected() throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode input = mapper.readTree(Files.readAllBytes(INPUT_PATH));
    JsonNode expected = mapper.readTree(Files.readAllBytes(EXPECTED_PATH));
    assertEquals(
        input.get("schema_hash").asText(),
        expected.get("schema_hash").asText(),
        "input + expected fixtures came from different schema versions");
  }

  @Test
  @EnabledIf("fixtureAndArtifactsPresent")
  void javaOnnxMatchesExpectedForEveryRow() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode input = mapper.readTree(Files.readAllBytes(INPUT_PATH));
    JsonNode expected = mapper.readTree(Files.readAllBytes(EXPECTED_PATH));
    double tolerance = expected.get("tolerance").asDouble();

    FeaturePipeline pipeline = FeaturePipeline.load(CONTRACT_PATH, PARK_PATH);
    try (OnnxModel model = new OnnxModel(ONNX_PATH)) {
      JsonNode rows = input.get("rows");
      JsonNode expectedRows = expected.get("rows");
      assertEquals(
          rows.size(),
          expectedRows.size(),
          "input and expected fixtures have different row counts");

      for (int i = 0; i < rows.size(); i++) {
        JsonNode raw = rows.get(i);
        JsonNode want = expectedRows.get(i);
        FeaturePipeline.RawRow rawRow = parseRow(raw);
        float[] vector = pipeline.transform(rawRow);
        assertFeatureVectorMatches(vector, want.get("feature_vector"), tolerance, raw);

        float predicted = model.predict(vector);
        double wanted = want.get("onnx_probability").asDouble();
        double drift = Math.abs(predicted - wanted);
        assertTrue(
            drift < tolerance,
            String.format(
                "prob drift > %g on game_id=%d: got %f wanted %f",
                tolerance, raw.get("game_id").asLong(), predicted, wanted));
      }
    }
  }

  private static FeaturePipeline.RawRow parseRow(JsonNode raw) {
    Map<String, Double> numeric = new HashMap<>();
    Map<String, String> categorical = new HashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = raw.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      JsonNode value = entry.getValue();
      if (value.isNumber()) {
        numeric.put(entry.getKey(), value.asDouble());
      } else if (value.isTextual()) {
        categorical.put(entry.getKey(), value.asText());
      }
    }
    return new FeaturePipeline.RawRow(numeric, categorical);
  }

  private static void assertFeatureVectorMatches(
      float[] actual, JsonNode expected, double tolerance, JsonNode raw) {
    assertEquals(
        expected.size(),
        actual.length,
        "feature vector length mismatch on game_id=" + raw.get("game_id").asLong());
    for (int i = 0; i < actual.length; i++) {
      double want = expected.get(i).asDouble();
      double drift = Math.abs(actual[i] - want);
      assertTrue(
          drift < tolerance,
          String.format(
              "feature[%d] drift > %g on game_id=%d: got %f wanted %f",
              i, tolerance, raw.get("game_id").asLong(), actual[i], want));
    }
  }
}
