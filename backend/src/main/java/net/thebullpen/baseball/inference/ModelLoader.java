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
  private final Cache<Long, LoadedAllParksModel> allParksCache;

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
    this.allParksCache =
        Caffeine.newBuilder()
            .maximumSize(cacheSize)
            .removalListener(
                (Long key, LoadedAllParksModel value, RemovalCause cause) -> {
                  if (value == null) {
                    return;
                  }
                  try {
                    value.close();
                    log.info("ModelLoader: evicted all-parks version_id={} (cause={})", key, cause);
                  } catch (OrtException e) {
                    log.warn("ModelLoader: failed to close evicted all-parks model_id={}", key, e);
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
    // BUG-4: atomic load. Caffeine runs the mapping function at most once per key under contention,
    // so two concurrent cold-cache misses can't each open an ORT session - the get-then-put it
    // replaces let the loser's bundle never reach the cache, so the removalListener never fired for
    // it and its native ORT session leaked (plus a wasted double-load).
    return battedBallCache.get(versionId, this::loadBattedBallFresh);
  }

  private LoadedBattedBallModel loadBattedBallFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      return LoadedBattedBallModel.load(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir(),
          defaultBattedBallContractPath);
    } catch (IOException | OrtException e) {
      throw new IllegalStateException(
          "ModelLoader: failed to load batted-ball model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  /**
   * Get (or load) the real per-park outcome model for {@code versionId} (B4, decision [146]). Same
   * atomic-load + S3-archive guard as {@link #loadBattedBall}, but yields a {@link
   * LoadedAllParksModel} (the {@code [None,15]->[None,30,5]} distribution model) instead of the toy
   * single-float bundle. A given {@code versionId} is one shape or the other, never both, so the
   * two caches never hold the same key.
   */
  public LoadedAllParksModel loadAllParks(long versionId) {
    return allParksCache.get(versionId, this::loadAllParksFresh);
  }

  private LoadedAllParksModel loadAllParksFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      return LoadedAllParksModel.load(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir());
    } catch (IOException | OrtException e) {
      throw new IllegalStateException(
          "ModelLoader: failed to load all-parks model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  /**
   * Resolve a registry row to its local snapshot directory, enforcing the S3-archive guard
   * (archived versions must be restored via the registry-snapshot-recovery runbook before they can
   * load). Shared by {@link #loadBattedBallFresh} and {@link #loadAllParksFresh}.
   */
  private ResolvedSnapshot resolveSnapshot(long versionId) {
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
              + " - run the registry-snapshot-recovery runbook to restore it locally first");
    }
    Path snapshotDir = Path.of(mv.artifactPath()).getParent();
    if (snapshotDir == null) {
      throw new IllegalStateException(
          "artifact path has no parent directory: " + mv.artifactPath());
    }
    return new ResolvedSnapshot(mv, snapshotDir);
  }

  private record ResolvedSnapshot(ModelVersion mv, Path snapshotDir) {}

  /** Visible for tests + warm-up: hint that {@code versionId} is no longer needed in cache. */
  public void invalidate(long versionId) {
    battedBallCache.invalidate(versionId);
    allParksCache.invalidate(versionId);
  }

  @PreDestroy
  public void close() {
    battedBallCache.invalidateAll();
    battedBallCache.cleanUp(); // synchronously fires the removalListener for closed sessions
    allParksCache.invalidateAll();
    allParksCache.cleanUp();
    log.info("ModelLoader: shut down, all cached sessions released");
  }
}
