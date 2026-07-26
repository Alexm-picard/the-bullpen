package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.registry.FeatureSchemaHasher;

/**
 * Java mirror of the Python preprocess defined in /contracts/feature_pipeline_pitchtype.json - the
 * pitch-TYPE head's feature pipeline (decision [183]).
 *
 * <p>Serves BOTH pitch-type models: {@code pitch_type_pre} (LightGBM) and its rule-9 baseline
 * {@code pitch_type_lr_baseline} (sklearn). They are separate registry rows sharing one contract,
 * which is exactly what makes [183]'s log-loss guardrail apples-to-apples - so they must also share
 * one feature pipeline, or the guardrail would be comparing two different feature vectors.
 *
 * <p>Encode-only by design: this builds the 24-column vector in the contract's declared order and
 * nothing else. No imputation, no standardisation. The LR baseline needs both, which is precisely
 * why its ONNX graph carries the Imputer and Scaler INSIDE it (see {@code
 * bullpen_training.pitch_type.export_lr_onnx}); LightGBM handles NaN natively. Missing values are
 * therefore forwarded as {@code NaN} rather than defaulted - a zero here would be a real
 * observation to both models and would silently skew the prior.
 *
 * <p>The schema hash is verified through {@link FeatureSchemaHasher} rather than a local copy of
 * the canonical-JSON algorithm, following {@link FeaturePipeline}: the algorithm lives in exactly
 * one place and stays in lockstep with the Python side.
 */
public final class FeaturePipelinePitchType {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The contract's declared shape. {@code classLabels} is the y7 taxonomy, in ONNX column order.
   */
  public record Spec(
      String modelName,
      String pipelineVersion,
      List<String> featureOrder,
      List<String> classLabels,
      String schemaHash) {}

  /**
   * The raw, pre-encoding inputs. Nullable fields are the ones the store declares Nullable - the
   * three V013 Tier-S columns and the eight career-expanding ARS columns, which are NULL at a
   * pitcher's first career pitch. They arrive here as {@code null} and leave as {@code NaN}.
   */
  public record Request(
      int balls,
      int strikes,
      int outs,
      int inning,
      int baseState,
      String stand,
      String pThrows,
      String parkId,
      Double timesThroughOrder,
      Double atBatNumberInGame,
      Double timesFacedToday,
      Double arsFf,
      Double arsSi,
      Double arsFc,
      Double arsSl,
      Double arsCu,
      Double arsCh,
      Double arsOff,
      Double arsFfByCount,
      int pitcherPriorN,
      int prev1PitchTypeInt,
      int prev2PitchTypeInt,
      int prev1Missing,
      int pitchesIntoOuting) {}

  /** park_id -&gt; int, with the contract's declared missing_value for an unseen park. */
  public record ParkIdLookup(Map<String, Integer> mapping, int missingValue) {
    int forPark(String park) {
      // Null-guarded: mapping is Map.copyOf, whose get() throws on a null key, and a null
      // park_id is a DEFINED case on the training side (Python maps then fillna's to
      // UNKNOWN_PARK_CODE). Without this a null park would NPE instead of encoding -1.
      if (park == null) {
        return missingValue;
      }
      Integer v = mapping.get(park);
      return v == null ? missingValue : v;
    }
  }

  private final Spec spec;
  private final ParkIdLookup parkIdLookup;

  public FeaturePipelinePitchType(Spec spec, ParkIdLookup parkIdLookup) {
    this.spec = spec;
    this.parkIdLookup = parkIdLookup;
  }

  public Spec spec() {
    return spec;
  }

  public ParkIdLookup parkIdLookup() {
    return parkIdLookup;
  }

