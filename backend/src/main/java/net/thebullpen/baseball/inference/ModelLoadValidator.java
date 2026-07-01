package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
        // per-park [None,15]->[None,30,5] all-parks model. When it serves carry (Phase 4),
        // exercise BOTH outputs and sanity-check the per-park carry head ([166] carry sanity),
        // not just output[0] - a mis-exported / garbage carry head then 422s at promote-time
        // instead of serving absurd feet on /parks.
        LoadedAllParksModel allParks = modelLoader.loadAllParks(mv.id());
        if (allParks.servesCarry()) {
          assertCarrySane(
              allParks.predictWithCarry(ALL_PARKS_DUMMY).carryFtByPark(),
              mv.modelName() + "/" + mv.version());
        } else {
          allParks.predict(ALL_PARKS_DUMMY);
        }
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

  /**
   * Carry-sanity bounds in feet ([166]: every per-park carry finite and in [50, 550] ft).
   *
   * <p>These MUST stay in lock-step with the Python promotion gate's band - {@code
   * training/src/bullpen_training/battedball/mlp/rolling_cv_eval.py} {@code CARRY_MIN_FT} / {@code
   * CARRY_MAX_FT} (the ADR-0012 {@code carry_gate}). The offline gate rejects a challenger whose
   * per-park carry falls outside this range at promote-eval time; this load gate is the
   * serving-side twin that rejects the same at model-load time. If you widen or tighten one, change
   * the other in the same PR (there is no shared source for the constant across the Java/Python
   * boundary - registry-guard Note-1).
   */
  static final double CARRY_MIN_FT = 50.0;

  static final double CARRY_MAX_FT = 550.0;

  /**
   * Carry-head sanity for a carry-serving all-parks model ([166]): the per-park carry map must be
   * present, non-empty, and every value finite and within [{@value #CARRY_MIN_FT}, {@value
   * #CARRY_MAX_FT}] ft. Catches a mis-exported carry head (NaN / zero / absurd feet) at
   * promote-time; complements {@link LoadedAllParksModel#predictWithCarry}, which already rejects a
   * carry-axis length mismatch. Throws {@link IllegalStateException}, which {@link #validate} wraps
   * into {@link RegistryException.ModelLoadFailed}.
   */
  static void assertCarrySane(Map<String, Double> carryFtByPark, String modelLabel) {
    if (carryFtByPark == null || carryFtByPark.isEmpty()) {
      throw new IllegalStateException(
          "carry-serving model " + modelLabel + " produced no carry output");
    }
    for (Map.Entry<String, Double> e : carryFtByPark.entrySet()) {
      Double ft = e.getValue();
      if (ft == null || !Double.isFinite(ft) || ft < CARRY_MIN_FT || ft > CARRY_MAX_FT) {
        throw new IllegalStateException(
            "carry head output out of sane range ["
                + CARRY_MIN_FT
                + ", "
                + CARRY_MAX_FT
                + "] ft: park "
                + e.getKey()
                + " = "
                + ft
                + " for "
                + modelLabel);
      }
    }
  }
}
