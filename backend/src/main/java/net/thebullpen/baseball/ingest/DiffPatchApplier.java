package net.thebullpen.baseball.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies MLB diffPatch responses (RFC 6902 JSON Patch with a {@code copy} extension) to an
 * in-memory GUMBO live-feed document, then re-parses into a {@link LiveGameFeed}.
 *
 * <p>The diffPatch endpoint ({@code /api/v1.1/game/{pk}/feed/live/diffPatch?startTimecode=<ts>})
 * returns a JSON array of {@code {"diff": [...]}} entries whose ops use paths into the full
 * document. Against a finished game or a stale timecode it returns the full document (non-array) -
 * the caller treats that as a full resync.
 *
 * <p>Fallback to a full fetch happens on any patch error, on a timecode gap > 60s, and on a 60s
 * consistency timer. Every fallback increments {@code bullpen_ingest_diffpatch_fallback_total} with
 * the reason label.
 */
class DiffPatchApplier {

  private static final Logger log = LoggerFactory.getLogger(DiffPatchApplier.class);
  private static final Duration MAX_TIMECODE_GAP = Duration.ofSeconds(60);
  private static final Duration CONSISTENCY_INTERVAL = Duration.ofSeconds(60);
  private static final Pattern ALL_PLAYS_INDEX =
      Pattern.compile("^/liveData/plays/allPlays/(\\d+)(?:/.*)?$");

  private final ObjectMapper mapper;
  private final MlbFeedParser parser;

  private JsonNode document;
  private String lastTimecode;
  private Instant lastFullFetchAt;
  private Instant lastTimecodeAdvancedAt;

  DiffPatchApplier(ObjectMapper mapper, MlbFeedParser parser) {
    this.mapper = mapper;
    this.parser = parser;
  }

  /** True when a full document has been loaded at least once. */
  boolean isInitialized() {
    return document != null;
  }

  /** Load a full feed document (initial fetch or fallback resync). */
  LiveGameFeed loadFull(String fullJson) throws IOException {
    this.document = mapper.readTree(fullJson);
    this.lastTimecode = extractTimecode(document);
    this.lastFullFetchAt = Instant.now();
    this.lastTimecodeAdvancedAt = Instant.now();
    return parser.parseLiveFeedFromNode(document);
  }

  /** The timecode to send as {@code startTimecode} on the next diffPatch request. */
  String lastTimecode() {
    return lastTimecode;
  }

  /**
   * Whether it is time for a periodic full-fetch consistency check. The spec calls for a 60s
   * consistency timer so the in-memory document does not silently drift from the source.
   */
  boolean isConsistencyCheckDue() {
    return lastFullFetchAt != null
        && Duration.between(lastFullFetchAt, Instant.now()).compareTo(CONSISTENCY_INTERVAL) >= 0;
  }

  /**
   * Apply a diffPatch response. Returns the resulting {@link LiveGameFeed}, or {@code null} when
   * the response is a full document (resync signal) - the caller should use {@link #loadFull}
   * instead.
   *
   * @return the parsed feed after patches, or null if the response was a full-document resync
   * @throws PatchFailedException when any patch op fails (caller should fall back to full fetch)
   */
  ApplyResult applyDiff(String diffJson) throws IOException, PatchFailedException {
    JsonNode root = mapper.readTree(diffJson);
    if (!root.isArray()) {
      return null;
    }
    Set<Integer> touchedPlays = new TreeSet<>();
    for (JsonNode entry : root) {
      JsonNode ops = entry.path("diff");
      if (!ops.isArray()) {
        continue;
      }
      for (JsonNode op : ops) {
        String path = op.path("path").asText("");
        applyOp(op, path);
        trackTouchedPlay(path, touchedPlays);
      }
    }
    String newTimecode = extractTimecode(document);
    if (newTimecode != null && !newTimecode.equals(lastTimecode)) {
      lastTimecodeAdvancedAt = Instant.now();
    }
    if (lastTimecode != null
        && newTimecode != null
        && !lastTimecode.equals(newTimecode)
        && timecodeGapExceeds(lastTimecode, newTimecode, MAX_TIMECODE_GAP)) {
      lastTimecode = newTimecode;
      throw new PatchFailedException("timecode_gap", "timecode gap > 60s");
    }
    lastTimecode = newTimecode;
    LiveGameFeed feed = parser.parseLiveFeedFromNode(document);
    return new ApplyResult(feed, touchedPlays);
  }

  private void applyOp(JsonNode op, String path) throws PatchFailedException {
    String operation = op.path("op").asText("");
    switch (operation) {
      case "replace" -> replaceAt(path, op.path("value"));
      case "add" -> addAt(path, op.path("value"));
      case "remove" -> removeAt(path);
      case "copy" -> copyAt(op.path("from").asText(""), path);
      default -> throw new PatchFailedException("unknown_op", "unknown patch op: " + operation);
    }
  }

  private void replaceAt(String path, JsonNode value) throws PatchFailedException {
    PathRef ref = resolve(path, false);
    if (ref.parent() instanceof ObjectNode obj) {
      obj.set(ref.key(), value.deepCopy());
    } else if (ref.parent() instanceof ArrayNode arr && ref.index() >= 0) {
      arr.set(ref.index(), value.deepCopy());
    } else {
      throw new PatchFailedException("replace_failed", "cannot replace at " + path);
    }
  }

