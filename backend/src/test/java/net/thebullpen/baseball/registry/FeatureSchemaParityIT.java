package net.thebullpen.baseball.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Parity test: the Java {@link FeatureSchemaHasher} must produce the same SHA-256 digest as the
 * Python implementation in {@code bullpen_training.registry_client.feature_hasher} for every
 * fixture under {@code contracts/test_fixtures/feature_hasher/}.
 *
 * <p>Same fixtures are exercised on the Python side by {@code
 * training/tests/registry_client/test_feature_hasher.py}. If a fixture's hash drifts on either
 * side, both suites fail at once and the divergence is loud — exactly the rule-7 / decision-[67]
 * guarantee at the registration boundary.
 *
 * <p>To add a fixture: drop the input JSON under the fixtures dir, compute its hash via the Python
 * reference (`uv run python -c "from bullpen_training.registry_client.feature_hasher import
 * compute; print(compute('<path>'))"`), and append the entry to {@code fixtures.json}.
 */
class FeatureSchemaParityIT {

  // backend module's working dir is .../thebullpen/backend during test execution,
  // so the contracts dir is one level up.
  private static final Path FIXTURE_DIR =
      Path.of("..", "contracts", "test_fixtures", "feature_hasher");

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final FeatureSchemaHasher hasher = new FeatureSchemaHasher();

  @TestFactory
  List<DynamicTest> parityForEveryManifestEntry() throws IOException {
    JsonNode manifest = MAPPER.readTree(FIXTURE_DIR.resolve("fixtures.json").toFile());
    assertThat(manifest.isArray())
        .as("fixtures.json must be a JSON array of {name,input_file,expected_hash} entries")
        .isTrue();

    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode entry : manifest) {
      String name = entry.get("name").asText();
      String inputFile = entry.get("input_file").asText();
      String expected = entry.get("expected_hash").asText();
      tests.add(
          DynamicTest.dynamicTest(
              "parity/" + name,
              () -> {
                Path inputPath = FIXTURE_DIR.resolve(inputFile);
                assertThat(Files.exists(inputPath))
                    .as("fixture input file %s must exist", inputPath)
                    .isTrue();
                String actual = hasher.compute(inputPath);
                assertThat(actual)
                    .as(
                        "fixture %s: Java hash diverges from Python — "
                            + "either CanonicalJson or feature_hasher.py changed",
                        name)
                    .isEqualTo(expected);
              }));
    }
    return tests;
  }
}
