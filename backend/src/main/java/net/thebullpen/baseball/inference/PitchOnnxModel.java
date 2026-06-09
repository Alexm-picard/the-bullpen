package net.thebullpen.baseball.inference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.Map;

/**
 * ONNX Runtime Java reader for a single pitch-outcome head (pre or post). Input is {@code [N,
 * nFeatures]} - the scaled feature vector {@link FeaturePipelinePitchPre} / {@link
 * FeaturePipelinePitchPost} produces. Output is the raw 5-class probability tensor; calibration is
 * applied downstream in {@link LoadedPitchModel} in double precision (decision [38]).
 *
 * <p>Per-version sibling of the process-singleton reader inside {@link PitchInferenceService}: this
 * one is owned by a registry-loaded {@link LoadedPitchModel}, so the {@link ModelLoader} cache
 * closes it on eviction. The {@link OrtEnvironment} is the process-wide ORT singleton and is never
 * closed here.
 *
 * <p>CRITICAL (2026-06-07 first-champion incident class, decision [152]): the input tensor name is
 * resolved from {@link OrtSession#getInputNames()} - NOT hardcoded to {@code "input"} the way the
 * legacy {@link PitchInferenceService} reader does at its {@code ONNX_INPUT_NAME} constant.
 * Different exporters (LightGBM via onnxmltools, the LR baseline, a future MLP) can disagree on the
 * declared input name; a hardcoded constant feeds at most one family, so the reader feeds whatever
 * the loaded model actually declares. The {@code size != 1} guard fails loud at load time, not at
 * the first request. The ONNX I/O-name surface is intentionally NOT part of the rule-7
 * feature-schema hash (that hash covers the feature-pipeline contract, not the graph's tensor
 * names); this session-resolved read is the discipline that keeps the un-hashed name from silently
 * mismatching.
 */
public final class PitchOnnxModel implements AutoCloseable {

  private final OrtEnvironment env;
  private final OrtSession session;
  private final String inputName;

  public PitchOnnxModel(Path modelPath) throws OrtException {
    this.env = OrtEnvironment.getEnvironment();
    this.session = env.createSession(modelPath.toString(), new OrtSession.SessionOptions());
    var inputNames = session.getInputNames();
    if (inputNames.size() != 1) {
      throw new IllegalStateException(
          "pitch ONNX must declare exactly one input tensor, got " + inputNames);
    }
    this.inputName = inputNames.iterator().next();
  }

  /**
   * Score one pitch: {@code nFeatures}-long scaled vector in, raw {@code nClasses}-long probability
   * vector out. A zipmap=False multi-class export emits {@code [label tensor, probability tensor
   * (N, K)]}; a single-output export emits just the probability tensor. The probability tensor is
   * the last output either way - read it positionally so the read is name-agnostic on the output
   * side too. Mirrors the legacy reader's {@code runOnnx} so parity holds against the same graph.
   */
  public float[] predict(float[] features) throws OrtException {
    try (OnnxTensor tensor = OnnxTensor.createTensor(env, new float[][] {features});
        OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
      int size = result.size();
      if (size == 0) {
        throw new IllegalStateException("pitch ONNX session returned no outputs");
      }
      Object probObj = (size == 1 ? result.get(0) : result.get(1)).getValue();
      if (!(probObj instanceof float[][] probs)) {
        throw new IllegalStateException(
            "pitch ONNX probability output must be a float[N][K] tensor, got "
                + (probObj == null ? "null" : probObj.getClass().getSimpleName()));
      }
      return probs[0];
    }
  }

  /** The resolved input tensor name (visible for tests / diagnostics). */
  public String inputName() {
    return inputName;
  }

  @Override
  public void close() throws OrtException {
    session.close();
    // env is the process-wide ORT singleton owned by ORT; do not close it.
  }
}
