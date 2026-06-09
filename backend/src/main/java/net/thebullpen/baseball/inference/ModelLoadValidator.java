package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.thebullpen.baseball.registry.RegistryException;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * INC-2 (decision [151]) promotion load-gate. Before a model is promoted ({@code -> CHAMPION}) or
 * first enters SHADOW, load it through the SAME loader serving will use and run one forward pass,
 * so a non-loadable or mis-wired snapshot is rejected at promote-time with a 422 instead of 500ing
 * live at serving (the 2026-06-07 first-champion incident: an incomplete copy-set + a list-format
 * calibrator passed the file-existence + schema-hash checks, then 500'd at {@code /all-parks}).
 *
 * <p>The loader is resolved from the model's OWN snapshot metadata, NOT hardcoded by model name
 * (mirrors how serving resolves the loader by shape): {@code park_order} present means the per-park
 * {@code [None,15]->[None,30,5]} all-parks model; otherwise {@code head} ({@code "pre"}|{@code
 * "post"}, written by register_snapshot for the pitch heads AND their LR baseline, rule 9) selects
 * {@code loadPitchPre}/{@code loadPitchPost}; otherwise the single-float batted-ball loader. A
 * successful load + dummy predict exercises the same code path serving uses (the external-data ONNX
 * sidecar, the calibrator file + format, and the feature pipeline + lookups), so a mis-wired
 * snapshot 422s at promote-time instead of 500ing live.
 *
 * <p>Follow-on (noted, not a blocker): promote {@code park_order}-sniffing to a first-class {@code
 * model_kind} registry column set at registration, so loader resolution is explicit; if the
 * controllers adopt the same resolver, the endpoint-hardcoded loader-divergence goes away too.
 */
@Component
public class ModelLoadValidator {

  private static final Logger log = LoggerFactory.getLogger(ModelLoadValidator.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Deterministic, in-distribution dummy input for the all-parks warm-up forward pass. */
  private static final FeaturePipelineBattedBall.Request ALL_PARKS_DUMMY =
      new FeaturePipelineBattedBall.Request(102.0, 27.0, 5.0, 401.0, "R", 0, 1);

  /**
   * Warm-up dummy for the PRE pitch head. Unknown pitcher/batter ids (0L) exercise the same
   * missing-TE fallback live serving uses for a never-seen player; null Tier-3 form values are
   * forwarded as NaN by the pipeline. Park NYY exists in every snapshot's park_id_mapping.json.
   */
  private static final FeaturePipelinePitchPre.Request PITCH_PRE_DUMMY =
      new FeaturePipelinePitchPre.Request(
          1, 1, 1, 5, 0, 0, 3, "R", "R", "NYY", 0L, 0L, null, null, null, null, null, null, null,
          null, null, null, null);

  /** Warm-up dummy for the POST pitch head: PRE fields plus the nullable Tier-4 block. */
  private static final FeaturePipelinePitchPost.Request PITCH_POST_DUMMY =
      new FeaturePipelinePitchPost.Request(
          1, 1, 1, 5, 0, 0, 3, "R", "R", "NYY", 0L, 0L, null, null, null, null, null, null, null,
          null, null, null, null, "FF", null, null, null, null, null, null, null, null, null);

  private final ModelLoader modelLoader;

  public ModelLoadValidator(ModelLoader modelLoader) {
    this.modelLoader = modelLoader;
  }

  /**
   * Load {@code mv} via its serving loader + one forward pass. Throws {@link
   * RegistryException.ModelLoadFailed} on any load/predict failure (missing external-data sidecar,
   * missing or wrong-format calibrator, ONNX wiring error, or unreadable metadata - fail closed,
   * since a model whose metadata can't be read won't serve either).
   */
  public void validate(ModelVersion mv) {
    try {
      JsonNode md = MAPPER.readTree(Files.readAllBytes(Path.of(mv.metadataPath())));
      if (md.has("park_order")) {
        // per-park [None,15]->[None,30,5] all-parks model
        modelLoader.loadAllParks(mv.id()).predict(ALL_PARKS_DUMMY);
      } else if (md.has("head")) {
        // pitch head or its LR baseline: register_snapshot writes head=pre|post (rule 9)
        if ("post".equals(md.path("head").asText())) {
          modelLoader.loadPitchPost(mv.id()).predictPost(PITCH_POST_DUMMY);
        } else {
          modelLoader.loadPitchPre(mv.id()).predictPre(PITCH_PRE_DUMMY);
        }
      } else {
        // single-float batted-ball loader
        modelLoader.loadBattedBall(mv.id()).predict(102.0, 27.0, 92.0, "NYY", "R");
      }
      log.info(
          "load gate OK: {}/{} (id={}) loads + predicts via the serving path",
          mv.modelName(),
          mv.version(),
          mv.id());
    } catch (Exception e) {
      throw new RegistryException.ModelLoadFailed(mv.modelName(), mv.version(), mv.id(), e);
    }
  }
}
