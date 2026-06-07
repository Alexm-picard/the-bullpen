package net.thebullpen.baseball.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * <p>The loader is resolved from the model's OWN metadata - {@code park_order} present means the
 * per park {@code [None,15]->[None,30,5]} all-parks model ({@link #loadAllParks}); otherwise the
 * single-float batted-ball loader - NOT hardcoded by model name. That mirrors how serving itself
 * resolves the loader (by shape) and avoids the BUG-1a name-coupling smell. A successful {@code
 * loadAllParks} + dummy predict exercises all three incident layers (the external-data ONNX
 * sidecar, the calibrator file, and the calibrator format inside {@code
 * BattedBallCalibrators.load}).
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
      if (isAllParks(mv)) {
        modelLoader.loadAllParks(mv.id()).predict(ALL_PARKS_DUMMY);
      } else {
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

  /** {@code park_order} in the snapshot metadata == the per-park [30,5] all-parks model. */
  private boolean isAllParks(ModelVersion mv) throws IOException {
    JsonNode md = MAPPER.readTree(Files.readAllBytes(Path.of(mv.metadataPath())));
    return md.has("park_order");
  }
}
