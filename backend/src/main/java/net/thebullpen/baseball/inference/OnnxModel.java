package net.thebullpen.baseball.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Thin wrapper around an ONNX Runtime Java session for the toy batted-ball binary classifier (Phase
 * 1.4).
 *
 * <p>onnxmltools' LightGBM converter with {@code zipmap=False} produces an inference graph whose
 * outputs are:
 *
 * <ol>
 *   <li>label tensor (Nx1 int) — argmax class
 *   <li>probability tensor (Nx2 float) — [p(class=0), p(class=1)]
 * </ol>
 *
 * <p>We pick the second output and return p(class=1) for each row.
 */
public final class OnnxModel implements AutoCloseable {

  private static final String INPUT_NAME = "input";

  private final OrtEnvironment env;
  private final OrtSession session;
  private final SessionGuard guard;

  public OnnxModel(Path modelPath) throws OrtException {
    this.env = OrtEnvironment.getEnvironment();
    this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
    this.guard = new SessionGuard("onnx session " + modelPath.getFileName());
  }

  public float predict(float[] features) throws OrtException {
    return predictBatch(new float[][] {features})[0];
  }

  public float[] predictBatch(float[][] features) throws OrtException {
    // Every native touch goes through the guard (task #87 / H2): close waits for this run.
    return guard.withSession(
        () -> {
          try (OnnxTensor tensor = OnnxTensor.createTensor(env, features);
              OrtSession.Result result = session.run(Map.of(INPUT_NAME, tensor))) {
            OnnxValue probability = extractProbabilityTensor(result);
            float[][] probs = (float[][]) probability.getValue();
            float[] out = new float[probs.length];
            for (int i = 0; i < probs.length; i++) {
              out[i] = probs[i][1];
            }
            return out;
          }
        });
  }

  /** True once {@link #close()} has begun: the bundle is stale and a fresh load should serve. */
  public boolean isRetired() {
    return guard.isRetired();
  }

  private static OnnxValue extractProbabilityTensor(OrtSession.Result result) {
    int size = result.size();
    if (size == 0) {
      throw new IllegalStateException("ONNX session returned no outputs");
    }
    // Single-output graphs land here; two-output graphs use index 1 (probabilities).
    return size == 1 ? result.get(0) : result.get(1);
  }

  @Override
  public void close() throws OrtException {
    // Retire-drain-close via the guard: safe against in-flight runs, idempotent across the
    // Caffeine removalListener and the ModelLoader shutdown sweep both reaching this bundle.
    guard.closeWhenIdle(session::close);
    // Do NOT close env — it's a process-wide singleton owned by ORT.
  }

  /** Convenience: ensures the Spring bean lives at the canonical artifact path. */
  static Path defaultModelPath() throws IOException {
    Path repoRoot = Path.of(System.getProperty("user.dir"));
    // Spring boots from /backend; artifacts live at ../training/artifacts/_toy/v0/
    Path candidate = repoRoot.resolve("../training/artifacts/_toy/v0/model.onnx").normalize();
    if (!candidate.toFile().exists()) {
      throw new IOException("toy ONNX model not found at " + candidate);
    }
    return candidate;
  }
}
