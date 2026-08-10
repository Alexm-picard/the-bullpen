package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Encoder tests for the pitch-TYPE feature pipeline.
 *
 * <p>This is the surface most able to serve wrong numbers quietly. The calibrator has
 * cross-language parity coverage and the load gate proves routing, but neither pins the ENCODING: a
 * swapped column or an inverted L/R map produces a perfectly well-formed 24-vector that simply
 * means something else, and every downstream check would still pass. So these assert the vector
 * positionally against the real committed contract, and deliberately exercise the branches the
 * load-gate dummies never reach - "L" handedness, an unknown park, a null park, and populated
 * nullables.
 */
class FeaturePipelinePitchTypeTest {

  private static final Path REPO_ROOT = Path.of(System.getProperty("user.dir")).getParent();
  private static final Path CONTRACT =
      REPO_ROOT.resolve("contracts/feature_pipeline_pitchtype.json");

  @TempDir Path artifactDir;

  private FeaturePipelinePitchType load(String parkJson) throws Exception {
    Files.writeString(artifactDir.resolve("park_id_mapping.json"), parkJson);
    return FeaturePipelinePitchType.load(CONTRACT, artifactDir);
  }

  private FeaturePipelinePitchType load() throws Exception {
    return load("{\"park_id\":{\"NYY\":7,\"BOS\":3},\"missing_value\":-1}");
  }

  /** Every nullable populated with a distinct value, so a column swap moves a detectable number. */
  private static FeaturePipelinePitchType.Request denseRequest(String stand, String throws_) {
    return new FeaturePipelinePitchType.Request(
        3, 2, 1, 6, 5, stand, throws_, "NYY", 2.0, 12.0, 1.0, 0.41, 0.18, 0.08, 0.15, 0.09, 0.06,
        0.03, 0.44, 850, 4, 2, 0, 14);
  }

  @Test
  void contract_shape_is_the_y7_family() throws Exception {
    FeaturePipelinePitchType.Spec spec = load().spec();
    assertThat(spec.modelName()).isEqualTo("pitch_type_pre");
    assertThat(spec.featureOrder()).hasSize(24);
    assertThat(spec.classLabels()).containsExactly("FF", "SI", "FC", "SL", "CU", "CH", "OFF");
  }

  @Test
  void transform_places_every_value_at_its_contract_position() throws Exception {
    FeaturePipelinePitchType pipeline = load();
    List<String> order = pipeline.spec().featureOrder();
    float[] v = pipeline.transform(denseRequest("R", "L"));

    assertThat(v).hasSize(24);
    // Positional, resolved through the contract's own order rather than hardcoded indices: if the
    // contract is reordered this still checks the right slots, and if the switch mis-maps a column
    // the value lands in the wrong one and this fails.
    assertThat(v[order.indexOf("balls")]).isEqualTo(3f);
    assertThat(v[order.indexOf("strikes")]).isEqualTo(2f);
    assertThat(v[order.indexOf("outs")]).isEqualTo(1f);
    assertThat(v[order.indexOf("inning")]).isEqualTo(6f);
    assertThat(v[order.indexOf("base_state")]).isEqualTo(5f);
    assertThat(v[order.indexOf("park_i")]).isEqualTo(7f);
    assertThat(v[order.indexOf("times_through_order")]).isEqualTo(2f);
    assertThat(v[order.indexOf("at_bat_number_in_game")]).isEqualTo(12f);
    assertThat(v[order.indexOf("times_faced_today")]).isEqualTo(1f);
    assertThat(v[order.indexOf("ars_FF")]).isCloseTo(0.41f, within(1e-6f));
    assertThat(v[order.indexOf("ars_FF_by_count")]).isCloseTo(0.44f, within(1e-6f));
    assertThat(v[order.indexOf("pitcher_prior_n")]).isEqualTo(850f);
    assertThat(v[order.indexOf("prev1_pt_i")]).isEqualTo(4f);
    assertThat(v[order.indexOf("prev2_pt_i")]).isEqualTo(2f);
    assertThat(v[order.indexOf("prev1_missing")]).isEqualTo(0f);
    assertThat(v[order.indexOf("pitches_into_outing")]).isEqualTo(14f);
  }

