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
 * Java mirror of the production pitch_outcome_post feature pipeline (Phase 2b.3).
 *
 * <p>Sister class to {@link FeaturePipelinePitchPre}. Same shape — reads {@code
 * /contracts/feature_pipeline_post.json} for column order + transform rules and the per-model
 * lookup files ({@code park_id_mapping.json}, {@code pitch_type_mapping.json}, {@code
 * pitcher_te.json}, {@code batter_te.json}). Reuses the Pre pipeline's Tier 2 lookups verbatim
 * (same TE files on disk; the encoding is head-agnostic). Adds {@code pitch_type_int} as a fourth
 * integer-encoded categorical alongside park_id_int.
 *
 * <p>The 10 Tier 4 columns (pitch_type_int, release_speed_mph, plate location, movement vectors,
 * spin, release position) come in on the request body. The controller layer enforces that all 10
 * are non-null when {@code head=post}; here we treat any null as {@link Double#NaN} so LightGBM
 * handles it natively (the pre-2024 training rows are mostly Tier-4-null and the model learned to
 * tolerate that).
 *
 * <p>The pre + post pipelines are 80 % the same code today. Once 2b.3 lands and we have parity
 * proof on both, a small follow-up could pull the shared bits (schema_hash check, lookup loaders,
 * TeRow record) into a base class — out of scope for this leaf.
 */
public final class FeaturePipelinePitchPost {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  public record Spec(
      String modelName,
      String pipelineVersion,
      List<String> featureOrder,
      List<String> classLabels,
      String schemaHash,
      Map<String, Map<String, Object>> preprocess) {}

  /**
   * Post-head request body. Tier 1+2+3 fields mirror {@link FeaturePipelinePitchPre.Request}; Tier
   * 4 fields are <em>required</em> for a post-head call (the controller validates non-null before
   * invoking us) but typed Nullable here so the same record can model a partially-formed request
   * during validation + a fully-formed request at inference time.
   */
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
      // Tier 3
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
      Double batterInplayRateStd,
      // Tier 4 — required when head=post
      String pitchType,
      Double releaseSpeedMph,
      Double plateXIn,
      Double plateZIn,
      Double pfxXIn,
      Double pfxZIn,
      Double spinRateRpm,
      Double spinAxisDeg,
      Double releasePosXIn,
      Double releasePosZIn) {}

  /** Per-class TE values for a single entity_id (pitcher_id or batter_id). */
  record TeRow(
      double ball, double calledStrike, double swingingStrike, double foul, double inPlay) {}

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

  public record PitchTypeLookup(Map<String, Integer> mapping, int missingValue) {
    int forPitchType(String pt) {
      if (pt == null || pt.isEmpty()) return missingValue;
      Integer v = mapping.get(pt);
      return v == null ? missingValue : v;
    }
  }

  private final Spec spec;
  private final ParkIdLookup parkIdLookup;
  private final PitchTypeLookup pitchTypeLookup;
  private final TeLookup pitcherTe;
  private final TeLookup batterTe;

  public FeaturePipelinePitchPost(
      Spec spec,
      ParkIdLookup parkIdLookup,
      PitchTypeLookup pitchTypeLookup,
      TeLookup pitcherTe,
      TeLookup batterTe) {
    this.spec = spec;
    this.parkIdLookup = parkIdLookup;
    this.pitchTypeLookup = pitchTypeLookup;
    this.pitcherTe = pitcherTe;
    this.batterTe = batterTe;
  }

  public Spec spec() {
    return spec;
  }

  public ParkIdLookup parkIdLookup() {
    return parkIdLookup;
  }

  public PitchTypeLookup pitchTypeLookup() {
    return pitchTypeLookup;
  }

  public TeLookup pitcherTe() {
    return pitcherTe;
  }

  public TeLookup batterTe() {
    return batterTe;
  }

  public static FeaturePipelinePitchPost load(Path contractJson, Path artifactDir)
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
    PitchTypeLookup pitchType = loadPitchTypeLookup(artifactDir.resolve("pitch_type_mapping.json"));
    TeLookup pitcher = loadTeLookup(artifactDir.resolve("pitcher_te.json"));
    TeLookup batter = loadTeLookup(artifactDir.resolve("batter_te.json"));
    return new FeaturePipelinePitchPost(parsed, park, pitchType, pitcher, batter);
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

  static PitchTypeLookup loadPitchTypeLookup(Path path) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
    Map<String, Integer> mapping = new HashMap<>();
    JsonNode ptNode = root.get("pitch_type");
    ptNode.fieldNames().forEachRemaining(k -> mapping.put(k, ptNode.get(k).asInt()));
    int missing = root.path("missing_value").asInt(-1);
    return new PitchTypeLookup(Map.copyOf(mapping), missing);
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
    int pitchTypeInt = pitchTypeLookup.forPitchType(req.pitchType);
    int throwsInt = "L".equals(req.pitcherThrows) ? 0 : 1;
    int standInt = "L".equals(req.batterStand) ? 0 : 1;

    for (int i = 0; i < spec.featureOrder.size(); i++) {
      String col = spec.featureOrder.get(i);
      out[i] =
          (float) compute(col, req, pitcher, batter, parkInt, pitchTypeInt, throwsInt, standInt);
    }
    return out;
  }

  private double compute(
      String column,
      Request req,
      TeRow pitcher,
      TeRow batter,
      int parkInt,
      int pitchTypeInt,
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
      case "pitch_type_int" -> pitchTypeInt;
      case "release_speed_mph" -> nullableDouble(req.releaseSpeedMph);
      case "plate_x_in" -> nullableDouble(req.plateXIn);
      case "plate_z_in" -> nullableDouble(req.plateZIn);
      case "pfx_x_in" -> nullableDouble(req.pfxXIn);
      case "pfx_z_in" -> nullableDouble(req.pfxZIn);
      case "spin_rate_rpm" -> nullableDouble(req.spinRateRpm);
      case "spin_axis_deg" -> nullableDouble(req.spinAxisDeg);
      case "release_pos_x_in" -> nullableDouble(req.releasePosXIn);
      case "release_pos_z_in" -> nullableDouble(req.releasePosZIn);
      default -> throw new IllegalStateException("no transform rule for feature: " + column);
    };
  }

  private static double nullableDouble(Double v) {
    return v == null ? Double.NaN : v;
  }

  // --- Schema-hash verification (canonical JSON + ASCII escapes) ------------
  // Identical algorithm to FeaturePipelinePitchPre — see that class for the
  // rationale (matches the python pre-commit hook's sort_keys=True / no-whitespace
  // / ASCII-escape sha256). When a follow-up unifies these, this duplicated
  // block is the second target.

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
