package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Predicate;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.SnapshotStorage;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Loads {@link LoadedBattedBallModel} (pre/post pitch heads, and the pitch-type family) by registry
 * {@code version_id}. Caches loaded bundles in memory (Caffeine, default 4 per model — covers
 * champion + shadow + one in-flight rollback + one warm-up slot; the pitch-type cache gets DOUBLE
 * that because two registry families share it). On eviction the bundle is {@link
 * AutoCloseable#close() closed} so ORT sessions get released — the Caffeine {@code removalListener}
 * starts the close, and the bundle's {@code SessionGuard} (task #87 / H2) makes it SAFE: close
 * retires the bundle, waits for in-flight native runs to drain, and closes exactly once. A caller
 * holding a just-evicted bundle gets a typed {@link ModelUnavailableException} instead of a native
 * close-under-use, and the {@code getLive} recheck reloads a retired bundle before it ever reaches
 * the caller in the common case.
 *
 * <p>Executor note: removal notifications run on Caffeine's default executor (the common pool), and
 * a guarded close can now occupy that thread for up to the drain bound when a run is in flight.
 * Typical cost is one forward pass (~p99 14ms); the pathological case (evictions over STUCK runs
 * pinning several common-pool workers for 5s each) is accepted rather than given a dedicated
 * executor - graceful shutdown means the {@code @PreDestroy} sweep normally finds nothing in
 * flight, and eviction churn at that rate is a cache-sizing defect to fix, not to absorb.
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
  private volatile boolean closed;
  private final Cache<Long, LoadedBattedBallModel> battedBallCache;
  private final Cache<Long, LoadedAllParksModel> allParksCache;
  private final Cache<Long, LoadedPitchModel> pitchPreCache;
  private final Cache<Long, LoadedPitchModel> pitchPostCache;
  private final Cache<Long, LoadedPitchTypeModel> pitchTypeCache;

  public ModelLoader(
      RegistryService registry, @Value("${bullpen.model-loader.cache-size:4}") int cacheSize) {
    this.registry = registry;
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
            // 2x: serves TWO registry families (battedball_outcome + lr_baseline_batted_ball) -
            // see the family-per-cache map comment below.
            .maximumSize(2L * cacheSize)
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
    // Two pitch caches keyed by version_id (rule 9: pre + post are separate registry models, so a
    // given version_id loads into exactly one cache; the two never hold the same key).
    // A5 (task #87): every cache that serves TWO registry families gets BOTH families' budgets -
    // a single budget halves the champion+shadow+rollback+warmup policy the class javadoc states
    // and churns under it. The family-per-cache map, verified against RegistryBaselines +
    // ModelLoadValidator's dispatch: pitchPre serves pitch_outcome_pre + the SHARED
    // pitch_outcome_lr_baseline (the baseline row both heads declare dispatches by its
    // metadata head=pre into THIS cache, so pitchPost holds only pitch_outcome_post - 1x);
    // allParks serves battedball_outcome + lr_baseline_batted_ball (both take the park_order
    // branch); pitchType serves pitch_type_pre + pitch_type_lr_baseline. The toy single-float
    // battedBallCache is one family. This map is a snapshot of TODAY's fleet, not a closed set:
    // battedball_lgbm_per_park also exports park_order and would become allParks' THIRD family if
    // it ever registers - re-derive the map (and the multipliers) when the fleet changes.
    this.pitchPreCache = buildPitchCache("pre", 2 * cacheSize);
    this.pitchPostCache = buildPitchCache("post", cacheSize);
    this.pitchTypeCache = buildPitchTypeCache(2 * cacheSize);
    log.info(
        "ModelLoader ready: per-family cache size={} (two-family caches allParks/pitchPre/"
            + "pitchType sized {})",
        cacheSize,
        2 * cacheSize);
  }

  private static Cache<Long, LoadedPitchModel> buildPitchCache(String head, int cacheSize) {
    return Caffeine.newBuilder()
        .maximumSize(cacheSize)
        .removalListener(
            (Long key, LoadedPitchModel value, RemovalCause cause) -> {
              if (value == null) {
                return;
              }
              try {
                value.close();
                log.info(
                    "ModelLoader: evicted pitch {} version_id={} (cause={})", head, key, cause);
              } catch (OrtException e) {
                log.warn("ModelLoader: failed to close evicted pitch {} model_id={}", head, key, e);
              }
            })
        .build();
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
    return getLive(
        battedBallCache, versionId, this::loadBattedBallFresh, LoadedBattedBallModel::isRetired);
  }

  private LoadedBattedBallModel loadBattedBallFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      // BUG-1b: resolve the contract from THIS model's snapshot (mirrors LoadedAllParksModel.load),
      // not a process-wide ../contracts/feature_pipeline_toy.json default that every registered
      // version shared. A version registered against a different contract used to silently load the
      // toy contract; now each version loads its own feature_pipeline.json. The single-park toy
      // SERVING route under _toy_batted_ball (decision [146]) is untouched - that bean ships its
      // own
      // contract and never goes through ModelLoader.
      Path snapshotContract = r.snapshotDir().resolve(SnapshotStorage.FEATURE_PIPELINE_FILE);
      return LoadedBattedBallModel.load(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir(),
          snapshotContract);
    } catch (IOException | OrtException e) {
      throw new ModelUnavailableException(
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
    return getLive(
        allParksCache, versionId, this::loadAllParksFresh, LoadedAllParksModel::isRetired);
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
      throw new ModelUnavailableException(
          "ModelLoader: failed to load all-parks model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  /**
   * Get (or load) the PRE pitch head ({@code pitch_outcome_pre}) for {@code versionId} (W1). Same
   * atomic-load + S3-archive guard as {@link #loadBattedBall}, yielding a {@link LoadedPitchModel}.
   * Rule 9: pre + post are separate registry models loaded through separate caches.
   */
  public LoadedPitchModel loadPitchPre(long versionId) {
    return getLive(pitchPreCache, versionId, this::loadPitchPreFresh, LoadedPitchModel::isRetired);
  }

  private LoadedPitchModel loadPitchPreFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      return LoadedPitchModel.loadPre(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir());
    } catch (IOException | OrtException e) {
      throw new ModelUnavailableException(
          "ModelLoader: failed to load pitch PRE model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  /**
   * Get (or load) the POST pitch head ({@code pitch_outcome_post}) for {@code versionId} (W1). Same
   * contract as {@link #loadPitchPre} but yields the 41-feature post-head bundle. Rule 9: separate
   * registry model, separate cache.
   */
  public LoadedPitchModel loadPitchPost(long versionId) {
    return getLive(
        pitchPostCache, versionId, this::loadPitchPostFresh, LoadedPitchModel::isRetired);
  }

  private LoadedPitchModel loadPitchPostFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      return LoadedPitchModel.loadPost(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir());
    } catch (IOException | OrtException e) {
      throw new ModelUnavailableException(
          "ModelLoader: failed to load pitch POST model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  /**
   * Resolve a registry row to its local snapshot directory, enforcing the S3-archive guard
   * (archived versions must be restored via the registry-snapshot-recovery runbook before they can
   * load). Shared by {@link #loadBattedBallFresh}, {@link #loadAllParksFresh}, {@link
   * #loadPitchPreFresh}, and {@link #loadPitchPostFresh}.
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
      throw new ModelUnavailableException(
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

  private static Cache<Long, LoadedPitchTypeModel> buildPitchTypeCache(int cacheSize) {
    return Caffeine.newBuilder()
        .maximumSize(cacheSize)
        .removalListener(
            (Long key, LoadedPitchTypeModel value, RemovalCause cause) -> {
              if (value == null) {
                return;
              }
              try {
                value.close();
                log.info("ModelLoader: evicted pitch-type version_id={} (cause={})", key, cause);
              } catch (OrtException e) {
                log.warn("ModelLoader: failed to close evicted pitch-type model_id={}", key, e);
              }
            })
        .build();
  }

  /**
   * Get (or load) a pitch-TYPE model ({@code pitch_type_pre} or its rule-9 {@code
   * pitch_type_lr_baseline}) for {@code versionId} (decision [183]).
   *
   * <p>One cache serves both rows: they share a contract and a serving shape, and keying by
   * versionId already keeps distinct registry rows distinct. Rule 9 is about separate REGISTRY
   * rows, which they have - it does not require a duplicated cache.
   */
  public LoadedPitchTypeModel loadPitchType(long versionId) {
    return getLive(
        pitchTypeCache, versionId, this::loadPitchTypeFresh, LoadedPitchTypeModel::isRetired);
  }

  private LoadedPitchTypeModel loadPitchTypeFresh(long versionId) {
    ResolvedSnapshot r = resolveSnapshot(versionId);
    try {
      return LoadedPitchTypeModel.load(
          versionId,
          r.mv().modelName(),
          r.mv().version(),
          r.mv().featureSchemaHash(),
          r.snapshotDir());
    } catch (IOException | OrtException e) {
      throw new ModelUnavailableException(
          "ModelLoader: failed to load pitch-type model "
              + r.mv().naturalKey()
              + " from "
              + r.snapshotDir(),
          e);
    }
  }

  private record ResolvedSnapshot(ModelVersion mv, Path snapshotDir) {}

  /**
   * Cache read with a retired-recheck (task #87 / H2): between the cache's internal get and the
   * caller's native call, the entry can be evicted and its guard retired. The recheck shrinks that
   * window to near-zero by reloading ONCE when the returned bundle is already retired; the residual
   * race is closed by the SessionGuard itself, whose typed refusal callers already map to a 503.
   * Deliberately no retry loop: a second retired hit within one request means eviction is churning
   * faster than a request, which is a cache-sizing problem to surface loudly, not to spin on.
   */
  private <M> M getLive(
      Cache<Long, M> cache, long versionId, Function<Long, M> fresh, Predicate<M> retired) {
    if (closed) {
      // Without this, a load racing the @PreDestroy sweep would repopulate the cache with a
      // fresh session nothing ever closes - in a many-context test JVM that is exactly the
      // accumulation the direct-close shutdown exists to stop.
      throw new IllegalStateException("ModelLoader is closed - no loads after shutdown began");
    }
    M m = cache.get(versionId, fresh);
    if (retired.test(m)) {
      // CONDITIONAL removal (remove-if-still-this-instance), not invalidate: an unconditional
      // invalidate could evict a FRESH bundle another thread just reloaded under the same key,
      // whose removal listener would retire it - self-inflicting exactly the stale-reference
      // refusal this recheck exists to eliminate.
      cache.asMap().remove(versionId, m);
      m = cache.get(versionId, fresh);
    }
    return m;
  }

  /** Visible for tests + warm-up: hint that {@code versionId} is no longer needed in cache. */
  public void invalidate(long versionId) {
    battedBallCache.invalidate(versionId);
    allParksCache.invalidate(versionId);
    pitchPreCache.invalidate(versionId);
    pitchPostCache.invalidate(versionId);
    pitchTypeCache.invalidate(versionId);
  }

  @PreDestroy
  public void close() {
    closed = true;
    // Close bundles DIRECTLY rather than relying on the removalListener: Caffeine delivers
    // removal notifications ASYNCHRONOUSLY on its executor (the previous comment here claimed
    // cleanUp() fires them synchronously - it does not), so a shutdown that only invalidates can
    // exit before any session is closed - and in a many-context test JVM those native sessions
    // accumulate across contexts. Guarded close is idempotent, so the listener's own later close
    // attempt for the same bundle is a harmless no-op.
    closeAll(battedBallCache, "batted-ball");
    closeAll(allParksCache, "all-parks");
    closeAll(pitchPreCache, "pitch pre");
    closeAll(pitchPostCache, "pitch post");
    closeAll(pitchTypeCache, "pitch-type");
    log.info("ModelLoader: shut down, all cached sessions released");
  }

  private static void closeAll(Cache<Long, ? extends AutoCloseable> cache, String what) {
    cache
        .asMap()
        .forEach(
            (id, bundle) -> {
              try {
                bundle.close();
              } catch (Exception e) {
                log.warn("ModelLoader: failed to close {} version_id={} at shutdown", what, id, e);
              }
            });
    cache.invalidateAll();
  }
}
