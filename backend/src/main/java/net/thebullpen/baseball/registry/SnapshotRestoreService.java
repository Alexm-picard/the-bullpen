package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Snapshot file plumbing for the registry: the "what goes in the copy-list" policy for a
 * registration (extracted from {@code RegistryService.doInsert}) and the restore-from-archive
 * wrapper. {@link SnapshotStorage#placeArtifacts} stays the dumb copy of a caller-supplied map;
 * this service owns the POLICY of assembling that map (the ONNX + metadata + feature_pipeline plus
 * the optional calibrator + ONNX external-data sidecar + the pipeline-declared Tier-2 lookups), the
 * post-place assertion, and deriving the canonical registered paths.
 *
 * <p>Deliberately holds no registration/promotion DISCIPLINE: the rule-7 feature-schema hash, the
 * rule-5 promotion-evidence gate, and the rule-9 baseline gate all stay in {@link RegistryService}.
 * The dependency graph is one-directional: {@code RegistryService -> SnapshotRestoreService ->
 * SnapshotStorage}.
 */
@Service
public class SnapshotRestoreService {

  private static final Logger log = LoggerFactory.getLogger(SnapshotRestoreService.class);

  /** Reads {@code lookup_path} declarations out of a model's {@code feature_pipeline.json}. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final SnapshotStorage snapshotStorage;

  public SnapshotRestoreService(SnapshotStorage snapshotStorage) {
    this.snapshotStorage = snapshotStorage;
  }

  /** The canonical on-disk paths a staged snapshot exposes to {@code repo.insert}. */
  public record StagedSnapshot(String canonicalArtifactPath, String canonicalMetadataPath) {}

  /**
   * Copy the caller's source files into the canonical snapshot layout and return the registered
   * paths. NOT {@code @Transactional} on purpose: it is called from within {@code
   * RegistryService.register}'s transaction and must join it exactly as the inline block did (file
   * I/O + {@code placeArtifacts}, no new tx boundary).
   */
  public StagedSnapshot stageForRegistration(RegisterRequest req) {
    // 3a.5: copy the caller's source files into the canonical snapshot layout
    // <local-base>/<model_name>/<version>/{model.onnx, metadata.json, feature_pipeline.json}.
    // The registered paths point at the canonical destination so retention + restore have a
    // single place to flip. featurePipelinePath isn't a tracked column (the schema_hash is the
    // proxy), but we still archive the file so the pipeline can be reconstituted from S3.
    // BUG-1c: also copy the calibrator + ONNX external-data sidecar when the trainer produced them
    // beside the model. Both are co-located in the source dir but were omitted from the copy-list,
    // so registered snapshots served UNCALIBRATED (no calibrator.json) and external-data ONNX
    // models failed to load (no model.onnx.data). Both are optional - the toy / small in-graph
    // models have neither, so include only when the source file is actually present.
    Path artifactSource = Path.of(req.artifactPath());
    Path featurePipelineSource = Path.of(req.featurePipelinePath());
    Map<String, Path> sources = new java.util.LinkedHashMap<>();
    sources.put(SnapshotStorage.ARTIFACT_FILE, artifactSource);
    sources.put(SnapshotStorage.METADATA_FILE, Path.of(req.metadataPath()));
    sources.put(SnapshotStorage.FEATURE_PIPELINE_FILE, featurePipelineSource);
    Path sourceDir = artifactSource.getParent();
    if (sourceDir != null) {
      Path calibrator = sourceDir.resolve(SnapshotStorage.CALIBRATOR_FILE);
      if (Files.isRegularFile(calibrator)) {
        sources.put(SnapshotStorage.CALIBRATOR_FILE, calibrator);
      }
      Path externalData = sourceDir.resolve(SnapshotStorage.ARTIFACT_FILE + ".data");
      if (Files.isRegularFile(externalData)) {
        sources.put(SnapshotStorage.ARTIFACT_FILE + ".data", externalData);
      }
    }
    // BUG-1c-for-pitch (W4a): a registered pitch model resolves its Tier-2 lookups
    // (park_id_mapping.json, pitcher_te.json, batter_te.json, and for post pitch_type_mapping.json)
    // from the snapshot dir at load time - LoadedPitchModel.loadPre / loadPost fail loud when any
    // is
    // absent. Drive the extra copies off the feature_pipeline.json itself: every lookup the serving
    // pipeline needs is DECLARED as a `lookup_path` under one of its `preprocess` entries. This
    // stays model-agnostic: any model that declares lookups gets them copied, no per-model-name
    // branch, and a contract with no lookups (the toy / batted-ball in-graph models) is a no-op.
    //
    // Co-located-and-optional, exactly like the calibrator / external-data sidecars above: copy
    // each
    // declared lookup that is actually present beside the model. We do NOT hard-fail here when a
    // declared lookup is absent from the SOURCE dir - that keeps registration decoupled from how
    // the
    // caller stages files, and the real fail-loud for a genuinely-missing lookup already lives at
    // LOAD time in LoadedPitchModel, which serves before any user sees a prediction. What we DO
    // assert post-copy is that everything we asked placeArtifacts to relocate actually landed -
    // that
    // catches a copy-list / placeArtifacts regression at registration rather than at first load.
    Set<String> declaredLookups = declaredLookupFiles(featurePipelineSource);
    Set<String> copiedLookups = new LinkedHashSet<>();
    if (sourceDir != null) {
      for (String lookup : declaredLookups) {
        Path lookupSource = sourceDir.resolve(lookup);
        if (Files.isRegularFile(lookupSource)) {
          sources.put(lookup, lookupSource);
          copiedLookups.add(lookup);
        } else {
          log.warn(
              "registry: feature_pipeline for {}/{} declares lookup '{}' but it is not present in"
                  + " the source dir {} - the snapshot will rely on it being placed another way;"
                  + " LoadedPitchModel.loadPre/loadPost will fail loud at load if it is still"
                  + " missing",
              req.modelName(),
              req.version(),
              lookup,
              sourceDir);
        }
      }
    }
    Path snapshotDir = snapshotStorage.placeArtifacts(req.modelName(), req.version(), sources);
    assertLookupsPlaced(snapshotDir, copiedLookups);
    return new StagedSnapshot(
        snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE).toString(),
        snapshotDir.resolve(SnapshotStorage.METADATA_FILE).toString());
  }

  /**
   * Pull an archived version's snapshot back from S3 to local disk and update the tracked paths to
   * the local copies. Wraps {@link SnapshotStorage#restoreVersion(long)} so callers go through the
   * service boundary (the runbook references this method).
   */
  @Transactional
  public Path restoreVersion(long versionId) {
    return snapshotStorage.restoreVersion(versionId);
  }

  /**
   * Read the distinct {@code lookup_path} values declared under the {@code preprocess} block of a
   * model's {@code feature_pipeline.json}. These are the Tier-2 lookup files the serving pipeline
   * resolves from the snapshot dir at load time (pitch pre: park_id_mapping.json, pitcher_te.json,
   * batter_te.json; pitch post: + pitch_type_mapping.json). Model-agnostic: a contract that
   * declares no lookups (the toy / batted-ball in-graph models) yields an empty set and the copy
   * list is unchanged. Order is preserved (insertion order of the declarations) for stable logs.
   *
   * <p>A pipeline whose JSON is unreadable is treated as having no declared lookups - the
   * feature-schema hash check ({@link FeatureSchemaHasher}) already runs against this same file
   * before we get here, so a truly malformed pipeline fails earlier; this method must not throw on
   * an absent {@code preprocess} block (the legacy toy contract has none).
   */
  static Set<String> declaredLookupFiles(Path featurePipelinePath) {
    Set<String> lookups = new LinkedHashSet<>();
    JsonNode root;
    try {
      root = MAPPER.readTree(Files.readAllBytes(featurePipelinePath));
    } catch (IOException e) {
      throw new RegistryException.ArtifactMissing(featurePipelinePath.toString(), e);
    }
    JsonNode preprocess = root.path("preprocess");
    if (!preprocess.isObject()) {
      return lookups;
    }
    for (Map.Entry<String, JsonNode> entry : preprocess.properties()) {
      JsonNode lookupPath = entry.getValue().path("lookup_path");
      if (lookupPath.isTextual() && !lookupPath.asText().isBlank()) {
        lookups.add(lookupPath.asText());
      }
    }
    return lookups;
  }

  /**
   * Post-copy guard (W4a): every lookup we handed to {@link SnapshotStorage#placeArtifacts} (the
   * declared lookups that were present in the source dir) must now exist as a regular file inside
   * the placed snapshot directory. A miss here means {@code placeArtifacts} silently dropped a file
   * we asked it to relocate - a placement / copy-list regression - so we fail loud at registration
   * with {@link RegistryException.ArtifactMissing}, before the row is returned, rather than
   * discovering it at first load. This does NOT assert declared-but-absent-from-source lookups
   * (those are the load-time fail-loud's job in {@link
   * net.thebullpen.baseball.inference.LoadedPitchModel}).
   */
  private static void assertLookupsPlaced(Path snapshotDir, Set<String> copiedLookups) {
    for (String lookup : copiedLookups) {
      Path placed = snapshotDir.resolve(lookup);
      if (!Files.isRegularFile(placed)) {
        throw new RegistryException.ArtifactMissing(
            "lookup '"
                + lookup
                + "' was copied but did not land in the snapshot at "
                + placed
                + " - placeArtifacts is out of sync with the registration copy-list");
      }
    }
  }
}
