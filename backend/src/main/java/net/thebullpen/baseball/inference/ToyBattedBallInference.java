package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import net.thebullpen.baseball.config.InferenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Toy batted-ball inference bean (Phase 1.5).
 *
 * <p>Wires the ONNX session and the JSON-driven feature pipeline. One instance per JVM — the ONNX
 * session is reused across requests (creating one per request is what burns the cold-start budget).
 *
 * <p>Bean is profile-gated to {@code api} so the worker JVM doesn't pay the model-load cost.
 */
@Component
@Profile("api")
public class ToyBattedBallInference {

  private static final Logger log = LoggerFactory.getLogger(ToyBattedBallInference.class);

  public static final String MODEL_NAME = "_toy_batted_ball";
  public static final String MODEL_VERSION = "v0";

  private final Path artifactsDir;
  private final Path contractPath;
  private OnnxModel model;
  private FeaturePipeline pipeline;

  public ToyBattedBallInference(InferenceProperties props) {
    // toyContractPath() resolves the legacy shared bullpen.inference.contract-path key with the
    // toy's historical default (see InferenceProperties' javadoc on the shared-key wart).
    this.artifactsDir = Path.of(props.toy().artifactsDir()).toAbsolutePath().normalize();
    this.contractPath = Path.of(props.toyContractPath()).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void init() throws IOException, OrtException {
    Path onnxPath = artifactsDir.resolve("model.onnx");
    Path parkPath = artifactsDir.resolve("park_hr_rate.json");
    if (!onnxPath.toFile().exists()) {
      throw new IOException(
          "toy ONNX model not found at "
              + onnxPath
              + " — run `uv run python -m bullpen_training.battedball.export_toy_onnx` first");
    }
    if (!contractPath.toFile().exists()) {
      throw new IOException("feature pipeline contract not found at " + contractPath);
    }
    this.pipeline = FeaturePipeline.load(contractPath, parkPath);
    this.model = new OnnxModel(onnxPath);
    log.info(
        "ToyBattedBallInference ready model={} version={} features={} schema_hash={}",
        MODEL_NAME,
        MODEL_VERSION,
        pipeline.spec().featureOrder(),
        pipeline.spec().schemaHash());
  }

  @PreDestroy
  public void close() throws OrtException {
    if (model != null) {
      model.close();
    }
  }

  /** Returns p(HR) for the request. Thread-safe — ORT sessions are. */
  public float predict(
      double launchSpeedMph,
      double launchAngleDeg,
      double releaseSpeedMph,
      String parkId,
      String stand)
      throws OrtException {
    FeaturePipeline.RawRow row =
        new FeaturePipeline.RawRow(
            Map.of(
                "launch_speed_mph", launchSpeedMph,
                "launch_angle_deg", launchAngleDeg,
                "release_speed_mph", releaseSpeedMph),
            Map.of("park_id", parkId, "stand", stand));
    return model.predict(pipeline.transform(row));
  }

  public FeaturePipeline.Spec pipelineSpec() {
    return pipeline.spec();
  }
}
