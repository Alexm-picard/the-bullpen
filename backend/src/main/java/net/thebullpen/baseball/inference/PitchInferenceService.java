package net.thebullpen.baseball.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.config.InferenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production pitch-outcome inference bean (Phase 2a.8 + 2b.3).
 *
 * <p>Wires both heads of the pitch model behind a single Spring component (decision [35]: two
 * registered models, one endpoint that dispatches by {@code ?head=}):
 *
 * <ul>
 *   <li>{@link Head#PRE} — pre-pitch, 31 features (Tier 1+2+3); always loaded.
 *   <li>{@link Head#POST} — post-pitch, 41 features (Tier 1+2+3+4); loaded only if its artifact
 *       directory exists, otherwise {@code head=post} requests get a clear runtime error and
 *       toy-only test runs stay green.
 * </ul>
 *
 * <p>One instance per JVM, ONNX sessions reused across requests. Profile-gated to {@code api} so
 * the worker JVM doesn't pay the model-load cost.
 *
 * <p>Calibration happens in Java post-ONNX (decision [38]) — Python writes the breakpoints to
 * {@code calibrator.json}; Java applies them with double-precision arithmetic so the parity tests
 * pass at 1e-6 against each head's fixture.
 */
@Component
@Profile({"api", "worker"})
// Bean-existence gate: MUST stay a raw property expression (conditions evaluate before any
// @ConfigurationProperties bean exists). Its inline default is intentionally duplicated with
// InferenceProperties.PITCH_ARTIFACTS_DEFAULT; InferencePropertiesTest pins the two equal.
@ConditionalOnExpression(
    "T(java.nio.file.Files).exists(T(java.nio.file.Path).of('${bullpen.inference.pitch.artifacts-dir:../training/artifacts/pitch_outcome_pre/v1}').resolve('model.onnx'))")
public class PitchInferenceService {

  private static final Logger log = LoggerFactory.getLogger(PitchInferenceService.class);

  public static final String MODEL_NAME = "pitch_outcome_pre";
  public static final String MODEL_VERSION = "v1";
  public static final String POST_MODEL_NAME = "pitch_outcome_post";
  public static final String POST_MODEL_VERSION = "v1";

  private static final String ONNX_INPUT_NAME = "input";
  private static final int WARMUP_ROUNDS = 3;

  private final Path preArtifactsDir;
  private final Path prePipelineContractPath;
  private final Path postArtifactsDir;
  private final Path postPipelineContractPath;

  private OrtEnvironment env;
  // Pre head — always loaded (the bean's @ConditionalOnExpression guarantees the artifact exists).
  private OrtSession preSession;
  private FeaturePipelinePitchPre prePipeline;
  private IsotonicCalibratorJava preCalibrator;
  // Post head — loaded iff the artifact dir exists at startup. Null otherwise.
  private OrtSession postSession;
  private FeaturePipelinePitchPost postPipeline;
  private IsotonicCalibratorJava postCalibrator;

  public PitchInferenceService(InferenceProperties props) {
    // pitchContractPath() resolves the legacy shared bullpen.inference.contract-path key with the
    // pre head's historical default (see InferenceProperties' javadoc on the shared-key wart).
    this.preArtifactsDir = Path.of(props.pitch().artifactsDir()).toAbsolutePath().normalize();
    this.prePipelineContractPath = Path.of(props.pitchContractPath()).toAbsolutePath().normalize();
    this.postArtifactsDir = Path.of(props.pitchPost().artifactsDir()).toAbsolutePath().normalize();
    this.postPipelineContractPath =
        Path.of(props.pitchPost().contractPath()).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void init() throws IOException, OrtException {
    this.env = OrtEnvironment.getEnvironment();

    // Pre head — required (bean condition already proved model.onnx exists).
    Path preOnnxPath = preArtifactsDir.resolve("model.onnx");
    Path preCalibratorPath = preArtifactsDir.resolve("calibrator.json");
    if (!prePipelineContractPath.toFile().exists()) {
      throw new IOException("pre pipeline contract not found at " + prePipelineContractPath);
    }
    this.prePipeline = FeaturePipelinePitchPre.load(prePipelineContractPath, preArtifactsDir);
    this.preCalibrator = IsotonicCalibratorJava.load(preCalibratorPath);
    this.preSession = env.createSession(preOnnxPath.toString(), new OrtSession.SessionOptions());
    warmupPre();
    log.info(
        "PitchInferenceService pre head ready model={} version={} features={} classes={} schema_hash={}",
        MODEL_NAME,
        MODEL_VERSION,
        prePipeline.spec().featureOrder().size(),
        prePipeline.spec().classLabels(),
        prePipeline.spec().schemaHash());

    // Post head — optional. Load iff the artifact + contract both exist. Don't fail startup if
    // the post bundle hasn't been trained yet — that's a Phase-2b.2 deliverable that can lag the
    // Spring rollout, and toy + pre-only tests still need to boot.
    Path postOnnxPath = postArtifactsDir.resolve("model.onnx");
    Path postCalibratorPath = postArtifactsDir.resolve("calibrator.json");
    if (Files.exists(postOnnxPath) && Files.exists(postPipelineContractPath)) {
      this.postPipeline = FeaturePipelinePitchPost.load(postPipelineContractPath, postArtifactsDir);
      this.postCalibrator = IsotonicCalibratorJava.load(postCalibratorPath);
      this.postSession =
          env.createSession(postOnnxPath.toString(), new OrtSession.SessionOptions());
      warmupPost();
      log.info(
          "PitchInferenceService post head ready model={} version={} features={} classes={} schema_hash={}",
          POST_MODEL_NAME,
          POST_MODEL_VERSION,
          postPipeline.spec().featureOrder().size(),
          postPipeline.spec().classLabels(),
          postPipeline.spec().schemaHash());
    } else {
      log.info(
          "PitchInferenceService post head not loaded (model.onnx or contract missing) — "
              + "post requests will fail until {} is populated",
          postArtifactsDir);
    }
  }

  private void warmupPre() throws OrtException {
    FeaturePipelinePitchPre.Request dummy =
        new FeaturePipelinePitchPre.Request(
            0, 0, 0, 1, 0, 0, 0, "R", "R", "UNK", 0L, 0L, null, null, null, null, null, null, null,
            null, null, null, null);
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      predictPre(dummy);
    }
  }

  private void warmupPost() throws OrtException {
    FeaturePipelinePitchPost.Request dummy =
        new FeaturePipelinePitchPost.Request(
            0, 0, 0, 1, 0, 0, 0, "R", "R", "UNK", 0L, 0L, null, null, null, null, null, null, null,
            null, null, null, null, "FF", 92.0, 0.0, 2.5, 0.0, 0.0, 2200.0, 180.0, -1.5, 5.8);
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      predictPost(dummy);
    }
  }

  @PreDestroy
  public void close() throws OrtException {
    if (preSession != null) preSession.close();
    if (postSession != null) postSession.close();
    // Do NOT close env — it's a process-wide singleton owned by ORT.
  }

  /** True iff the post head was loaded at startup. Lets the controller short-circuit cleanly. */
  public boolean isPostHeadAvailable() {
    return postSession != null;
  }

  /** Predict calibrated 5-class distribution for the pre head. Thread-safe. */
  public Map<String, Double> predictPre(FeaturePipelinePitchPre.Request req) throws OrtException {
    float[] vector = prePipeline.transform(req);
    float[] rawProbs = runOnnx(preSession, vector);
    return calibrateAndPack(rawProbs, preCalibrator, prePipeline.spec().classLabels());
  }

  /** Predict calibrated 5-class distribution for the post head. Thread-safe. */
  public Map<String, Double> predictPost(FeaturePipelinePitchPost.Request req) throws OrtException {
    if (postSession == null) {
      throw new IllegalStateException(
          "post head not loaded — train + persist pitch_outcome_post/v1 first "
              + "(`uv run python -m bullpen_training.pitch.production --model post --version v1`)");
    }
    float[] vector = postPipeline.transform(req);
    float[] rawProbs = runOnnx(postSession, vector);
    return calibrateAndPack(rawProbs, postCalibrator, postPipeline.spec().classLabels());
  }

  /**
   * Back-compat shim for the 2a.8-era call signature: defaults to the pre head. New code should
   * call {@link #predictPre(FeaturePipelinePitchPre.Request)} explicitly.
   */
  public Map<String, Double> predict(FeaturePipelinePitchPre.Request req) throws OrtException {
    return predictPre(req);
  }

  private Map<String, Double> calibrateAndPack(
      float[] rawProbs, IsotonicCalibratorJava calibrator, List<String> labels) {
    double[] asDouble = new double[rawProbs.length];
    for (int i = 0; i < rawProbs.length; i++) asDouble[i] = rawProbs[i];
    double[][] calibrated = calibrator.transform(new double[][] {asDouble});
    Map<String, Double> out = new LinkedHashMap<>();
    for (int c = 0; c < labels.size(); c++) {
      out.put(labels.get(c), calibrated[0][c]);
    }
    return out;
  }

  private float[] runOnnx(OrtSession session, float[] features) throws OrtException {
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][] {features});
        OrtSession.Result result = session.run(Map.of(ONNX_INPUT_NAME, tensor))) {
      int size = result.size();
      if (size == 0) {
        throw new IllegalStateException("ONNX session returned no outputs");
      }
      // zipmap=False multi-class outputs: [label tensor, probability tensor (N, K)].
      Object probObj = (size == 1 ? result.get(0) : result.get(1)).getValue();
      float[][] probs = (float[][]) probObj;
      return probs[0];
    }
  }

  public FeaturePipelinePitchPre.Spec pipelineSpec() {
    return prePipeline.spec();
  }

  public FeaturePipelinePitchPost.Spec postPipelineSpec() {
    if (postPipeline == null) {
      throw new IllegalStateException("post head not loaded");
    }
    return postPipeline.spec();
  }

  public List<String> classLabels() {
    return prePipeline.spec().classLabels();
  }
}
