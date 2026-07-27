package net.thebullpen.baseball.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.thebullpen.baseball.api.dto.PitchTypeRequest;
import org.junit.jupiter.api.Test;

/**
 * The two serving contracts for pitch_type that are cheap to state and expensive to discover
 * broken: the drift key set, and decision [183]'s no-top-1 framing.
 *
 * <p>Neither needs Spring, ClickHouse, or a registered model, which is the point - both would
 * otherwise only be checkable once something is promoted, and by then a wrong answer has already
 * been written into prod rows.
 */
class PitchTypeServingContractTest {

  /**
   * The eleven keys the merged {@code pitch_type_pre} entry in {@code distributions.py} watches.
   *
   * <p>Restated here on purpose. The Python pin guards the config against the Java DTO; this guards
   * the SERIALIZER against both. PSI joins observed to reference by EXACT key, so a serializer that
   * emits a different spelling produces an empty drift surface that raises nothing and looks
   * exactly like a healthy one.
   */
  private static final Set<String> WATCHED_WIRE_KEYS =
      Set.of(
          "balls",
          "strikes",
          "outs",
          "inning",
          "baseState",
          "stand",
          "pThrows",
          "parkId",
          "timesThroughOrder",
          "atBatNumberInGame",
          "timesFacedToday");

  private static PitchTypeRequest sampleRequest() {
    return new PitchTypeRequest(
        4242L,
        700_001L,
        LocalDate.of(2024, 5, 1),
        1,
        3,
        1,
        1,
        0,
        4,
        2,
        "R",
        "L",
        "PARK01",
        2.0,
        14.0,
        1.0);
  }

  private PitchTypePredictionService serviceForSerialization() {
    // Only the ObjectMapper participates in serializeFeatures; the rest are never touched on this
    // path, so nulls keep the test to the contract under examination instead of standing up an
    // inference stack to read a map.
    return new PitchTypePredictionService(null, null, null, null, new ObjectMapper());
  }

  @Test
  void logged_features_are_exactly_the_watched_wire_keys() throws Exception {
    String json = serviceForSerialization().serializeFeatures(sampleRequest());
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);

    assertThat(parsed.keySet())
        .as(
            "prediction_log.features must carry exactly the keys CHAMPIONS watches; PSI joins by"
                + " exact key and a mismatch writes nothing while raising nothing")
        .isEqualTo(WATCHED_WIRE_KEYS);
  }

  @Test
  void logged_features_use_pitch_type_spellings_not_the_pitch_outcome_ones() throws Exception {
    // THE COPY-THE-NEIGHBOUR TRAP, asserted on the serializer rather than the config. Both
    // pitch-outcome heads spell these batterStand / pitcherThrows.
    String json = serviceForSerialization().serializeFeatures(sampleRequest());
    assertThat(json).contains("\"stand\"").contains("\"pThrows\"");
    assertThat(json).doesNotContain("batterStand").doesNotContain("pitcherThrows");
  }

  @Test
  void identity_fields_are_not_logged_as_drift_features() throws Exception {
    // pitcherId / gameId / gameDate / atBatIndex / pitchNumber exist so the career prior can be
    // derived server-side. They are excluded from the drift config deliberately, so logging them
    // would put keys in the observed distribution that the reference side has no counterpart for.
    String json = serviceForSerialization().serializeFeatures(sampleRequest());
    assertThat(json)
        .doesNotContain("pitcherId")
        .doesNotContain("gameId")
        .doesNotContain("gameDate")
        .doesNotContain("atBatIndex")
        .doesNotContain("pitchNumber");
  }

  @Test
  void the_response_exposes_no_top_1_claim() {
    // DECISION [183] AS A PROPERTY OF THE TYPE. Top-1 is ~0.45, so this model is promoted on
    // calibration and must never read as "the next pitch will be X". The pitch-OUTCOME response
    // legitimately carries a winner because its claim is different; the contrast between the two
    // is what documents [183]. A field added here would make the misreading available again, and
    // no caption or README would undo it.
    List<String> names =
        Arrays.stream(PredictPitchTypeController.PitchTypePriorResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertThat(names)
        .as("no argmax-shaped field may exist on the prior response")
        .doesNotContain("winner", "predictedType", "topPitch", "prediction", "mostLikely");
    assertThat(names).contains("probabilities", "priorPitches");
  }

  @Test
  void the_response_reports_the_support_the_prior_was_computed_over() {
    // priorPitches is honesty about support: a prior over 12 career pitches and one over 12,000
    // are not comparable, and a bare distribution hides that entirely.
    List<String> names =
        Arrays.stream(PredictPitchTypeController.PitchTypePriorResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    assertThat(names).contains("priorPitches");
  }
}
