package net.thebullpen.baseball.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Production pre-pitch inference bean (Phase 2a.8).
 *
 * <p>Wires the multinomial LightGBM ONNX session, the per-class isotonic calibrator, and the
 * production feature pipeline (Tier 1+2+3). One instance per JVM — the ONNX session is reused
 * across requests. Profile-gated to {@code api} so the worker JVM doesn't pay the model-load cost.
 *
 * <p>Calibration happens in Java post-ONNX (decision [38]) — Python writes the breakpoints to
 * {@code calibrator.json}; Java applies them with double-precision arithmetic so the parity test
 * passes at 1e-6.
 */
@Component
@Profile("api")
@ConditionalOnExpression(
    "T(java.nio.file.Files).exists(T(java.nio.file.Path).of('${bullpen.inference.pitch.artifacts-dir:../training/artifacts/pitch_outcome_pre/v1}').resolve('model.onnx'))")
public class PitchInferenceService {

  private static final Logger log = LoggerFactory.getLogger(PitchInferenceService.class);

  public static final String MODEL_NAME = "pitch_outcome_pre";
  public static final String MODEL_VERSION = "v1";
  private static final String ONNX_INPUT_NAME = "input";
  private static final int WARMUP_ROUNDS = 3;

  private final Path artifactsDir;
  private final Path contractPath;
  private OrtEnvironment env;
  private OrtSession session;
  private FeaturePipelinePitchPre pipeline;
  private IsotonicCalibratorJava calibrator;

  public PitchInferenceService(
      @Value("${bullpen.inference.pitch.artifacts-dir:../training/artifacts/pitch_outcome_pre/v1}")
          String artifactsDir,
      @Value("${bullpen.inference.contract-path:../contracts/feature_pipeline.json}")
          String contractPath) {
    this.artifactsDir = Path.of(artifactsDir).toAbsolutePath().normalize();
    this.contractPath = Path.of(contractPath).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void init() throws IOException, OrtException {
    Path onnxPath = artifactsDir.resolve("model.onnx");
    Path calibratorPath = artifactsDir.resolve("calibrator.json");
    if (!onnxPath.toFile().exists()) {
      throw new IOException(
          "pitch ONNX model not found at "
              + onnxPath
              + " — run `uv run python -m bullpen_training.pitch.production "
              + "--model lightgbm --version v1` then "
              + "`uv run python -m bullpen_training.pitch.export_pre_onnx` first");
    }
    if (!contractPath.toFile().exists()) {
      throw new IOException("feature pipeline contract not found at " + contractPath);
    }

    this.pipeline = FeaturePipelinePitchPre.load(contractPath, artifactsDir);
    this.calibrator = IsotonicCalibratorJava.load(calibratorPath);
    this.env = OrtEnvironment.getEnvironment();
    this.session = env.createSession(onnxPath.toString(), new OrtSession.SessionOptions());

    warmup();
    log.info(
        "PitchInferenceService ready model={} version={} features={} classes={} schema_hash={}",
        MODEL_NAME,
        MODEL_VERSION,
        pipeline.spec().featureOrder().size(),
        pipeline.spec().classLabels(),
        pipeline.spec().schemaHash());
  }

  /**
   * Run a handful of trivial requests through the full pipeline so the JIT, the ONNX session, and
   * the calibrator are hot before the first user request. Documented in Phase 1.5 — paid for once
   * at startup so the 100ms p95 SLO holds from request #1.
   */
  private void warmup() throws OrtException {
    FeaturePipelinePitchPre.Request dummy =
        new FeaturePipelinePitchPre.Request(
            0, 0, 0, 1, 0, 0, 0, "R", "R", "UNK", 0L, 0L, null, null, null, null, null, null, null,
            null, null, null, null);
    for (int i = 0; i < WARMUP_ROUNDS; i++) {
      predict(dummy);
    }
  }

  @PreDestroy
  public void close() throws OrtException {
    if (session != null) {
      session.close();
    }
    // Do NOT close env — it's a process-wide singleton owned by ORT.
  }

  /** Predict calibrated 5-class distribution for one request. Thread-safe. */
  public Map<String, Double> predict(FeaturePipelinePitchPre.Request req) throws OrtException {
    float[] vector = pipeline.transform(req);
    float[] rawProbs = runOnnx(vector);
    double[] asDouble = new double[rawProbs.length];
    for (int i = 0; i < rawProbs.length; i++) asDouble[i] = rawProbs[i];
    double[][] calibrated = calibrator.transform(new double[][] {asDouble});

    Map<String, Double> out = new LinkedHashMap<>();
    java.util.List<String> labels = pipeline.spec().classLabels();
    for (int c = 0; c < labels.size(); c++) {
      out.put(labels.get(c), calibrated[0][c]);
    }
    return out;
  }

  private float[] runOnnx(float[] features) throws OrtException {
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][] {features});
        OrtSession.Result result = session.run(Map.of(ONNX_INPUT_NAME, tensor))) {
      int size = result.size();
      if (size == 0) {
        throw new IllegalStateException("ONNX session returned no outputs");
      }
      // zipmap=False multi-class outputs: [label tensor, probability tensor (N, K)].
      // Single-output graphs (defensive fallback for older converters) use index 0.
      Object probObj = (size == 1 ? result.get(0) : result.get(1)).getValue();
      float[][] probs = (float[][]) probObj;
      return probs[0];
    }
  }

  public FeaturePipelinePitchPre.Spec pipelineSpec() {
    return pipeline.spec();
  }

  public java.util.List<String> classLabels() {
    return pipeline.spec().classLabels();
  }
}
