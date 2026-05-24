package net.thebullpen.baseball.inference;

import java.util.Locale;

/**
 * Which pitch-outcome head a {@code POST /v1/predict/pitch} request targets — Phase 2b.3.
 *
 * <p>Pre is the default for callers that don't pass {@code ?head=}, keeping the 2a.8 contract
 * unchanged. Post requires Tier 4 (post-pitch) fields on the request body; the controller validates
 * that cross-field rule before dispatching.
 *
 * <p>Decision [35]: two heads = two registered models. They share the same endpoint URL but
 * dispatch to separate ONNX sessions + calibrators + feature pipelines.
 */
public enum Head {
  PRE,
  POST;

  /** Lenient parser for the query-string value — accepts "pre" / "PRE" / "Pre". */
  public static Head parse(String raw) {
    if (raw == null || raw.isEmpty()) {
      return PRE;
    }
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "pre" -> PRE;
      case "post" -> POST;
      default ->
          throw new IllegalArgumentException("head must be 'pre' or 'post' (got '" + raw + "')");
    };
  }

  public String lower() {
    return name().toLowerCase(Locale.ROOT);
  }
}
