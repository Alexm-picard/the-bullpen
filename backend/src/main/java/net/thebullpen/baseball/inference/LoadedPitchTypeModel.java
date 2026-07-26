package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.registry.SnapshotStorage;

/**
 * A loaded pitch-TYPE model: ONNX session + the encode-only feature pipeline + the temperature
 * calibrator (decision [183]).
 *
 * <p>One class serves BOTH pitch-type registry rows - {@code pitch_type_pre} (LightGBM) and the
 * rule-9 {@code pitch_type_lr_baseline} (sklearn, with Imputer and Scaler inside its graph). They
 * are two rows sharing one contract, and the [183] guardrail compares their log-loss, so serving
 * them through one code path is what keeps that comparison honest. Rule 9 is about REGISTRY rows,
 * not about duplicating a loader.
 *
 * <p>{@link #predict} returns a label-keyed map in the contract's label order, matching {@link
 * LoadedPitchModel}'s shape. What it returns is a CALIBRATED PITCH-TYPE PRIOR - a full distribution
 * over the y7 taxonomy - not a next-pitch call. Any caller that reduces it to its argmax is
 * discarding the thing decision [183] says is valuable, and any UI built on it must say so.
 */
public final class LoadedPitchTypeModel implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final long versionId;
  private final String modelName;
  private final String version;
  private final String schemaHash;
  private final FeaturePipelinePitchType pipeline;
  private final PitchOnnxModel onnx;
  private final TemperatureCalibratorJava calibrator;
  private final List<String> classLabels;

  private LoadedPitchTypeModel(
      long versionId,
      String modelName,
      String version,
      String schemaHash,
      FeaturePipelinePitchType pipeline,
      PitchOnnxModel onnx,
      TemperatureCalibratorJava calibrator,
      List<String> classLabels) {
    this.versionId = versionId;
    this.modelName = modelName;
    this.version = version;
    this.schemaHash = schemaHash;
    this.pipeline = pipeline;
    this.onnx = onnx;
    this.calibrator = calibrator;
    this.classLabels = List.copyOf(classLabels);
  }

  /** Load every artifact a pitch-type snapshot must carry, from {@code snapshotDir}. */
  public static LoadedPitchTypeModel load(
      long versionId, String modelName, String version, String schemaHash, Path snapshotDir)
      throws IOException, OrtException {
    FeaturePipelinePitchType pipeline =
        FeaturePipelinePitchType.load(
            snapshotDir.resolve(SnapshotStorage.FEATURE_PIPELINE_FILE), snapshotDir);
    TemperatureCalibratorJava calibrator = loadCalibrator(snapshotDir);

    // The contract's labels are the ONNX column order; the calibrator carries its own copy. If
    // they ever disagree, the calibrated probabilities would be packed under the wrong labels -
    // a silently wrong distribution rather than a failure. Refuse to load instead.
    if (!pipeline.spec().classLabels().equals(calibrator.classLabels())) {
      throw new IllegalStateException(
          "class-label mismatch in "
              + snapshotDir
              + ": contract has "
              + pipeline.spec().classLabels()
              + " but calibrator.json has "
              + calibrator.classLabels());
    }

    PitchOnnxModel onnx = new PitchOnnxModel(snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE));
    return new LoadedPitchTypeModel(
        versionId,
        modelName,
        version,
        schemaHash,
        pipeline,
        onnx,
        calibrator,
        pipeline.spec().classLabels());
  }

  /**
   * Resolve the calibrator by the metadata pointer when present, else the canonical {@code
   * calibrator.json}. Mirrors {@link LoadedPitchModel}'s resolution so a snapshot that names its
   * calibrator differently still loads.
   */
  private static TemperatureCalibratorJava loadCalibrator(Path snapshotDir) throws IOException {
    Path metadataPath = snapshotDir.resolve(SnapshotStorage.METADATA_FILE);
    Path calibratorPath = snapshotDir.resolve(SnapshotStorage.CALIBRATOR_FILE);
    if (Files.isRegularFile(metadataPath)) {
      JsonNode cal =
          MAPPER.readTree(Files.readAllBytes(metadataPath)).path("calibrator").path("path");
      if (cal.isTextual() && !cal.asText().isBlank()) {
        Path fromMeta = snapshotDir.resolve(cal.asText()).normalize();
        if (Files.isRegularFile(fromMeta)) {
          calibratorPath = fromMeta;
        }
      }
    }
    if (!Files.isRegularFile(calibratorPath)) {
      throw new IOException(
          "pitch-type snapshot at "
              + snapshotDir
              + " has no calibrator (expected "
              + calibratorPath
              + "); registration must place calibrator.json beside model.onnx");
    }
    return TemperatureCalibratorJava.load(calibratorPath);
  }

  /**
   * The model's raw, PRE-calibration probabilities for {@code req}.
   *
   * <p>Exposed for the promotion load gate. Asserting on the calibrated row would be nearly
   * vacuous: {@link TemperatureCalibratorJava#transform} renormalises, so it turns any finite input
   * - logits, raw scores, unnormalised junk - into a plausible-looking distribution that sums to 1.
   * The only place a mis-exported graph is still visible is here, before calibration.
   */
  public float[] rawProbabilities(FeaturePipelinePitchType.Request req) throws OrtException {
    return onnx.predict(pipeline.transform(req));
  }

  /** Calibrated y7 distribution: a pitch-type PRIOR, not a next-pitch prediction. */
  public Map<String, Double> predict(FeaturePipelinePitchType.Request req) throws OrtException {
    float[] vector = pipeline.transform(req);
    float[] raw = onnx.predict(vector);
    if (raw.length != classLabels.size()) {
      throw new IllegalStateException(
          "ONNX returned "
              + raw.length
              + " probabilities but the contract declares "
              + classLabels.size()
              + " classes for "
              + modelName
              + "/"
              + version);
    }
    double[] asDouble = new double[raw.length];
    for (int i = 0; i < raw.length; i++) {
      asDouble[i] = raw[i];
    }
    double[] calibrated = calibrator.transform(asDouble);
    Map<String, Double> out = new LinkedHashMap<>();
    for (int c = 0; c < classLabels.size(); c++) {
      out.put(classLabels.get(c), calibrated[c]);
    }
    return out;
  }

  public long versionId() {
    return versionId;
  }

  public String modelName() {
    return modelName;
  }

  public String version() {
    return version;
  }

  public String schemaHash() {
    return schemaHash;
  }

  public List<String> classLabels() {
    return classLabels;
  }

  public FeaturePipelinePitchType pipeline() {
    return pipeline;
  }

  @Override
  public void close() throws OrtException {
    onnx.close();
  }
}
