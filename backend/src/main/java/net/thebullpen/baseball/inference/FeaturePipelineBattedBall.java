package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java mirror of the Phase 2c batted-ball outcome feature pipeline (decision [45]; re-scoped to a
 * per-park outcome model by decision [141]).
 *
 * <p>Sister class to {@link FeaturePipelinePitchPost}. Reads {@code
 * /contracts/feature_pipeline_battedball.json} for the 15-column feature order + the schema_hash
 * (rule 7), and the trained model's {@code metadata.json} for the per-feature {@code
 * feature_scaler} (means / stds). {@link #transform} builds the raw 15-feature vector from a
 * request and applies the same z-score the Python training path applies ({@code
 * FeatureScaler.transform}: {@code (x - mean) / std}, with the one-hot columns carrying mean 0 /
 * std 1 so they pass through unchanged) — so the float32 vector handed to ONNX matches Python
 * byte-for-byte on the parity fixture (2c serving).
 *
 * <p>Unlike the pitch pipelines there are no Tier-2/3/4 lookups: every feature is built directly
 * from the request fields (launch kinematics + handedness + base/out state). The park dimension is
 * an <em>output</em> axis (the model emits a per-park outcome distribution), not an input feature,
 * so {@code park_id} is absent here.
 *
 * <p>The schema-hash verification block duplicates {@link FeaturePipelinePitchPost} verbatim (same
 * canonical-JSON + SHA-256 algorithm as the {@code .githooks/pre-commit} Python snippet and {@code
 * FeatureSchemaHasher}); a future base-class refactor is the place to deduplicate.
 */
public final class FeaturePipelineBattedBall {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public record Spec(
      String modelName,
      String pipelineVersion,
      List<String> featureOrder,
      List<String> classLabels,
      int nParks,
      String schemaHash) {}

  /**
   * Phase 4 carry-target standardisation, read from {@code metadata.json:carry_target} (nullable -
   * absent on a probabilities-only model). The model's carry output is standardised; serving
   * recovers feet via {@code ft = raw * stdFt + meanFt}. Lives in per-model metadata (like {@code
   * feature_scaler}), NOT the hashed contract, so carry is an additive capability that doesn't
   * change the feature schema hash.
   */
  public record CarryTarget(double meanFt, double stdFt) {

    /** Recover carry distance in feet from a standardised model output. */
    public double toFeet(double standardised) {
      return standardised * stdFt + meanFt;
    }
  }

  /**
   * Batted-ball request body. The park axis is an output dimension, so no {@code parkId} here.
   * Spray angle is supplied directly (the Statcast {@code hc_x/hc_y -> spray} derivation is a
   * training-time concern); {@code stand} is "L" or "R" (unknown -> R, matching Python's {@code
   * stand_one_hot}).
   */
  public record Request(
      double launchSpeedMph,
      double launchAngleDeg,
      double sprayAngleDeg,
      double hitDistanceFt,
      String stand,
      int baseState,
      int outs) {}

  private final Spec spec;
  private final double[] means;
  private final double[] stds;
  private final CarryTarget carryTarget; // nullable: absent on a probabilities-only model

  private FeaturePipelineBattedBall(
      Spec spec, double[] means, double[] stds, CarryTarget carryTarget) {
    this.spec = spec;
    this.means = means;
    this.stds = stds;
    this.carryTarget = carryTarget;
  }

  public Spec spec() {
    return spec;
  }

  /** The carry-target standardisation, or {@code null} when the model has no carry head. */
  public CarryTarget carryTarget() {
    return carryTarget;
  }

  /**
   * Load the contract (feature order + schema_hash) and the model's metadata (feature_scaler).
   *
   * @param contractJson the {@code /contracts/feature_pipeline_battedball.json} contract
   * @param metadataJson the trained model's {@code metadata.json} sidecar (carries feature_scaler +
   *     park_order)
   */
  public static FeaturePipelineBattedBall load(Path contractJson, Path metadataJson)
      throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(contractJson));
    verifySchemaHash(root);

    List<String> order = new ArrayList<>();
    root.get("feature_order").forEach(n -> order.add(n.asText()));

    List<String> classLabels = new ArrayList<>();
    root.get("output").get("labels").forEach(n -> classLabels.add(n.asText()));
    int nParks = root.get("output").path("n_parks").asInt(0);

    Spec parsed =
        new Spec(
            root.get("model_name").asText(),
            root.path("pipeline_version").asText("unknown"),
            List.copyOf(order),
            List.copyOf(classLabels),
            nParks,
            root.get("schema_hash").asText());

    JsonNode meta = MAPPER.readTree(Files.readAllBytes(metadataJson));
    JsonNode scaler = meta.get("feature_scaler");
    if (scaler == null) {
      throw new IllegalStateException(
          "metadata.json missing feature_scaler — cannot z-score batted-ball features ("
              + metadataJson
              + ")");
    }
    double[] means = readDoubleArray(scaler.get("means"));
    double[] stds = readDoubleArray(scaler.get("stds"));
    int n = parsed.featureOrder().size();
    if (means.length != n || stds.length != n) {
      throw new IllegalStateException(
          "feature_scaler length mismatch: feature_order="
              + n
              + " means="
              + means.length
              + " stds="
              + stds.length);
    }
    CarryTarget carryTarget = readCarryTarget(meta.get("carry_target"));
    return new FeaturePipelineBattedBall(parsed, means, stds, carryTarget);
  }

  /**
   * Parse the optional {@code carry_target} block. Absent (or null) -> a probabilities-only model,
   * returns {@code null}. Present but malformed (missing mean/std, non-positive std) is a snapshot
   * defect and fails loud rather than serving a silently-wrong carry.
   */
  private static CarryTarget readCarryTarget(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    JsonNode mean = node.get("mean_ft");
    JsonNode std = node.get("std_ft");
    if (mean == null || std == null) {
      throw new IllegalStateException(
          "metadata.json carry_target must carry mean_ft + std_ft, got " + node);
    }
    double stdFt = std.asDouble();
    if (!(stdFt > 0.0)) {
      throw new IllegalStateException(
          "metadata.json carry_target std_ft must be > 0, got " + stdFt);
    }
    return new CarryTarget(mean.asDouble(), stdFt);
  }

  private static double[] readDoubleArray(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalStateException("expected a numeric array in feature_scaler");
    }
    double[] out = new double[node.size()];
    for (int i = 0; i < node.size(); i++) {
      out[i] = node.get(i).asDouble();
    }
    return out;
  }

  /**
   * Build the scaled float32 ONNX input vector for one request. Matches the Python {@code
   * FeatureScaler.transform(_build_features(...))} path byte-for-byte on the parity fixture.
   */
  public float[] transform(Request req) {
    int n = spec.featureOrder().size();
    float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      double raw = computeRaw(spec.featureOrder().get(i), req);
      out[i] = (float) ((raw - means[i]) / stds[i]);
    }
    return out;
  }

  private double computeRaw(String column, Request req) {
    return switch (column) {
      case "launch_speed_mph" -> req.launchSpeedMph;
      case "launch_angle_deg" -> req.launchAngleDeg;
      case "spray_angle_deg" -> req.sprayAngleDeg;
      case "hit_distance_ft" -> req.hitDistanceFt;
      // stand one-hot: unknown / null -> R (Python stand_one_hot fallback).
      case "stand_R" -> "L".equals(req.stand) ? 0.0 : 1.0;
      case "stand_L" -> "L".equals(req.stand) ? 1.0 : 0.0;
      case "base_state_0" -> req.baseState == 0 ? 1.0 : 0.0;
      case "base_state_1" -> req.baseState == 1 ? 1.0 : 0.0;
      case "base_state_2" -> req.baseState == 2 ? 1.0 : 0.0;
      case "base_state_3" -> req.baseState == 3 ? 1.0 : 0.0;
      case "base_state_4" -> req.baseState == 4 ? 1.0 : 0.0;
      case "base_state_5" -> req.baseState == 5 ? 1.0 : 0.0;
      case "base_state_6" -> req.baseState == 6 ? 1.0 : 0.0;
      case "base_state_7" -> req.baseState == 7 ? 1.0 : 0.0;
      case "outs" -> req.outs;
      default -> throw new IllegalStateException("no transform rule for feature: " + column);
    };
  }

  // --- Schema-hash verification (canonical JSON + ASCII escapes) ------------
  // Identical algorithm to FeaturePipelinePitchPost / FeatureSchemaHasher / the
  // .githooks/pre-commit Python snippet (sort_keys=True, no whitespace, ASCII
  // escapes, sha256 over the file with schema_hash zeroed). When a follow-up
  // unifies these, this duplicated block is one of the targets.

  private static void verifySchemaHash(JsonNode root) {
    if (!root.has("schema_hash")) {
      throw new IllegalStateException("contract missing schema_hash field");
    }
    String declared = root.get("schema_hash").asText();
    if (!(root instanceof ObjectNode)) {
      throw new IllegalStateException("contract root is not a JSON object");
    }
    ObjectNode canonical = ((ObjectNode) root).deepCopy();
    canonical.put("schema_hash", "");
    String recomputed = sha256Hex(serializeCanonical(canonical));
    if (!declared.equals(recomputed)) {
      throw new IllegalStateException(
          "contract schema_hash mismatch: declared="
              + declared
              + " recomputed="
              + recomputed
              + " - re-run the recompute snippet in .githooks/pre-commit");
    }
  }

  static String serializeCanonical(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    writeCanonical(node, sb);
    return sb.toString();
  }

  private static void writeCanonical(JsonNode node, StringBuilder sb) {
    if (node.isObject()) {
      sb.append('{');
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      node.fieldNames().forEachRemaining(k -> sorted.put(k, node.get(k)));
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) sb.append(',');
        first = false;
        writeStringLiteral(entry.getKey(), sb);
        sb.append(':');
        writeCanonical(entry.getValue(), sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      boolean first = true;
      for (JsonNode item : node) {
        if (!first) sb.append(',');
        first = false;
        writeCanonical(item, sb);
      }
      sb.append(']');
    } else if (node.isTextual()) {
      writeStringLiteral(node.asText(), sb);
    } else if (node.isNull()) {
      sb.append("null");
    } else if (node.isBoolean()) {
      sb.append(node.asBoolean() ? "true" : "false");
    } else if (node.isInt() || node.isLong() || node.isBigInteger()) {
      sb.append(node.asText());
    } else if (node.isFloatingPointNumber()) {
      sb.append(node.doubleValue());
    } else {
      sb.append(node.asText());
    }
  }

  private static void writeStringLiteral(String s, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20 || c > 0x7e) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) hex.append(String.format("%02x", b));
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
