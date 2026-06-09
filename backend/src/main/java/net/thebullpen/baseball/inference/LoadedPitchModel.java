package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.registry.SnapshotStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One registered pitch-outcome model version wired for serving (W1). Mirrors {@link
 * LoadedAllParksModel}: a feature pipeline + an ONNX session + an {@link IsotonicCalibratorJava},
 * ALL loaded from THIS model's own snapshot directory (the parent of the registry row's {@code
 * artifact_path}) - never a process-wide default and never a hardcoded path.
 *
 * <p>Rule 9 (two heads = two registered models): a {@code LoadedPitchModel} carries exactly ONE
 * head - {@code pitch_outcome_pre} loads a {@link FeaturePipelinePitchPre} (31 features), {@code
 * pitch_outcome_post} loads a {@link FeaturePipelinePitchPost} (41 features). The two are loaded as
 * two separate registry versions via {@link ModelLoader#loadPitchPre} / {@link
 * ModelLoader#loadPitchPost}, never one masked model. The non-active pipeline reference stays null.
 *
 * <p>CRITICAL (2026-06-07 first-champion incident class, decision [152]): the ONNX input tensor
 * name is resolved from the loaded session inside {@link PitchOnnxModel}, and the calibrator file
 * is resolved from the snapshot's {@code metadata.json} ({@code calibrator.path}) when present,
 * falling back to the canonical {@code calibrator.json}. Neither is hardcoded the way the legacy
 * {@link PitchInferenceService} does. The feature-pipeline contract + its Tier-2 lookups ({@code
 * park_id_mapping.json}, {@code pitcher_te.json}, {@code batter_te.json}, and for post {@code
 * pitch_type_mapping.json}) are read from the snapshot dir, so a registration that fails to place
 * those files fails loud here at load time rather than serving skewed features.
 */
public final class LoadedPitchModel implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LoadedPitchModel.class);

  private final long versionId;
  private final String modelName;
  private final String version;
  private final String schemaHash;
  private final Head head;
  // Exactly one of these is non-null, selected by head (rule 9).
  private final FeaturePipelinePitchPre prePipeline;
  private final FeaturePipelinePitchPost postPipeline;
  private final PitchOnnxModel onnx;
  private final IsotonicCalibratorJava calibrator;
  private final List<String> classLabels;

  private LoadedPitchModel(
      long versionId,
      String modelName,
      String version,
      String schemaHash,
      Head head,
      FeaturePipelinePitchPre prePipeline,
      FeaturePipelinePitchPost postPipeline,
      PitchOnnxModel onnx,
      IsotonicCalibratorJava calibrator,
      List<String> classLabels) {
    this.versionId = versionId;
    this.modelName = modelName;
    this.version = version;
    this.schemaHash = schemaHash;
    this.head = head;
    this.prePipeline = prePipeline;
    this.postPipeline = postPipeline;
    this.onnx = onnx;
    this.calibrator = calibrator;
    this.classLabels = classLabels;
  }

  /**
   * Load the PRE head ({@code pitch_outcome_pre}) for a registered version from its snapshot
   * directory: {@code model.onnx}, {@code feature_pipeline.json}, {@code metadata.json}, {@code
   * calibrator.json}, plus the Tier-2 lookups ({@code park_id_mapping.json}, {@code
   * pitcher_te.json}, {@code batter_te.json}) the pre pipeline resolves from that same dir.
   */
  public static LoadedPitchModel loadPre(
      long versionId, String modelName, String version, String schemaHash, Path snapshotDir)
      throws IOException, OrtException {
    FeaturePipelinePitchPre pipeline =
        FeaturePipelinePitchPre.load(
            snapshotDir.resolve(SnapshotStorage.FEATURE_PIPELINE_FILE), snapshotDir);
    PitchOnnxModel onnx = new PitchOnnxModel(snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE));
    IsotonicCalibratorJava calibrator = loadCalibrator(snapshotDir);
    log.info(
        "loaded pitch PRE model {}/{} (id={}) from {} (onnx input={})",
        modelName,
        version,
        versionId,
        snapshotDir,
        onnx.inputName());
    return new LoadedPitchModel(
        versionId,
        modelName,
        version,
        schemaHash,
        Head.PRE,
        pipeline,
        null,
        onnx,
        calibrator,
        pipeline.spec().classLabels());
  }

  /**
   * Load the POST head ({@code pitch_outcome_post}) for a registered version from its snapshot
   * directory. Same resolution as {@link #loadPre} plus the post pipeline's {@code
   * pitch_type_mapping.json} lookup.
   */
  public static LoadedPitchModel loadPost(
      long versionId, String modelName, String version, String schemaHash, Path snapshotDir)
      throws IOException, OrtException {
    FeaturePipelinePitchPost pipeline =
        FeaturePipelinePitchPost.load(
            snapshotDir.resolve(SnapshotStorage.FEATURE_PIPELINE_FILE), snapshotDir);
    PitchOnnxModel onnx = new PitchOnnxModel(snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE));
    IsotonicCalibratorJava calibrator = loadCalibrator(snapshotDir);
    log.info(
        "loaded pitch POST model {}/{} (id={}) from {} (onnx input={})",
        modelName,
        version,
        versionId,
        snapshotDir,
        onnx.inputName());
    return new LoadedPitchModel(
        versionId,
        modelName,
        version,
        schemaHash,
        Head.POST,
        null,
        pipeline,
        onnx,
        calibrator,
        pipeline.spec().classLabels());
  }

  /**
   * Resolve the calibrator file from the snapshot's {@code metadata.json} ({@code calibrator.path},
   * relative to the snapshot dir) when present, else fall back to the canonical {@code
   * calibrator.json}. The metadata pointer keeps this from hardcoding the filename the way the
   * legacy service does - a future snapshot that names the calibrator differently still loads.
   */
  private static IsotonicCalibratorJava loadCalibrator(Path snapshotDir)
      throws IOException, OrtException {
    Path metadataPath = snapshotDir.resolve(SnapshotStorage.METADATA_FILE);
    Path calibratorPath = snapshotDir.resolve(SnapshotStorage.CALIBRATOR_FILE);
    if (Files.isRegularFile(metadataPath)) {
      String relName = readCalibratorRelPath(metadataPath);
      if (relName != null && !relName.isBlank()) {
        Path fromMeta = snapshotDir.resolve(relName).normalize();
        if (Files.isRegularFile(fromMeta)) {
          calibratorPath = fromMeta;
        }
      }
    }
    if (!Files.isRegularFile(calibratorPath)) {
      throw new IOException(
          "pitch snapshot at "
              + snapshotDir
              + " has no calibrator (expected "
              + calibratorPath
              + "); registration must place calibrator.json beside model.onnx");
    }
    return IsotonicCalibratorJava.load(calibratorPath);
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** Read {@code calibrator.path} out of metadata.json, or null when the pointer is absent. */
  private static String readCalibratorRelPath(Path metadataPath) throws IOException {
    com.fasterxml.jackson.databind.JsonNode root =
        MAPPER.readTree(Files.readAllBytes(metadataPath));
    com.fasterxml.jackson.databind.JsonNode cal = root.path("calibrator").path("path");
    return cal.isTextual() ? cal.asText() : null;
  }

  /** Calibrated 5-class distribution for the PRE head. */
  public Map<String, Double> predictPre(FeaturePipelinePitchPre.Request req) throws OrtException {
    if (head != Head.PRE) {
      throw new IllegalStateException(
          "predictPre called on a " + head + " model (" + modelName + "/" + version + ")");
    }
    float[] vector = prePipeline.transform(req);
    return calibrateAndPack(onnx.predict(vector));
  }

  /** Calibrated 5-class distribution for the POST head. */
  public Map<String, Double> predictPost(FeaturePipelinePitchPost.Request req) throws OrtException {
    if (head != Head.POST) {
      throw new IllegalStateException(
          "predictPost called on a " + head + " model (" + modelName + "/" + version + ")");
    }
    float[] vector = postPipeline.transform(req);
    return calibrateAndPack(onnx.predict(vector));
  }

  /**
   * Apply the per-class isotonic calibrator in double precision then pack into a label-keyed map.
   * Identical arithmetic to {@link PitchInferenceService#calibrateAndPack} so parity holds against
   * the same fixture at 1e-6.
   */
  private Map<String, Double> calibrateAndPack(float[] rawProbs) {
    double[] asDouble = new double[rawProbs.length];
    for (int i = 0; i < rawProbs.length; i++) {
      asDouble[i] = rawProbs[i];
    }
    double[][] calibrated = calibrator.transform(new double[][] {asDouble});
    Map<String, Double> out = new LinkedHashMap<>();
    for (int c = 0; c < classLabels.size(); c++) {
      out.put(classLabels.get(c), calibrated[0][c]);
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

  public Head head() {
    return head;
  }

  public List<String> classLabels() {
    return classLabels;
  }

  @Override
  public void close() throws OrtException {
    onnx.close();
  }
}
