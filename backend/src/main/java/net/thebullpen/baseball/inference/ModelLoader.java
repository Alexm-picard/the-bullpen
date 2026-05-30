package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.SnapshotStorage;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads {@link LoadedBattedBallModel} (and, later, pre/post pitch heads) by registry {@code
 * version_id}. Caches loaded bundles in memory (Caffeine, default 4 per model — covers champion +
 * shadow + one in-flight rollback + one warm-up slot). On eviction the bundle is {@link
 * AutoCloseable#close() closed} so ORT sessions get released — the Caffeine {@code removalListener}
 * is the discipline.
 *
 * <p>Loading reads the registry row for the {@code versionId}, derives the snapshot directory from
 * the row's {@code artifact_path}, and constructs the bundle. {@code s3://}-prefixed paths are
 * rejected with a clear error — operator runs the {@code
 * docs/runbooks/registry-snapshot-recovery.md} runbook to pull the snapshot back to local disk
 * before this load can succeed.
 *
 * <p>Used by:
 *
 * <ul>
 *   <li>{@link InferenceRouter} — to dispatch any registered champion / challenger version.
 *   <li>(future) the warm-up logic that pre-loads every active routing pair.
 * </ul>
 */
@Component
public class ModelLoader {

  private static final Logger log = LoggerFactory.getLogger(ModelLoader.class);

  private final RegistryService registry;
  private final Path defaultBattedBallContractPath;
  private final Cache<Long, LoadedBattedBallModel> battedBallCache;

  public ModelLoader(
      RegistryService registry,
      @Value(
              "${bullpen.model-loader.batted-ball-contract-path:../contracts/feature_pipeline_toy.json}")
          String battedBallContractPath,
      @Value("${bullpen.model-loader.cache-size:4}") int cacheSize) {
    this.registry = registry;
    this.defaultBattedBallContractPath =
        Path.of(battedBallContractPath).toAbsolutePath().normalize();
    this.battedBallCache =
        Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .removalListener(
                (Long key, LoadedBattedBallModel value, RemovalCause cause) -> {
                  if (value == null) {
                    return;
                  }
                  try {
                    value.close();
                    log.info(
                        "ModelLoader: evicted batted-ball version_id={} (cause={})", key, cause);
                  } catch (OrtException e) {
                    log.warn("ModelLoader: failed to close evicted model_id={}", key, e);
                  }
                })
            .build();
    log.info(
        "ModelLoader ready: batted-ball cache size={} contract={}",
        cacheSize,
        defaultBattedBallContractPath);
  }

  /**
   * Get (or load) the batted-ball model for {@code versionId}. Throws if the version isn't in the
   * registry or its artifacts aren't local (S3-archived versions must be restored first via the
   * 3a.5 runbook).
   */
  public LoadedBattedBallModel loadBattedBall(long versionId) {
    LoadedBattedBallModel cached = battedBallCache.getIfPresent(versionId);
    if (cached != null) {
      return cached;
    }
    LoadedBattedBallModel fresh = loadBattedBallFresh(versionId);
    battedBallCache.put(versionId, fresh);
    return fresh;
  }

  private LoadedBattedBallModel loadBattedBallFresh(long versionId) {
    ModelVersion mv =
        registry
            .getById(versionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "ModelLoader: no model_version with id " + versionId));
    if (SnapshotStorage.isS3Uri(mv.artifactPath())) {
      throw new IllegalStateException(
          "ModelLoader: model_version "
              + versionId
              + " ("
              + mv.naturalKey()
              + ") is archived to "
              + mv.artifactPath()
              + " — run the registry-snapshot-recovery runbook to restore it locally first");
    }
    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    if (snapshotDir == null) {
      throw new IllegalStateException(
          "artifact path has no parent directory: " + mv.artifactPath());
    }
    try {
      return LoadedBattedBallModel.load(
          versionId,
          mv.modelName(),
          mv.version(),
          mv.featureSchemaHash(),
          snapshotDir,
          defaultBattedBallContractPath);
    } catch (IOException | OrtException e) {
      throw new IllegalStateException(
          "ModelLoader: failed to load batted-ball model "
              + mv.naturalKey()
              + " from "
              + snapshotDir,
          e);
    }
  }

  /** Visible for tests + warm-up: hint that {@code versionId} is no longer needed in cache. */
  public void invalidate(long versionId) {
    battedBallCache.invalidate(versionId);
  }

  @PreDestroy
  public void close() {
    battedBallCache.invalidateAll();
    battedBallCache.cleanUp(); // synchronously fires the removalListener for closed sessions
    log.info("ModelLoader: shut down, all cached sessions released");
  }
}