  @Test
  void handedness_maps_L_to_0_and_R_to_1_independently() throws Exception {
    // The load-gate dummies are both "R", so without this the "L" branch and any stand/throws
    // transposition would ship green. Asymmetric inputs catch a swap of the two columns.
    FeaturePipelinePitchType pipeline = load();
    List<String> order = pipeline.spec().featureOrder();
    int standIdx = order.indexOf("stand_i");
    int throwsIdx = order.indexOf("throws_i");

    float[] standL = pipeline.transform(denseRequest("L", "R"));
    assertThat(standL[standIdx]).as("stand L").isEqualTo(0f);
    assertThat(standL[throwsIdx]).as("throws R").isEqualTo(1f);

    float[] throwsL = pipeline.transform(denseRequest("R", "L"));
    assertThat(throwsL[standIdx]).as("stand R").isEqualTo(1f);
    assertThat(throwsL[throwsIdx]).as("throws L").isEqualTo(0f);
  }

  @Test
  void unknown_and_null_parks_encode_the_contract_missing_value() throws Exception {
    FeaturePipelinePitchType pipeline = load();
    int parkIdx = pipeline.spec().featureOrder().indexOf("park_i");

    float[] unknown = pipeline.transform(denseRequest2("SEA"));
    assertThat(unknown[parkIdx]).as("unseen park").isEqualTo(-1f);

    // Null is a DEFINED case (Python maps then fills with UNKNOWN_PARK_CODE); it must encode -1,
    // not NPE on Map.copyOf's null-hostile get().
    float[] nullPark = pipeline.transform(denseRequest2(null));
    assertThat(nullPark[parkIdx]).as("null park").isEqualTo(-1f);
  }

  private static FeaturePipelinePitchType.Request denseRequest2(String park) {
    return new FeaturePipelinePitchType.Request(
        1, 1, 1, 5, 0, "R", "R", park, 1.0, 2.0, 0.0, 0.4, 0.2, 0.1, 0.1, 0.1, 0.05, 0.05, 0.4, 10,
        0, 0, 0, 1);
  }

  @Test
  void nulls_are_forwarded_as_NaN_not_defaulted_to_zero() throws Exception {
    // Load-bearing: LightGBM branches on NaN natively and the LR baseline's in-graph Imputer
    // replaces it with the training median. A 0.0 here would be a real observation to both and
    // would silently skew the prior toward whatever 0 means for that feature.
    FeaturePipelinePitchType pipeline = load();
    List<String> order = pipeline.spec().featureOrder();
    float[] v =
        pipeline.transform(
            new FeaturePipelinePitchType.Request(
                1, 1, 1, 5, 0, "R", "R", "NYY", null, null, null, null, null, null, null, null,
                null, null, null, 0, -1, -1, 1, 0));

    for (String col :
        List.of(
            "times_through_order",
            "at_bat_number_in_game",
            "times_faced_today",
            "ars_FF",
            "ars_SI",
            "ars_FC",
            "ars_SL",
            "ars_CU",
            "ars_CH",
            "ars_OFF",
            "ars_FF_by_count")) {
      assertThat(Float.isNaN(v[order.indexOf(col)])).as("%s must be NaN", col).isTrue();
    }
    // The sentinel-bearing columns are NOT nullable and must stay exact.
    assertThat(v[order.indexOf("prev1_pt_i")]).isEqualTo(-1f);
    assertThat(v[order.indexOf("prev1_missing")]).isEqualTo(1f);
    assertThat(v[order.indexOf("pitcher_prior_n")]).isEqualTo(0f);
  }

  @Test
  void a_stale_contract_schema_hash_fails_loud() throws Exception {
    // Rule 7 at LOAD time, not just registration: edit the contract without recomputing its hash
    // and the pipeline must refuse rather than serve a vector built from a drifted spec.
    String drifted =
        Files.readString(CONTRACT)
            .replace("\"pipeline_version\": \"1.0.0\"", "\"pipeline_version\": \"9.9.9\"");
    Path bad = artifactDir.resolve("feature_pipeline.json");
    Files.writeString(bad, drifted);
    Files.writeString(
        artifactDir.resolve("park_id_mapping.json"), "{\"park_id\":{},\"missing_value\":-1}");
    assertThatThrownBy(() -> FeaturePipelinePitchType.load(bad, artifactDir))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("schema_hash");
  }
}
