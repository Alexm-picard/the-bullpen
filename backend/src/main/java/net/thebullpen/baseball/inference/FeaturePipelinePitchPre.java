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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java mirror of the production pitch_outcome_pre feature pipeline (Phase 2a.8).
 *
 * <p>Reads {@code /contracts/feature_pipeline.json} for column order + transform rules, and the
 * per-model lookup files (park_id_mapping.json, pitcher_te.json, batter_te.json) for the data
 * needed to reproduce Tier 2 + categorical transforms at request time. Tier 3 (rolling form) values
 * come on the request body — for v1 the caller supplies them; Phase 3 introduces a worker-populated
 * {@code pitcher_form_current} table to avoid that.
 *
 * <p>The schema_hash check at load time matches {@link FeaturePipeline} (toy) byte-for-byte —
 * canonical JSON, sorted recursively, ASCII-escape non-ASCII. Drift between this code's view of the
 * contract and the file on disk is a hard fail.
 */
public final class FeaturePipelinePitchPre {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public record Spec(
      String modelName,
      String pipelineVersion,
      List<String> featureOrder,
      List<String> classLabels,
      String schemaHash,
      Map<String, Map<String, Object>> preprocess) {}

  public record Request(
      int countBalls,
      int countStrikes,
      int outs,
      int inning,
      int baseState,
      int scoreDiff,
      int dow,
      String pitcherThrows,
      String batterStand,
      String parkId,
      long pitcherId,
      long batterId,
      // Tier 3 form values — null treated as "missing" and forwarded as NaN to ONNX.
      Double pitcherPitchesLast28d,
      Double pitcherPitchesInGame,
      Double daysSinceLastAppearance,
      Double pitcherStrikeRate28d,
      Double pitcherSwstrikeRate28d,
      Double pitcherInplayRate28d,
      Double pitcherStrikeRateStd,
      Double batterStrikeRate28d,
      Double batterInplayRate28d,
      Double batterBallRate28d,
      Double batterInplayRateStd) {}

  /** Per-class TE values for a single entity_id (pitcher_id or batter_id). */
  record TeRow(
      double ball, double calledStrike, double swingingStrike, double foul, double inPlay) {
    double forClass(String cls) {
      return switch (cls) {
        case "ball" -> ball;
        case "called_strike" -> calledStrike;
        case "swinging_strike" -> swingingStrike;
        case "foul" -> foul;
        case "in_play" -> inPlay;
        default -> throw new IllegalArgumentException("unknown class: " + cls);
      };
    }
  }

  public record TeLookup(Map<Long, TeRow> rows, TeRow prior) {
    TeRow forEntity(long id) {
      return rows.getOrDefault(id, prior);
    }
  }

  public record ParkIdLookup(Map<String, Integer> mapping, int missingValue) {
    int forPark(String park) {
      Integer v = mapping.get(park);
      return v == null ? missingValue : v;
    }
  }

  private final Spec spec;
  private final ParkIdLookup parkIdLookup;
  private final TeLookup pitcherTe;
  private final TeLookup batterTe;

  public FeaturePipelinePitchPre(
      Spec spec, ParkIdLookup parkIdLookup, TeLookup pitcherTe, TeLookup batterTe) {
    this.spec = spec;
    this.parkIdLookup = parkIdLookup;
    this.pitcherTe = pitcherTe;
    this.batterTe = batterTe;
  }

  public Spec spec() {
    return spec;
  }

  public ParkIdLookup parkIdLookup() {
    return parkIdLookup;
  }

  public TeLookup pitcherTe() {
    return pitcherTe;
  }

  public TeLookup batterTe() {
    return batterTe;
  }

