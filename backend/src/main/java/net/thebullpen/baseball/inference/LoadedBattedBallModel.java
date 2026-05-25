package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import java.io.IOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable bundle of one batted-ball model version's artifacts — used by {@link ModelLoader} so
 * the A/B router (3b.3) can serve any registered version, not just the hardcoded v0 the legacy
 * {@link ToyBattedBallInference} bean ships with.
 *
 * <p>Owns an {@link OnnxModel} + {@link FeaturePipeline} pair. Closed when the {@link ModelLoader}
 * cache evicts it.
 */
public final class LoadedBattedBallModel implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(LoadedBattedBallModel.class);

  private final long versionId;
  private final String modelName;
  private final String version;
  private final String schemaHash;
  private final OnnxModel onnx;
  private final FeaturePipeline pipeline;

  private LoadedBattedBallModel(
      long versionId,
      String modelName,
      String version,
      String schemaHash,
      OnnxModel onnx,
      FeaturePipeline pipeline) {
    this.versionId = versionId;
    this.modelName = modelName;
    this.version = version;
    this.schemaHash = schemaHash;
    this.onnx = onnx;
    this.pipeline = pipeline;
  }

  /**
   * Load the artifacts for a registered version from the canonical snapshot location.
   *
   * <p>{@code snapshotDir} is the parent of the row's {@code artifact_path} (i.e. the directory
   * holding {@code model.onnx} + {@code feature_pipeline.json} + sibling files).
   *
   * <p>{@code parkHrRatePath} is the auxiliary lookup the toy pipeline needs — defaults to the
   * sibling {@code park_hr_rate.json} when present, or the global contract sibling otherwise.
   */
  public static LoadedBattedBallModel load(
      long versionId,
      String modelName,
      String version,
      String schemaHash,
      Path snapshotDir,
      Path contractPath)
      throws IOException, OrtException {
    Path onnxPath = snapshotDir.resolve("model.onnx");
    Path parkPath = snapshotDir.resolve("park_hr_rate.json");
    FeaturePipeline pipeline = FeaturePipeline.load(contractPath, parkPath);
    OnnxModel onnx = new OnnxModel(onnxPath);
    log.info(
        "loaded batted-ball model {}/{} (id={}) from {}",
        modelName,
        version,
        versionId,
        snapshotDir);
    return new LoadedBattedBallModel(versionId, modelName, version, schemaHash, onnx, pipeline);
  }

  public float predict(
      double launchSpeedMph,
      double launchAngleDeg,
      double releaseSpeedMph,
      String parkId,
      String stand)
      throws OrtException {
    FeaturePipeline.RawRow row =
        new FeaturePipeline.RawRow(
            java.util.Map.of(
                "launch_speed_mph", launchSpeedMph,
                "launch_angle_deg", launchAngleDeg,
                "release_speed_mph", releaseSpeedMph),
            java.util.Map.of("park_id", parkId, "stand", stand));
    return onnx.predict(pipeline.transform(row));
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

  public FeaturePipeline.Spec pipelineSpec() {
    return pipeline.spec();
  }

  @Override
  public void close() throws OrtException {
    onnx.close();
  }
}
