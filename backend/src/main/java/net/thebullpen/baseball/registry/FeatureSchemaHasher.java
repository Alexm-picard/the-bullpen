package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * Compute the canonical SHA-256 hash of a {@code feature_pipeline.json} file. The hash is the
 * fingerprint the registry uses to enforce rule 7 (refuse model registrations whose schema hash
 * does not match the production pipeline) and decision [67] (Python and Java must agree on the
 * exact byte sequence).
 *
 * <p>Algorithm — must stay in lockstep with the Python implementation in {@code
 * bullpen_training.registry_client.feature_hasher}:
 *
 * <ol>
 *   <li>Parse the file as JSON.
 *   <li>Replace the top-level {@code schema_hash} field with an empty string (so the hash is stable
 *       across self-updates of the {@code schema_hash} field itself — the {@code
 *       .githooks/pre-commit} trick).
 *   <li>Serialize via {@link CanonicalJson#serialize(JsonNode)}.
 *   <li>SHA-256 the UTF-8 bytes; return lowercase-hex digest.
 * </ol>
 *
 * <p>Returns the raw 64-char hex digest (no {@code sha256:} prefix) so the value drops straight
 * into the {@code model_versions.feature_schema_hash} column.
 */
@Component
public class FeatureSchemaHasher {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Compute the canonical hash of the JSON at {@code featurePipelinePath}. */
  public String compute(Path featurePipelinePath) {
    String content;
    try {
      content = Files.readString(featurePipelinePath);
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "registry: cannot read feature pipeline at " + featurePipelinePath, e);
    }
    return computeFromContent(content);
  }

  /** Hash a JSON string in-memory (used by tests + the parity fixtures). */
  public String computeFromContent(String jsonContent) {
    JsonNode root;
    try {
      root = MAPPER.readTree(jsonContent);
    } catch (IOException e) {
      throw new IllegalArgumentException("registry: feature pipeline is not valid JSON", e);
    }
    if (!(root instanceof ObjectNode obj)) {
      throw new IllegalArgumentException(
          "registry: feature pipeline root must be a JSON object, got " + root.getNodeType());
    }
    ObjectNode canonical = CanonicalJson.withZeroedSchemaHash(obj);
    String canonicalJson = CanonicalJson.serialize(canonical);
    return sha256Hex(canonicalJson);
  }

  static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available on this JVM", e);
    }
  }
}
