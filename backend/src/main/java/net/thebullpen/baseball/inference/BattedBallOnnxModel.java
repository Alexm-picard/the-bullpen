package net.thebullpen.baseball.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.Map;

/**
 * ONNX Runtime Java reader for the real batted-ball outcome model (B-workstream B1).
 *
 * <p>Input is {@code [N, nFeatures]} - the 15 scaled features {@link FeaturePipelineBattedBall}
 * produces. Output is {@code [N, nParks, nOutcomes]}: every park's raw-softmax outcome distribution
 * for each batted ball (decisions [141]/[142]; the MLP, the per-park LGBM, and the LR baseline all
 * share this {@code [30, 5]} shape, with the distribution pinned at the contract's {@code
 * onnx_output_index: 0}). Park selection and the per-park isotonic calibration are applied
 * downstream (B3); this reader is just the session + the 3D-tensor read.
 *
 * <p>Replaces the toy single-float {@link OnnxModel} on the batted-ball serving path. The {@link
 * OrtEnvironment} is the process-wide ORT singleton and is never closed here; the session is closed
 * when {@link ModelLoader} evicts the owning model.
 */
public final class BattedBallOnnxModel implements AutoCloseable {

  private final OrtEnvironment env;
  private final OrtSession session;

  /**
   * The model's declared input tensor name, resolved from the loaded session rather than hardcoded
   * (decision [152]). The all-parks exporters this one reader serves (see the class javadoc)
   * DISAGREE on the input name: the MLP / per-park MLP export {@code "features"}; the per-park LGBM
   * and the LR baseline export {@code "input"}. A hardcoded constant could feed at most one family,
   * so it is resolved from {@link OrtSession#getInputNames()} - the reader feeds whatever the
   * loaded model actually declares. This is the latent input-name half of the Python<->Java
   * batted-ball contract, surfaced by the 2026-06-07 first-champion promotion: the MLP (named
   * {@code "features"}) was the first real all-parks model ever served through this reader - the
   * toy always went through {@link OnnxModel} (named {@code "input"}), so the mismatch never fired
   * until now.
   */
  private final String inputName;

  /**
   * The exporter's name for the per-park carry output (Phase 4): {@code _ProbaExport}'s output 1.
   */
  private static final String CARRY_OUTPUT_NAME = "carry";

  /**
   * Whether the loaded graph exposes the per-park carry output (Phase 4 PR-4). Resolved by NAME
   * from the session, never by output count/position: a {@code carry}-named second output means a
   * carry-capable champion; its absence (an older probabilities-only champion) serves carry-free.
   * Resolving by name - not {@code outputs.size() >= 2} + a positional {@code get(1)} - mirrors how
   * {@link #inputName} is resolved (decision [152]) so a future graph that grows a different second
   * head can't be silently mis-read as carry. Carry is an additive model output, NOT part of the
   * hashed feature contract (the input pipeline + the primary distribution output are unchanged),
   * so the same reader serves both kinds.
   */
  private final boolean hasCarry;

  public BattedBallOnnxModel(Path modelPath) throws OrtException {
    this.env = OrtEnvironment.getEnvironment();
    this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
    var inputNames = session.getInputNames();
    if (inputNames.size() != 1) {
      throw new IllegalStateException(
          "batted-ball ONNX must declare exactly one input tensor, got " + inputNames);
    }
    this.inputName = inputNames.iterator().next();
    this.hasCarry = session.getOutputNames().contains(CARRY_OUTPUT_NAME);
  }

  /** True when the graph exposes the second (carry) output (Phase 4). */
  public boolean hasCarry() {
    return hasCarry;
  }

  /**
   * One batted ball's per-park raw softmax distribution plus, when the graph exposes it, the
   * per-park STANDARDISED carry (output index 1). {@code carry} is {@code null} for a
   * probabilities-only model; otherwise it is a {@code nParks}-long vector the caller
   * un-standardises to feet.
   */
  // Transient inference carrier - never compared/hashed/serialized, so the array-component
  // equals/hashCode caveat ArrayRecordComponent warns about does not apply here.
  @SuppressWarnings("ArrayRecordComponent")
  public record Prediction(float[][] distribution, float[] carry) {}

  /**
   * Score one batted ball.
   *
   * @param features the {@code nFeatures}-long scaled feature vector from {@link
   *     FeaturePipelineBattedBall#transform}
   * @return {@code [nParks][nOutcomes]} raw softmax, park axis in the contract's {@code park_order}
   */
  public float[][] predict(float[] features) throws OrtException {
    return predictBatch(new float[][] {features})[0];
  }

  /** Batched variant: {@code [N][nFeatures]} in, {@code [N][nParks][nOutcomes]} out. */
  public float[][][] predictBatch(float[][] features) throws OrtException {
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, features);
        OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
      return readDistribution(result);
    }
  }

  /**
   * Score one batted ball, returning the per-park distribution AND - when {@link #hasCarry()} - the
   * per-park STANDARDISED carry, in ONE inference. {@code carry} is {@code null} for a
   * probabilities-only model. The distribution is read at output index 0 (contract-pinned); carry
   * is read by NAME ({@value #CARRY_OUTPUT_NAME}), not by position. The export squeezes carry's
   * trailing dim, so the carry tensor is {@code [N][nParks]}; we read row 0.
   */
  public Prediction predictWithCarry(float[] features) throws OrtException {
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][] {features});
        OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
      float[][] dist = readDistribution(result)[0];
      if (!hasCarry) {
        return new Prediction(dist, null);
      }
      Object carryValue =
          result
              .get(CARRY_OUTPUT_NAME)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "hasCarry set but session has no '" + CARRY_OUTPUT_NAME + "' output"))
              .getValue();
      if (!(carryValue instanceof float[][] carry)) {
        throw new IllegalStateException(
            "batted-ball ONNX '"
                + CARRY_OUTPUT_NAME
                + "' output must be a float[N][nParks] tensor, got "
                + (carryValue == null ? "null" : carryValue.getClass().getSimpleName()));
      }
      return new Prediction(dist, carry[0]);
    }
  }

  /** Read + type-check output 0 - the per-park distribution the contract pins at index 0. */
  private static float[][][] readDistribution(OrtSession.Result result) throws OrtException {
    Object value = result.get(0).getValue();
    if (!(value instanceof float[][][] dist)) {
      throw new IllegalStateException(
          "batted-ball ONNX output[0] must be a float[N][nParks][nOutcomes] tensor, got "
              + (value == null ? "null" : value.getClass().getSimpleName()));
    }
    return dist;
  }

  @Override
  public void close() throws OrtException {
    session.close();
    // env is the process-wide ORT singleton owned by ORT; do not close it.
  }
}
