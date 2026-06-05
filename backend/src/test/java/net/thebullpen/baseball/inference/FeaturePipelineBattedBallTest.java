package net.thebullpen.baseball.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link FeaturePipelineBattedBall} (decision [45]/[141], A0/B2).
 *
 * <p>Self-contained: uses the committed {@code feature_pipeline_battedball.json} contract + a temp
 * fixture {@code metadata.json} scaler, so it runs on the Mac without the desktop artifacts. The
 * first test is load-bearing for A0 - it proves the committed contract's schema_hash satisfies the
 * Java canonical recompute, i.e. the Python (pre-commit / training) and Java hashers agree.
 */
class FeaturePipelineBattedBallTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_battedball.json");

  private static Path writeMetadata(Path dir, String scalerJson) throws Exception {
    Path p = dir.resolve("metadata.json");
    Files.writeString(
        p, "{\"model_name\":\"battedball_outcome\",\"feature_scaler\":" + scalerJson + "}");
    return p;
  }

  /** 15-feature identity scaler (means 0 / stds 1) so raw features pass through unchanged. */
  private static String identityScaler() {
    StringBuilder means = new StringBuilder("[");
    StringBuilder stds = new StringBuilder("[");
    for (int i = 0; i < 15; i++) {
      means.append(i == 0 ? "0.0" : ",0.0");
      stds.append(i == 0 ? "1.0" : ",1.0");
    }
    means.append("]");
    stds.append("]");
    return "{\"means\":" + means + ",\"stds\":" + stds + ",\"is_continuous\":[]}";
  }

  @Test
  void contractSchemaHashVerifiesUnderJavaHasher(@TempDir Path dir) throws Exception {
    // Load succeeds only if the committed schema_hash matches FeaturePipelineBattedBall's
    // canonical recompute - cross-language parity with the Python pre-commit/training algo.
    FeaturePipelineBattedBall pipeline =
        FeaturePipelineBattedBall.load(CONTRACT, writeMetadata(dir, identityScaler()));
    assertEquals(15, pipeline.spec().featureOrder().size());
    assertEquals(List.of("out", "1b", "2b", "3b", "hr"), pipeline.spec().classLabels());
    assertEquals(30, pipeline.spec().nParks());
    assertEquals("battedball_outcome", pipeline.spec().modelName());
  }

  @Test
  void transformBuildsFifteenFeatureVectorWithOneHots(@TempDir Path dir) throws Exception {
    FeaturePipelineBattedBall pipeline =
        FeaturePipelineBattedBall.load(CONTRACT, writeMetadata(dir, identityScaler()));
    float[] v =
        pipeline.transform(
            new FeaturePipelineBattedBall.Request(108.0, 27.0, 18.0, 405.0, "R", 3, 1));
    assertEquals(15, v.length);
    assertEquals(108.0f, v[0], 1e-6); // launch_speed_mph
    assertEquals(27.0f, v[1], 1e-6); // launch_angle_deg
    assertEquals(18.0f, v[2], 1e-6); // spray_angle_deg
    assertEquals(405.0f, v[3], 1e-6); // hit_distance_ft
    assertEquals(1.0f, v[4], 1e-6); // stand_R
    assertEquals(0.0f, v[5], 1e-6); // stand_L
    assertEquals(0.0f, v[6], 1e-6); // base_state_0
    assertEquals(1.0f, v[9], 1e-6); // base_state_3 (index 6 + 3)
    assertEquals(1.0f, v[14], 1e-6); // outs
  }

  @Test
  void standLeftAndUnknownFallback(@TempDir Path dir) throws Exception {
    FeaturePipelineBattedBall pipeline =
        FeaturePipelineBattedBall.load(CONTRACT, writeMetadata(dir, identityScaler()));
    float[] left =
        pipeline.transform(new FeaturePipelineBattedBall.Request(100, 20, 0, 350, "L", 0, 0));
    assertEquals(0.0f, left[4], 1e-6); // stand_R
    assertEquals(1.0f, left[5], 1e-6); // stand_L
    float[] unknown =
        pipeline.transform(new FeaturePipelineBattedBall.Request(100, 20, 0, 350, null, 0, 0));
    assertEquals(1.0f, unknown[4], 1e-6); // unknown -> R fallback (matches Python stand_one_hot)
    assertEquals(0.0f, unknown[5], 1e-6);
  }

  @Test
  void zscoreAppliedFromMetadataScaler(@TempDir Path dir) throws Exception {
    // launch_speed mean=100 std=10 -> (108-100)/10 = 0.8; the rest identity.
    String scaler =
        "{\"means\":[100.0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],"
            + "\"stds\":[10.0,1,1,1,1,1,1,1,1,1,1,1,1,1,1],\"is_continuous\":[]}";
    FeaturePipelineBattedBall pipeline =
        FeaturePipelineBattedBall.load(CONTRACT, writeMetadata(dir, scaler));
    float[] v =
        pipeline.transform(new FeaturePipelineBattedBall.Request(108, 27, 18, 405, "R", 0, 1));
    assertEquals(0.8f, v[0], 1e-6);
  }

  @Test
  void scalerLengthMismatchFailsLoud(@TempDir Path dir) throws Exception {
    String bad = "{\"means\":[0.0,0.0],\"stds\":[1.0,1.0],\"is_continuous\":[]}";
    Path md = writeMetadata(dir, bad);
    assertThrows(IllegalStateException.class, () -> FeaturePipelineBattedBall.load(CONTRACT, md));
  }
}
