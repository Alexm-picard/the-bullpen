package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic JSON serializer that mirrors Python's
 *
 * <pre>{@code
 * json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
 * }</pre>
 *
 * <p>The defining property: the same JSON document — regardless of its source language or key
 * insertion order — produces an identical byte sequence. That byte sequence is what {@link
 * FeatureSchemaHasher} feeds into SHA-256 to enforce decision [67]'s feature-schema-hash discipline
 * at registration (rule 7).
 *
 * <p>Algorithm details (see also {@code .githooks/pre-commit} which uses the Python side):
 *
 * <ul>
 *   <li>Object keys sorted alphabetically, recursively.
 *   <li>No whitespace anywhere; pair separator is comma, key-value separator is colon.
 *   <li>Arrays preserve element order (semantic).
 *   <li>Strings: backslash-escape double-quote, backslash, control chars; non-ASCII (codepoint
 *       outside 0x20..0x7e) becomes a Python-style 6-char hex escape with lowercase hex.
 *   <li>Floats: rendered via {@link Double#toString(double)} (matches Python's repr for the values
 *       these contracts use; pure-integer floats keep the trailing {@code .0}).
 *   <li>No trailing newline.
 * </ul>
 *
 * <p>If a contract introduces values where Java/Python diverge (e.g. very small / very large floats
 * with exponent notation differences), the parity test {@code FeatureSchemaParityIT} will catch it
 * — extend this class rather than papering over the divergence.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  /** Serialize {@code node} to its canonical byte form (returned as a String, UTF-8 source). */
  public static String serialize(JsonNode node) {
    StringBuilder sb = new StringBuilder();
    writeNode(node, sb);
    return sb.toString();
  }

  /**
   * Return a deep copy of {@code root} with the {@code schema_hash} field replaced by an empty
   * string. Hashes computed over the result are stable across self-updates of the {@code
   * schema_hash} field itself — which is the trick {@code .githooks/pre-commit} also uses.
   */
  public static ObjectNode withZeroedSchemaHash(ObjectNode root) {
    ObjectNode copy = root.deepCopy();
    copy.put("schema_hash", "");
    return copy;
  }

  private static void writeNode(JsonNode node, StringBuilder sb) {
    if (node.isObject()) {
      sb.append('{');
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      node.fieldNames().forEachRemaining(k -> sorted.put(k, node.get(k)));
      boolean first = true;
      for (Map.Entry<String, JsonNode> entry : sorted.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeStringLiteral(entry.getKey(), sb);
        sb.append(':');
        writeNode(entry.getValue(), sb);
      }
      sb.append('}');
    } else if (node.isArray()) {
      sb.append('[');
      boolean first = true;
      for (JsonNode item : node) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeNode(item, sb);
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
      // Python's repr; matches Java's Double.toString for the contract value ranges.
      sb.append(node.doubleValue());
    } else {
      sb.append(node.asText());
    }
  }

  /**
   * Match Python's json.dumps default: escape double-quote, backslash, control chars; non-ASCII as
   * a 6-char hex escape.
   */
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
}