  public static FeaturePipelinePitchPre load(Path contractJson, Path artifactDir)
      throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(contractJson));
    verifySchemaHash(root);

    List<String> order = new ArrayList<>();
    root.get("feature_order").forEach(n -> order.add(n.asText()));

    List<String> classLabels = new ArrayList<>();
    root.get("output").get("labels").forEach(n -> classLabels.add(n.asText()));

    Map<String, Map<String, Object>> preprocess = new LinkedHashMap<>();
    JsonNode preNode = root.get("preprocess");
    preNode
        .fieldNames()
        .forEachRemaining(
            field -> {
              Map<String, Object> entry = new LinkedHashMap<>();
              Iterator<Map.Entry<String, JsonNode>> it = preNode.get(field).fields();
              while (it.hasNext()) {
                Map.Entry<String, JsonNode> kv = it.next();
                entry.put(kv.getKey(), unwrapJson(kv.getValue()));
              }
              preprocess.put(field, entry);
            });

    Spec parsed =
        new Spec(
            root.get("model_name").asText(),
            root.path("pipeline_version").asText("unknown"),
            List.copyOf(order),
            List.copyOf(classLabels),
            root.get("schema_hash").asText(),
            Collections.unmodifiableMap(preprocess));

    ParkIdLookup park = loadParkLookup(artifactDir.resolve("park_id_mapping.json"));
    TeLookup pitcher = loadTeLookup(artifactDir.resolve("pitcher_te.json"));
    TeLookup batter = loadTeLookup(artifactDir.resolve("batter_te.json"));
    return new FeaturePipelinePitchPre(parsed, park, pitcher, batter);
  }

  private static Object unwrapJson(JsonNode value) {
    if (value.isInt() || value.isLong()) return value.asLong();
    if (value.isFloatingPointNumber()) return value.asDouble();
    if (value.isBoolean()) return value.asBoolean();
    if (value.isNull()) return null;
    if (value.isObject() || value.isArray()) return value;
    return value.asText();
  }

  static ParkIdLookup loadParkLookup(Path path) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
    Map<String, Integer> mapping = new HashMap<>();
    JsonNode parkNode = root.get("park_id");
    parkNode.fieldNames().forEachRemaining(k -> mapping.put(k, parkNode.get(k).asInt()));
    int missing = root.path("missing_value").asInt(-1);
    return new ParkIdLookup(Map.copyOf(mapping), missing);
  }

  static TeLookup loadTeLookup(Path path) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
    String entityCol = root.get("entity_col").asText();
    JsonNode priorNode = root.get("prior");
    TeRow prior =
        new TeRow(
            priorNode.get("ball").asDouble(),
            priorNode.get("called_strike").asDouble(),
            priorNode.get("swinging_strike").asDouble(),
            priorNode.get("foul").asDouble(),
            priorNode.get("in_play").asDouble());
    Map<Long, TeRow> rows = new HashMap<>();
    for (JsonNode row : root.get("rows")) {
      long id = row.get(entityCol).asLong();
      rows.put(
          id,
          new TeRow(
              row.get("te_ball").asDouble(),
              row.get("te_called_strike").asDouble(),
              row.get("te_swinging_strike").asDouble(),
              row.get("te_foul").asDouble(),
              row.get("te_in_play").asDouble()));
    }
    return new TeLookup(Map.copyOf(rows), prior);
  }

  /**
   * Build the float32 ONNX input vector for one request. Must match the Python implementation
   * byte-for-byte on the parity fixture.
   */
  public float[] transform(Request req) {
    float[] out = new float[spec.featureOrder.size()];
    TeRow pitcher = pitcherTe.forEntity(req.pitcherId);
    TeRow batter = batterTe.forEntity(req.batterId);
    int parkInt = parkIdLookup.forPark(req.parkId);
    int throwsInt = "L".equals(req.pitcherThrows) ? 0 : 1;
    int standInt = "L".equals(req.batterStand) ? 0 : 1;

    for (int i = 0; i < spec.featureOrder.size(); i++) {
      String col = spec.featureOrder.get(i);
      out[i] = (float) compute(col, req, pitcher, batter, parkInt, throwsInt, standInt);
    }
    return out;
  }

  private double compute(
      String column,
      Request req,
      TeRow pitcher,
      TeRow batter,
      int parkInt,
      int throwsInt,
      int standInt) {
    return switch (column) {
      case "count_balls" -> req.countBalls;
      case "count_strikes" -> req.countStrikes;
      case "outs" -> req.outs;
      case "inning" -> req.inning;
      case "base_state" -> req.baseState;
      case "score_diff" -> req.scoreDiff;
      case "dow" -> req.dow;
      case "pitcher_throws_int" -> throwsInt;
      case "batter_stand_int" -> standInt;
      case "park_id_int" -> parkInt;
      case "pitcher_te_ball" -> pitcher.ball;
      case "pitcher_te_called_strike" -> pitcher.calledStrike;
      case "pitcher_te_swinging_strike" -> pitcher.swingingStrike;
      case "pitcher_te_foul" -> pitcher.foul;
      case "pitcher_te_in_play" -> pitcher.inPlay;
      case "batter_te_ball" -> batter.ball;
      case "batter_te_called_strike" -> batter.calledStrike;
      case "batter_te_swinging_strike" -> batter.swingingStrike;
      case "batter_te_foul" -> batter.foul;
      case "batter_te_in_play" -> batter.inPlay;
      case "pitcher_pitches_last_28d" -> nullableDouble(req.pitcherPitchesLast28d);
      case "pitcher_pitches_in_game" -> nullableDouble(req.pitcherPitchesInGame);
      case "days_since_last_appearance" -> nullableDouble(req.daysSinceLastAppearance);
      case "pitcher_strike_rate_28d" -> nullableDouble(req.pitcherStrikeRate28d);
      case "pitcher_swstrike_rate_28d" -> nullableDouble(req.pitcherSwstrikeRate28d);
      case "pitcher_inplay_rate_28d" -> nullableDouble(req.pitcherInplayRate28d);
      case "pitcher_strike_rate_std" -> nullableDouble(req.pitcherStrikeRateStd);
      case "batter_strike_rate_28d" -> nullableDouble(req.batterStrikeRate28d);
      case "batter_inplay_rate_28d" -> nullableDouble(req.batterInplayRate28d);
      case "batter_ball_rate_28d" -> nullableDouble(req.batterBallRate28d);
      case "batter_inplay_rate_std" -> nullableDouble(req.batterInplayRateStd);
      default -> throw new IllegalStateException("no transform rule for feature: " + column);
    };
  }

  private static double nullableDouble(Double v) {
    return v == null ? Double.NaN : v;
  }

  // --- Schema-hash verification (canonical JSON + ASCII escapes) ------------
  // Identical algorithm to FeaturePipeline (toy) so they can be unified later.

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
              + " — re-run the recompute snippet in .githooks/pre-commit");
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