  private void addAt(String path, JsonNode value) throws PatchFailedException {
    PathRef ref = resolve(path, true);
    if (ref.parent() instanceof ObjectNode obj) {
      obj.set(ref.key(), value.deepCopy());
    } else if (ref.parent() instanceof ArrayNode arr) {
      int idx = ref.index();
      if (idx < 0 || "-".equals(ref.key())) {
        arr.add(value.deepCopy());
      } else {
        arr.insert(idx, value.deepCopy());
      }
    } else {
      throw new PatchFailedException("add_failed", "cannot add at " + path);
    }
  }

  private void removeAt(String path) throws PatchFailedException {
    PathRef ref = resolve(path, false);
    if (ref.parent() instanceof ObjectNode obj) {
      obj.remove(ref.key());
    } else if (ref.parent() instanceof ArrayNode arr && ref.index() >= 0) {
      arr.remove(ref.index());
    } else {
      throw new PatchFailedException("remove_failed", "cannot remove at " + path);
    }
  }

  private void copyAt(String from, String to) throws PatchFailedException {
    PathRef src = resolve(from, false);
    JsonNode value;
    if (src.parent() instanceof ObjectNode obj) {
      value = obj.get(src.key());
    } else if (src.parent() instanceof ArrayNode arr && src.index() >= 0) {
      value = arr.get(src.index());
    } else {
      throw new PatchFailedException("copy_failed", "cannot read from " + from);
    }
    if (value == null) {
      throw new PatchFailedException("copy_failed", "source is null at " + from);
    }
    addAt(to, value);
  }

  private record PathRef(JsonNode parent, String key, int index) {}

  private PathRef resolve(String path, boolean createMissing) throws PatchFailedException {
    if (path.isEmpty() || path.charAt(0) != '/') {
      throw new PatchFailedException("bad_path", "invalid JSON pointer: " + path);
    }
    String[] segments = path.substring(1).split("/", -1);
    JsonNode current = document;
    for (int i = 0; i < segments.length - 1; i++) {
      String seg = unescapeJsonPointer(segments[i]);
      if (current instanceof ObjectNode obj) {
        JsonNode child = obj.get(seg);
        if (child == null && createMissing) {
          child = mapper.createObjectNode();
          obj.set(seg, child);
        }
        current = child;
      } else if (current instanceof ArrayNode arr) {
        int idx = parseIndex(seg);
        current = (idx >= 0 && idx < arr.size()) ? arr.get(idx) : null;
      } else {
        current = null;
      }
      if (current == null) {
        throw new PatchFailedException("path_not_found", "path segment missing: " + seg);
      }
    }
    String lastSeg = unescapeJsonPointer(segments[segments.length - 1]);
    int idx = (current instanceof ArrayNode) ? parseIndex(lastSeg) : -1;
    return new PathRef(current, lastSeg, idx);
  }

  private static int parseIndex(String seg) {
    if ("-".equals(seg)) {
      return -1;
    }
    try {
      return Integer.parseInt(seg);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static String unescapeJsonPointer(String seg) {
    return seg.replace("~1", "/").replace("~0", "~");
  }

  private static void trackTouchedPlay(String path, Set<Integer> touched) {
    Matcher m = ALL_PLAYS_INDEX.matcher(path);
    if (m.matches()) {
      touched.add(Integer.parseInt(m.group(1)));
    }
  }

  private static String extractTimecode(JsonNode doc) {
    JsonNode ts = doc.path("metaData").path("timeStamp");
    return ts.isTextual() ? ts.asText() : null;
  }

  /**
   * Heuristic timecode gap check. MLB timecodes are formatted as {@code yyyyMMdd_HHmmss}, so
   * parsing as a duration is a string comparison of fixed-width ISO-ish timestamps.
   */
  private static boolean timecodeGapExceeds(String old, String current, Duration max) {
    try {
      Instant oldI = parseTimecode(old);
      Instant curI = parseTimecode(current);
      if (oldI == null || curI == null) {
        return false;
      }
      return Duration.between(oldI, curI).abs().compareTo(max) > 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static Instant parseTimecode(String tc) {
    if (tc == null || tc.length() < 15) {
      return null;
    }
    try {
      int year = Integer.parseInt(tc.substring(0, 4));
      int month = Integer.parseInt(tc.substring(4, 6));
      int day = Integer.parseInt(tc.substring(6, 8));
      int hour = Integer.parseInt(tc.substring(9, 11));
      int min = Integer.parseInt(tc.substring(11, 13));
      int sec = Integer.parseInt(tc.substring(13, 15));
      return java.time.LocalDateTime.of(year, month, day, hour, min, sec)
          .toInstant(java.time.ZoneOffset.UTC);
    } catch (Exception e) {
      return null;
    }
  }

  record ApplyResult(LiveGameFeed feed, Set<Integer> touchedPlayIndices) {}

  static final class PatchFailedException extends Exception {
    private static final long serialVersionUID = 1L;
    final String reason;

    PatchFailedException(String reason, String message) {
      super(message);
      this.reason = reason;
    }
  }
}
