package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java mirror of the Python preprocess defined in /contracts/feature_pipeline.json.
 *
 * <p>The contract file is the single source of truth (CLAUDE.md rule 7). On {@link #load(Path,
 * Path)} we recompute the schema_hash with the field set to empty string and reject the file if the
 * declared hash doesn't match — same algorithm as {@code .githooks/pre-commit}. That way Java
 * refuses to start against a hand-edited contract whose hash was forgotten.
 *
 * <p>Unknown preprocess rule kinds throw at load time so a future Python-side schema change can't
 * silently degrade Java behaviour.
 */
public final class FeaturePipeline {

  public record Spec(
      String modelName,
      String pipelineVersion,
      List<String> featureOrder,
      String schemaHash,
      Map<String, Map<String, Object>> preprocess,
      Map<String, Double> parkHrRate,
      double parkHrRateFallback) {}

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  private final Spec spec;

  public FeaturePipeline(Spec spec) {
    this.spec = spec;
  }

  public static FeaturePipeline load(Path contractJson, Path parkLookupJson) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(contractJson));
    verifySchemaHash(root);

    List<String> order = new java.util.ArrayList<>();
    root.get("feature_order").forEach(n -> order.add(n.asText()));

    Map<String, Map<String, Object>> preprocess = new LinkedHashMap<>();
    JsonNode preNode = root.get("preprocess");
    preNode
        .fieldNames()
        .forEachRemaining(
            field -> {
              Map<String, Object> entry = new HashMap<>();
              preNode
                  .get(field)
                  .fieldNames()
                  .forEachRemaining(k -> entry.put(k, preNode.get(field).get(k).asText()));
              preprocess.put(field, entry);
            });

    JsonNode lookup = MAPPER.readTree(Files.readAllBytes(parkLookupJson));
    Map<String, Double> parkRate = new HashMap<>();
    lookup.fieldNames().forEachRemaining(team -> parkRate.put(team, lookup.get(team).asDouble()));
    double fallback =
        parkRate.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

    Spec parsed =
        new Spec(
            root.get("model_name").asText(),
            root.path("pipeline_version").asText("unknown"),
            List.copyOf(order),
            root.get("schema_hash").asText(),
            preprocess,
            Map.copyOf(parkRate),
            fallback);
    return new FeaturePipeline(parsed);
  }

  /**
   * Recompute schema_hash and reject on mismatch — must match the Python algorithm in
   * .githooks/pre-commit exactly:
   *
   * <pre>
   *   json.dumps(canonical, sort_keys=True, separators=(",", ":"))
   * </pre>
   *
   * <p>That means: schema_hash field set to empty string, all object keys sorted **recursively**,
   * no whitespace. Jackson preserves ObjectNode insertion order by default, so we re-emit through a
   * sorted TreeMap walker.
   */
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
    String canonicalJson = serializeCanonical(canonical);
    String recomputed = sha256Hex(canonicalJson);
    if (!declared.equals(recomputed)) {
      throw new IllegalStateException(
          "contract schema_hash mismatch: declared="
              + declared
              + " recomputed="
              + recomputed
              + " — someone hand-edited the JSON without updating the hash (see .githooks/pre-commit)");
    }
  }

  /**
   * Walk the JsonNode tree emitting Python's {@code json.dumps(sort_keys=True, separators=(",",
   * ":"))} output: object keys sorted recursively, no whitespace, ASCII-safe escapes for non-ASCII
   * characters (Python's default {@code ensure_ascii=True}).
   */
  static String serializeCanonical(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    writeCanonical(node, sb);
    return sb.toString();
  }

  private static void writeCanonical(JsonNode node, StringBuilder sb) {
    if (node.isObject()) {
      sb.append('{');
      java.util.TreeMap<String, JsonNode> sorted = new java.util.TreeMap<>();
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
      // Python repr for floats; Jackson's asText gives the same canonical form for the
      // values we care about (whole-number floats keep the .0 — match by formatting through
      // Double.toString which matches Python for the values in this contract).
      sb.append(node.doubleValue());
    } else {
      sb.append(node.asText());
    }
  }

  /** Match Python's json.dumps default — escape \", \\, control chars, non-ASCII as \\uXXXX. */
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

  public Spec spec() {
    return spec;
  }

  /**
   * Transform a single raw input row to the float32 feature vector ONNX expects. Returns a row
   * whose order matches {@link Spec#featureOrder()}.
   */
  public float[] transform(RawRow row) {
    float[] out = new float[spec.featureOrder.size()];
    for (int i = 0; i < spec.featureOrder.size(); i++) {
      String col = spec.featureOrder.get(i);
      out[i] = (float) compute(col, row);
    }
    return out;
  }

  private double compute(String column, RawRow row) {
    Map<String, Object> rule = spec.preprocess.get(column);
    if (rule == null) {
      throw new IllegalStateException("no preprocess rule for column: " + column);
    }
    String kind = (String) rule.get("type");
    return switch (kind) {
      case "passthrough" -> requireNumeric(column, row.numeric().get(column));
      case "target_encoding" ->
          spec.parkHrRate.getOrDefault(row.categorical().get("park_id"), spec.parkHrRateFallback);
      case "boolean_eq" -> {
        String sourceCol = (String) rule.get("source_column");
        String match = (String) rule.get("match_value");
        String observed = row.categorical().get(sourceCol);
        yield match.equals(observed) ? 1.0 : 0.0;
      }
      default -> throw new IllegalStateException("unsupported preprocess rule kind: " + kind);
    };
  }

  private static double requireNumeric(String column, Double value) {
    if (value == null) {
      throw new IllegalArgumentException(
          "null value for required numeric feature '" + column + "' (toy model has no imputer)");
    }
    return value;
  }

  /** A raw input row — numeric features keyed by column name, categoricals likewise. */
  public record RawRow(Map<String, Double> numeric, Map<String, String> categorical) {}
}
