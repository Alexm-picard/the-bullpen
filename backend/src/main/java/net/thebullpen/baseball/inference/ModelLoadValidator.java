package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * (mirrors how serving resolves the loader by shape), in this order: an explicit {@code model_kind}
 * of {@value #PITCH_TYPE_KIND} selects {@code loadPitchType} (decision [183]); otherwise {@code
 * park_order} present means the per-park {@code [None,15]->[None,30,5]} all-parks model; otherwise
 * {@code head} ({@code "pre"}|{@code "post"}, written by register_snapshot for the pitch heads AND
 * their LR baseline, rule 9) selects {@code loadPitchPre}/{@code loadPitchPost}; otherwise the
 * single-float batted-ball loader. A successful load + dummy predict exercises the same code path
 * serving uses (the external-data ONNX sidecar, the calibrator file + format, and the feature
 * pipeline + lookups), so a mis-wired snapshot 422s at promote-time instead of 500ing live.
 *
 * <p>{@code model_kind} is checked FIRST so an explicit declaration always beats field-sniffing. It
 * partially discharges the follow-on below: pitch-type carries neither {@code park_order} nor
 * {@code head}, so without it those models fell through to the batted-ball loader and 422'd every
 * promotion.
 *
 * <p>Follow-on (noted, not a blocker): {@code model_kind} is still a METADATA field, not yet the
 * first-class registry COLUMN set at registration that would make loader resolution explicit for
 * every family and retire the {@code park_order}/{@code head} sniffing entirely; if the controllers
 * adopt the same resolver, the endpoint-hardcoded loader-divergence goes away too.
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

  /**
   * Value of {@code metadata.json}'s {@code model_kind} that selects the pitch-TYPE loader.
   *
   * <p>MUST stay in lock-step with {@code bullpen_training.pitch_type.persist}, which is the only
   * writer of this field. There is no shared source for the constant across the Java/Python
   * boundary (same situation as {@link #CARRY_MIN_FT}), so a rename on one side silently sends
   * every pitch-type model back into the batted-ball branch and 422s every promotion. {@code
   * ModelLoadValidatorPitchTypeTest} references this constant rather than re-spelling it, so a
   * rename breaks a test instead of a box run.
   */
  static final String PITCH_TYPE_KIND = "pitch_type";

  /**
   * Warm-up dummy for a pitch-TYPE model. Nulls in the ARS and V013 blocks are deliberate: they are
   * the cold-start shape (a pitcher's first career pitch) and the pipeline forwards them as NaN,
   * which against a REAL export is the path the LR baseline's in-graph Imputer absorbs. Note the
   * committed test fixture gathers only the dense leading columns, so the fixture-backed test does
   * not itself prove the NaN path - it proves the encode/route/calibrate wiring. Park NYY exists in
   * every snapshot's park_id_mapping.json; prev1_pt_i / prev2_pt_i use the -1 outing-start
   * sentinel.
   */
  private static final FeaturePipelinePitchType.Request PITCH_TYPE_DUMMY =
      new FeaturePipelinePitchType.Request(
          1, 1, 1, 5, 0, "R", "R", "NYY", null, null, null, null, null, null, null, null, null,
          null, null, 0, -1, -1, 1, 0);

  /**
   * Dense warm-up dummy: every nullable feature populated with an in-distribution value. The
   * cold-start dummy above proves the NaN/Imputer path; this proves the path that actually serves
   * production traffic, and is where a mis-wired in-graph Scaler in the LR baseline would show.
   */
  private static final FeaturePipelinePitchType.Request PITCH_TYPE_DENSE_DUMMY =
      new FeaturePipelinePitchType.Request(
          2, 1, 1, 5, 0, "R", "R", "NYY", 2.0, 12.0, 1.0, 0.42, 0.18, 0.08, 0.15, 0.09, 0.06, 0.02,
          0.45, 850, 0, 3, 0, 14);

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
      if (PITCH_TYPE_KIND.equals(md.path("model_kind").asText(""))) {
        // pitch-TYPE family (decision [183]): pitch_type_pre and its rule-9 LR baseline, both
        // written with model_kind by pitch_type.persist. Checked FIRST so an explicit kind always
        // wins over field-sniffing.
        LoadedPitchTypeModel pitchType = modelLoader.loadPitchType(mv.id());
        String label = mv.modelName() + "/" + mv.version();
        // Assert on the RAW probabilities, not the calibrated ones. Temperature calibration
        // renormalises, so it turns any finite output - logits, raw scores, unnormalised junk -
        // into a plausible sums-to-1 distribution; asserting post-calibration would pass a
        // model mis-exported without its final softmax and only surface as a wrong prior in
        // production. This is the pitch-type twin of assertCarrySane.
        assertRawProbabilitiesSane(pitchType.rawProbabilities(PITCH_TYPE_DUMMY), label);
        // Both paths, because they fail differently: the cold-start row is the only input that
        // exercises the LR baseline's in-graph Imputer, while the dense row is what serves
        // essentially all real traffic and is where a mis-wired in-graph Scaler would show.
        assertRawProbabilitiesSane(pitchType.rawProbabilities(PITCH_TYPE_DENSE_DUMMY), label);
        pitchType.predict(PITCH_TYPE_DUMMY);
      } else if (md.has("park_order")) {
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
    } catch (IOException | OrtException | RuntimeException e) {
      // Explicit rather than `catch (Exception)`: these are exactly what the block can raise -
      // unreadable metadata (IOException), an ONNX/session failure (OrtException), and the
      // fail-closed RuntimeExceptions the loaders and the sanity assertions throw. Naming them
      // keeps Error (OOM, StackOverflow) from being mislabelled as a model-load failure, and
      // makes a newly-introduced checked exception a compile error instead of a silent catch.
      throw new RegistryException.ModelLoadFailed(mv.modelName(), mv.version(), mv.id(), e);
    }
  }

  /**
   * A pitch-type model's RAW output must already be a probability distribution: right width, every
   * value finite and in [0, 1], and summing to 1 within float32 tolerance.
   *
   * <p>Deliberately checked BEFORE calibration. {@link TemperatureCalibratorJava} renormalises, so
   * post-calibration every finite vector looks like a valid distribution - a graph exported without
   * its final softmax (raw scores), or one ignoring its input, would sail through and then serve a
   * materially wrong prior. Same role as {@link #assertCarrySane} for the carry head: turn a
   * mis-exported graph into a promote-time 422 instead of a quietly wrong number.
   */
  static void assertRawProbabilitiesSane(float[] raw, String modelLabel) {
    if (raw == null || raw.length == 0) {
      throw new IllegalStateException("pitch-type model " + modelLabel + " produced no output");
    }
    double sum = 0.0;
    for (int i = 0; i < raw.length; i++) {
      float p = raw[i];
      if (!Float.isFinite(p) || p < 0.0f || p > 1.0f) {
        throw new IllegalStateException(
            "pitch-type raw output is not a probability at index "
                + i
                + ": "
                + p
                + " for "
                + modelLabel
                + " (a graph exported without its final softmax would look like this)");
      }
      sum += p;
    }
    if (Math.abs(sum - 1.0) > RAW_PROB_SUM_TOLERANCE) {
      throw new IllegalStateException(
          "pitch-type raw output does not sum to 1 (got "
              + sum
              + ") for "
              + modelLabel
              + "; the ONNX graph is not emitting a calibrated-ready distribution");
    }
  }

  /** float32 accumulation slack for a 7-way softmax; a real defect misses by far more than this. */
  static final double RAW_PROB_SUM_TOLERANCE = 1e-4;

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
