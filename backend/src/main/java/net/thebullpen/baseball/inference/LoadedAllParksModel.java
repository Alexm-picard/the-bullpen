package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.registry.SnapshotStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One registered batted-ball OUTCOME model version wired for serving (B-workstream B4, decision
 * [146]): {@link BattedBallOnnxModel} (the {@code [None,15] -> [None,30,5]} reader) + {@link
 * FeaturePipelineBattedBall} (the 15-feature input, loaded from THIS model's snapshot contract -
 * BUG-1b, not a process-wide default) + {@link BattedBallCalibrators} (per-park isotonic). A
 * prediction runs the 15 scaled features through the ONNX, then calibrates + renormalizes each
 * park's raw softmax, yielding one calibrated outcome distribution per park.
 *
 * <p>Distinct from the toy single-float {@link LoadedBattedBallModel}: this is the real
 * distribution model, loaded by {@code ModelLoader.loadAllParks} and served by {@code
 * PredictAllParksController}. Layering stays {@code api -> inference}: the controller maps its
 * {@code AllParksOutcomeRequest} DTO into the inference-layer {@link
 * FeaturePipelineBattedBall.Request} this class consumes.
 */
public final class LoadedAllParksModel implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LoadedAllParksModel.class);

  private final long versionId;
  private final String modelName;
  private final String version;
  private final String schemaHash;
  private final FeaturePipelineBattedBall pipeline;
  private final BattedBallOnnxModel onnx;
  private final BattedBallCalibrators calibrators;

  LoadedAllParksModel(
      long versionId,
      String modelName,
      String version,
      String schemaHash,
      FeaturePipelineBattedBall pipeline,
      BattedBallOnnxModel onnx,
      BattedBallCalibrators calibrators) {
    this.versionId = versionId;
    this.modelName = modelName;
    this.version = version;
    this.schemaHash = schemaHash;
    this.pipeline = pipeline;
    this.onnx = onnx;
    this.calibrators = calibrators;
  }

  /**
   * Load a registered version's serving artifacts from its snapshot directory (the parent of the
   * registry row's {@code artifact_path}): {@code model.onnx}, {@code feature_pipeline.json},
   * {@code metadata.json}, {@code calibrator.json}. The feature pipeline is loaded from THIS
   * model's snapshot contract (BUG-1b), never a global default.
   */
  public static LoadedAllParksModel load(
      long versionId, String modelName, String version, String schemaHash, Path snapshotDir)
      throws IOException, OrtException {
    FeaturePipelineBattedBall pipeline =
        FeaturePipelineBattedBall.load(
            snapshotDir.resolve(SnapshotStorage.FEATURE_PIPELINE_FILE),
            snapshotDir.resolve(SnapshotStorage.METADATA_FILE));
    BattedBallOnnxModel onnx =
        new BattedBallOnnxModel(snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE));
    BattedBallCalibrators calibrators =
        BattedBallCalibrators.load(snapshotDir.resolve(SnapshotStorage.CALIBRATOR_FILE));
    log.info(
        "loaded all-parks model {}/{} (id={}) from {}", modelName, version, versionId, snapshotDir);
    return new LoadedAllParksModel(
        versionId, modelName, version, schemaHash, pipeline, onnx, calibrators);
  }

  /**
   * Calibrated per-park outcome distribution for one batted ball: an insertion-ordered map from
   * park (in the calibrator's {@code park_order}) to a probability vector over {@link
   * #outcomeOrder()} that sums to 1. The ONNX park axis and the calibrator's {@code park_order}
   * share the exporter's sorted park ordering; a size mismatch is a snapshot defect and fails loud.
   */
  public Map<String, float[]> predict(FeaturePipelineBattedBall.Request req) throws OrtException {
    float[] features = pipeline.transform(req);
    float[][] raw = onnx.predict(features); // [nParks][nOutcomes]
    List<String> parks = calibrators.parkOrder();
    if (raw.length != parks.size()) {
      throw new IllegalStateException(
          "ONNX park axis ("
              + raw.length
              + ") != calibrator park_order ("
              + parks.size()
              + ") for "
              + modelName
              + "/"
              + version);
    }
    Map<String, float[]> byPark = new LinkedHashMap<>(parks.size() * 2);
    for (int p = 0; p < parks.size(); p++) {
      byPark.put(parks.get(p), calibrators.calibrate(parks.get(p), raw[p]));
    }
    return byPark;
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

  /** Outcome class labels for each probability vector (the calibrator's {@code outcome_order}). */
  public List<String> outcomeOrder() {
    return calibrators.outcomeOrder();
  }

  /** Park identifiers in the order {@link #predict} emits them (the calibrator's order). */
  public List<String> parkOrder() {
    return calibrators.parkOrder();
  }

  @Override
  public void close() throws OrtException {
    onnx.close();
  }
}
