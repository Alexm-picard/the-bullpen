package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirror of the Python preprocess defined in feature_pipeline.json (Phase 1.4).
 *
 * <p>The Python and Java pipelines read the same JSON contract — when the spec file changes the
 * schema_hash flips and any consumer that pinned a particular hash refuses to start (registry rule
 * lands in Phase 3a). Here we only parse the rules we know how to handle and throw on any unknown
 * rule kind so a future Python-side change can't silently diverge from Java.
 */
public final class FeaturePipeline {

  public record Spec(
      String modelName,
      String version,
      List<String> featureOrder,
      String schemaHash,
      Map<String, Map<String, Object>> preprocess,
      Map<String, Double> parkHrRate,
      double parkHrRateFallback) {}

  private final Spec spec;

  public FeaturePipeline(Spec spec) {
    this.spec = spec;
  }

  public static FeaturePipeline load(Path pipelineJson, Path parkLookupJson) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(Files.readAllBytes(pipelineJson));

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

    JsonNode lookup = mapper.readTree(Files.readAllBytes(parkLookupJson));
    Map<String, Double> parkRate = new HashMap<>();
    lookup.fieldNames().forEachRemaining(team -> parkRate.put(team, lookup.get(team).asDouble()));
    double fallback =
        parkRate.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

    Spec parsed =
        new Spec(
            root.get("model_name").asText(),
            root.get("version").asText(),
            List.copyOf(order),
            root.get("schema_hash").asText(),
            preprocess,
            Map.copyOf(parkRate),
            fallback);
    return new FeaturePipeline(parsed);
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
