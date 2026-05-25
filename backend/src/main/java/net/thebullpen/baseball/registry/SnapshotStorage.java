package net.thebullpen.baseball.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local + S3-compatible snapshot storage for the model registry — closes Risk Register G7
 * (retention to R2) and implements ADR-0007's single-storage-abstraction discipline.
 *
 * <p>Directory layout (mirrors leaf 3a.5):
 *
 * <pre>{@code
 * <local-base>/<model_name>/<version>/
 *   model.onnx
 *   metadata.json
 *   feature_pipeline.json
 *   calibrator.json        (when present; produced by 2a.5 isotonic)
 *   training_data.parquet  (decision [68])
 * }</pre>
 *
 * <p>S3 archive prefix mirrors the same layout under {@code
 * <archive-prefix>/<model_name>/<version>/} in the configured bucket (default {@code bullpen-prod},
 * configurable via {@code bullpen.s3.bucket}).
 *
 * <p>{@link #placeArtifacts} is a side-effect-only file copy — called by {@link RegistryService}
 * AFTER successful registration so the registry insert is the commit point. {@link
 * #enforceRetention} runs after every successful registration and keeps the last {@code
 * bullpen.snapshot.keep-locally} (default 5) non-archived versions on disk; older candidate /
 * archived rows get uploaded and have their paths flipped to S3 URIs. CHAMPION and SHADOW rows are
 * NEVER archived locally (leaf "Known edge cases"). {@link #restoreVersion} reverses the flip —
 * pulls a version's directory back from S3 to local and updates the paths.
 *
 * <p>The bean accepts an {@link Optional} of {@link R2ArchiveClient} so dev environments without
 * {@code S3_ENDPOINT_URL} bring the context up without error — the retention sweep just no-ops in
 * that case (logged at INFO once per call).
 */
@Component
public class SnapshotStorage {

  private static final Logger log = LoggerFactory.getLogger(SnapshotStorage.class);

  /** S3 URIs land in artifact_path with this prefix so callers can distinguish local vs remote. */
  static final String S3_URI_SCHEME = "s3://";

  /**
   * Canonical filenames inside a snapshot directory. Callers pass a {@link Map} of {canonical-name
   * -> source-path} to {@link #placeArtifacts}; the source filenames don't need to match (the
   * Python trainer may produce {@code pitch_outcome_pre_v1_model.onnx} but inside the snapshot it
   * lands as {@code model.onnx}). Callers are expected to pick names from this list.
   */
  public static final String ARTIFACT_FILE = "model.onnx";

  public static final String METADATA_FILE = "metadata.json";
  public static final String FEATURE_PIPELINE_FILE = "feature_pipeline.json";
  public static final String CALIBRATOR_FILE = "calibrator.json";
  public static final String TRAINING_DATA_FILE = "training_data.parquet";

  private final RegistryRepository repo;
  private final Optional<R2ArchiveClient> archive;
  private final Path localBase;
  private final int keepLocally;
  private final String archivePrefix;

  public SnapshotStorage(
      RegistryRepository repo,
      Optional<R2ArchiveClient> archive,
      @Value("${bullpen.snapshot.local-base-path:./data/models}") String localBase,
      @Value("${bullpen.snapshot.keep-locally:5}") int keepLocally,
      @Value("${bullpen.snapshot.archive-prefix:models-archive}") String archivePrefix) {
    this.repo = repo;
    this.archive = archive;
    this.localBase = Path.of(localBase);
    this.keepLocally = keepLocally;
    this.archivePrefix = archivePrefix;
    if (archive.isEmpty()) {
      log.info(
          "SnapshotStorage: no R2ArchiveClient bean — retention sweep + restore disabled."
              + " Set S3_ENDPOINT_URL to enable. Local artifact placement still active.");
    } else {
      log.info(
          "SnapshotStorage: localBase={} keepLocally={} archivePrefix={} bucket={}",
          this.localBase,
          this.keepLocally,
          this.archivePrefix,
          archive.get().bucket());
    }
  }

  // --- local placement ----------------------------------------------------

  /**
   * Copy each entry of {@code sources} into the canonical snapshot location {@code
   * <localBase>/<modelName>/<version>/<canonicalName>}, returning the target directory. The map key
   * is the canonical filename (use {@link #ARTIFACT_FILE}, {@link #METADATA_FILE}, etc.); the value
   * is the source path on disk. Source filenames don't need to match the canonical name — the
   * trainer might produce {@code pitch_outcome_pre_v1.onnx} but inside the snapshot it lands as
   * {@code model.onnx}.
   *
   * <p>Every source path must exist (caller's responsibility — {@link RegistryService.register}
   * already validates via {@code assertArtifactExists} before calling here). Existing destination
   * files are overwritten — re-registration is idempotent on {@code (modelName, version)} and the
   * feature-schema hash check from 3a.3 already forbids same-key + different-content.
   */
  public Path placeArtifacts(String modelName, String version, Map<String, Path> sources) {
    Path target = localBase.resolve(modelName).resolve(version);
    try {
      Files.createDirectories(target);
    } catch (IOException e) {
      throw new SnapshotStorageException("could not create snapshot dir " + target, e);
    }
    for (Map.Entry<String, Path> entry : sources.entrySet()) {
      Path src = entry.getValue();
      if (!Files.exists(src)) {
        throw new SnapshotStorageException(
            "snapshot source missing for " + modelName + "/" + version + ": " + src);
      }
      Path dst = target.resolve(entry.getKey());
      try {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        throw new SnapshotStorageException("could not copy " + src + " -> " + dst, e);
      }
    }
    log.info(
        "snapshot: placed {} files for {}/{} at {}", sources.size(), modelName, version, target);
    return target;
  }

  // --- retention ----------------------------------------------------------

  /**
   * Archive every non-CHAMPION / non-SHADOW row beyond {@code keepLocally} most-recent (by {@code
   * trained_at} desc) for {@code modelName}. Upload its directory to S3, update the tracked paths,
   * then delete the local directory. CHAMPION + SHADOW are never archived locally regardless of
   * count (live serving must always read from local disk).
   *
   * <p>Per-version upload-then-flip-then-delete order matters: if the upload throws, the local
   * files stay intact and the path doesn't change, so the next sweep retries cleanly.
   *
   * <p>No-op when no R2 client is configured.
   */
  @Transactional
  public void enforceRetention(String modelName) {
    if (archive.isEmpty()) {
      return;
    }
    List<ModelVersion> archivableNonLive =
        repo.findByName(modelName).stream()
            .filter(v -> v.stage() != Stage.CHAMPION && v.stage() != Stage.SHADOW)
            .filter(v -> !isS3Uri(v.artifactPath())) // already archived
            .sorted(Comparator.comparing(ModelVersion::trainedAt).reversed())
            .toList();
    if (archivableNonLive.size() <= keepLocally) {
      return;
    }
    List<ModelVersion> toArchive = archivableNonLive.subList(keepLocally, archivableNonLive.size());
    log.info(
        "snapshot: archiving {} version(s) of {} to s3://{}/{}",
        toArchive.size(),
        modelName,
        archive.get().bucket(),
        archivePrefix);
    for (ModelVersion mv : toArchive) {
      archiveSingle(mv);
    }
  }

  private void archiveSingle(ModelVersion mv) {
    R2ArchiveClient client = archive.orElseThrow();
    Path localDir = localBase.resolve(mv.modelName()).resolve(mv.version());
    if (!Files.isDirectory(localDir)) {
      log.warn(
          "snapshot: archive skipped for {}/{} — local dir missing at {}",
          mv.modelName(),
          mv.version(),
          localDir);
      return;
    }
    String keyPrefix = archivePrefix + "/" + mv.modelName() + "/" + mv.version();
    client.uploadDirectory(localDir, keyPrefix);

    String s3Artifact = s3Uri(client.bucket(), keyPrefix, "model.onnx");
    String s3Metadata = s3Uri(client.bucket(), keyPrefix, "metadata.json");
    repo.updatePaths(mv.id(), s3Artifact, s3Metadata);
    deleteDirectory(localDir);
    log.info(
        "snapshot: archived {}/{} (id={}) to {}", mv.modelName(), mv.version(), mv.id(), keyPrefix);
  }

  // --- restore ------------------------------------------------------------

  /**
   * Pull an archived version back from S3 to local disk and flip its paths. No-op (with a warning)
   * when the version's paths don't look like S3 URIs — restoring a version that's already local
   * would be a bug in the caller.
   */
  @Transactional
  public Path restoreVersion(long versionId) {
    if (archive.isEmpty()) {
      throw new SnapshotStorageException(
          "restoreVersion(" + versionId + ") requires an R2 client (S3_ENDPOINT_URL unset)");
    }
    ModelVersion mv =
        repo.findById(versionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "registry: no model_version with id " + versionId));
    if (!isS3Uri(mv.artifactPath())) {
      log.warn(
          "snapshot: restoreVersion({}) is a no-op — artifact_path is already local: {}",
          versionId,
          mv.artifactPath());
      return Path.of(mv.artifactPath()).getParent();
    }
    R2ArchiveClient client = archive.orElseThrow();
    String keyPrefix = archivePrefix + "/" + mv.modelName() + "/" + mv.version();
    Path localDir = localBase.resolve(mv.modelName()).resolve(mv.version());
    try {
      Files.createDirectories(localDir);
    } catch (IOException e) {
      throw new SnapshotStorageException("could not create restore target " + localDir, e);
    }
    client.downloadDirectory(keyPrefix, localDir);
    String newArtifact = localDir.resolve("model.onnx").toString();
    String newMetadata = localDir.resolve("metadata.json").toString();
    repo.updatePaths(mv.id(), newArtifact, newMetadata);
    log.info(
        "snapshot: restored {}/{} (id={}) from {} to {}",
        mv.modelName(),
        mv.version(),
        mv.id(),
        keyPrefix,
        localDir);
    return localDir;
  }

  // --- helpers ------------------------------------------------------------

  static boolean isS3Uri(String path) {
    return path != null && path.startsWith(S3_URI_SCHEME);
  }

  private static String s3Uri(String bucket, String keyPrefix, String file) {
    return S3_URI_SCHEME + bucket + "/" + keyPrefix + "/" + file;
  }

  private static void deleteDirectory(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new SnapshotStorageException("could not delete " + p, e);
                }
              });
    } catch (IOException e) {
      throw new SnapshotStorageException("could not walk " + dir + " for delete", e);
    }
  }
}