  public static FeaturePipelinePitchType load(Path contractJson, Path artifactDir)
      throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(contractJson));
    verifySchemaHash(root, contractJson);

    List<String> order = new ArrayList<>();
    root.get("feature_order").forEach(n -> order.add(n.asText()));

    List<String> classLabels = new ArrayList<>();
    root.get("output").get("labels").forEach(n -> classLabels.add(n.asText()));

    Spec parsed =
        new Spec(
            root.get("model_name").asText(),
            root.path("pipeline_version").asText("unknown"),
            List.copyOf(order),
            List.copyOf(classLabels),
            root.get("schema_hash").asText());

    return new FeaturePipelinePitchType(
        parsed, loadParkLookup(artifactDir.resolve("park_id_mapping.json")));
  }

  static ParkIdLookup loadParkLookup(Path parkJson) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(parkJson));
    Map<String, Integer> mapping = new LinkedHashMap<>();
    JsonNode parks = root.get("park_id");
    if (parks == null) {
      throw new IllegalStateException(
          "park_id_mapping.json at " + parkJson + " has no park_id map");
    }
    parks.fields().forEachRemaining(kv -> mapping.put(kv.getKey(), kv.getValue().asInt()));
    return new ParkIdLookup(Map.copyOf(mapping), root.path("missing_value").asInt(-1));
  }

  /**
   * Build the model input vector in the contract's declared {@code feature_order}.
   *
   * <p>Driven by the contract rather than by a fixed field order, so a contract whose order drifts
   * from this switch fails loud on an unknown column instead of silently feeding the model a
   * permuted vector.
   */
  public float[] transform(Request req) {
    float[] out = new float[spec.featureOrder.size()];
    int parkInt = parkIdLookup.forPark(req.parkId);
    // categorical_map, per the contract: {"L": 0, "R": 1}, missing_value 1.
    int standInt = "L".equals(req.stand) ? 0 : 1;
    int throwsInt = "L".equals(req.pThrows) ? 0 : 1;
    for (int i = 0; i < out.length; i++) {
      out[i] = (float) compute(spec.featureOrder.get(i), req, parkInt, standInt, throwsInt);
    }
    return out;
  }

  private double compute(String column, Request req, int parkInt, int standInt, int throwsInt) {
    return switch (column) {
      case "balls" -> req.balls;
      case "strikes" -> req.strikes;
      case "outs" -> req.outs;
      case "inning" -> req.inning;
      case "base_state" -> req.baseState;
      case "stand_i" -> standInt;
      case "throws_i" -> throwsInt;
      case "park_i" -> parkInt;
      case "times_through_order" -> nullableDouble(req.timesThroughOrder);
      case "at_bat_number_in_game" -> nullableDouble(req.atBatNumberInGame);
      case "times_faced_today" -> nullableDouble(req.timesFacedToday);
      case "ars_FF" -> nullableDouble(req.arsFf);
      case "ars_SI" -> nullableDouble(req.arsSi);
      case "ars_FC" -> nullableDouble(req.arsFc);
      case "ars_SL" -> nullableDouble(req.arsSl);
      case "ars_CU" -> nullableDouble(req.arsCu);
      case "ars_CH" -> nullableDouble(req.arsCh);
      case "ars_OFF" -> nullableDouble(req.arsOff);
      case "ars_FF_by_count" -> nullableDouble(req.arsFfByCount);
      case "pitcher_prior_n" -> req.pitcherPriorN;
      case "prev1_pt_i" -> req.prev1PitchTypeInt;
      case "prev2_pt_i" -> req.prev2PitchTypeInt;
      case "prev1_missing" -> req.prev1Missing;
      case "pitches_into_outing" -> req.pitchesIntoOuting;
      default -> throw new IllegalStateException("no transform rule for feature: " + column);
    };
  }

  private static double nullableDouble(Double v) {
    return v == null ? Double.NaN : v;
  }

  /**
   * Rule 7: the contract's declared schema_hash must equal the hash recomputed from its content.
   * Delegates to {@link FeatureSchemaHasher} so the canonical-JSON algorithm has exactly one
   * implementation on the Java side (mirrors {@link FeaturePipeline}).
   */
  private static void verifySchemaHash(JsonNode root, Path contractJson) {
    if (!root.has("schema_hash")) {
      throw new IllegalStateException("contract at " + contractJson + " missing schema_hash");
    }
    String declared = root.get("schema_hash").asText();
    String recomputed = new FeatureSchemaHasher().compute(contractJson);
    if (!declared.equals(recomputed)) {
      throw new IllegalStateException(
          "contract schema_hash mismatch at "
              + contractJson
              + ": declared="
              + declared
              + " recomputed="
              + recomputed);
    }
  }
}
