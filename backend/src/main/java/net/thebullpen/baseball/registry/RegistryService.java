package net.thebullpen.baseball.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
import net.thebullpen.baseball.registry.dto.ResetFeatureSchemaConfirmation;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates registry operations on top of {@link RegistryRepository} — owns the artifact-
 * presence check, the state machine, and the atomic CHAMPION promotion (archive prior champion in
 * the same transaction).
 *
 * <p>Validation that belongs to other leaves stays out of here:
 *
 * <ul>
 *   <li>Feature schema hash check vs {@code contracts/feature_pipeline.json} — Leaf 3a.3.
 *   <li>Snapshot upload to S3 / R2 — Leaf 3a.5.
 *   <li>HTTP exposure of these calls — Leaf 3a.4.
 *   <li>Pre-declared promotion criteria gate (rule 5) — Leaf 3a.4's promote endpoint.
 * </ul>
 *
 * <p>Register is idempotent on {@code (model_name, version)} (decision [65] + leaf 3a.2): a second
 * register call with the same key returns the existing row unchanged. Repeated registrations are
 * the common case when retrains finish — the trainer doesn't track what's already registered.
 */
@Service
public class RegistryService {

  private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

  /** Reads {@code lookup_path} declarations out of a model's {@code feature_pipeline.json}. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RegistryRepository repo;
  private final FeatureSchemaHasher hasher;
  private final ExperimentResultsRepository experimentRepo;
  private final SnapshotStorage snapshotStorage;
  // @Lazy breaks the circular dep: RoutingService -> RegistryService (challenger lookup) and
  // RegistryService -> RoutingService (ensureRoutingForChampion on promote). The Spring-injected
  // proxy resolves on first call, so by the time we promote the bean is fully constructed.
  private final net.thebullpen.baseball.inference.routing.RoutingService routingService;

  public RegistryService(
      RegistryRepository repo,
      FeatureSchemaHasher hasher,
      ExperimentResultsRepository experimentRepo,
      SnapshotStorage snapshotStorage,
      @org.springframework.context.annotation.Lazy
          net.thebullpen.baseball.inference.routing.RoutingService routingService) {
    this.repo = repo;
    this.hasher = hasher;
    this.experimentRepo = experimentRepo;
    this.snapshotStorage = snapshotStorage;
    this.routingService = routingService;
  }

  // --- register -----------------------------------------------------------

  /**
   * Insert a new {@link Stage#CANDIDATE} row, or return the existing row if {@code (modelName,
   * version)} is already registered (idempotent).
   *
   * <p>Side checks run in order before insert:
   *
   * <ol>
   *   <li>{@code artifactPath} + {@code metadataPath} + {@code featurePipelinePath} all exist on
   *       disk (throws {@link RegistryException.ArtifactMissing} otherwise).
   *   <li>The {@code feature_pipeline.json} is hashed via {@link FeatureSchemaHasher} and compared
   *       to the bootstrap-pinned hash for this {@code modelName}. First-ever registration sets the
   *       pin; subsequent registrations must match (decision [67], rule 7, closes G3). A mismatch
   *       throws {@link RegistryException.FeatureSchemaMismatch}; the {@link
   *       #registerWithBootstrap} escape hatch is the only way to reset.
   * </ol>
   *
   * <p>Local-filesystem-only artifact check for v1; 3a.5 swaps for an S3 HEAD once snapshot storage
   * lands.
   */
  @Transactional
  public ModelVersion register(RegisterRequest req) {
    Optional<ModelVersion> existing = repo.findByNameAndVersion(req.modelName(), req.version());
    if (existing.isPresent()) {
      log.info(
          "registry: register called for already-registered {}/{} — returning existing id={}",
          req.modelName(),
          req.version(),
          existing.get().id());
      return existing.get();
    }
    assertArtifactExists(req.artifactPath());
    assertArtifactExists(req.metadataPath());
    assertArtifactExists(req.featurePipelinePath());

    String candidateHash = hasher.compute(Path.of(req.featurePipelinePath()));
    Optional<String> bootstrapHash = repo.findBootstrapFeatureHash(req.modelName());
    if (bootstrapHash.isEmpty()) {
      log.info(
          "registry: bootstrap feature schema hash for {} = {}", req.modelName(), candidateHash);
    } else if (!bootstrapHash.get().equals(candidateHash)) {
      throw new RegistryException.FeatureSchemaMismatch(
          req.modelName(), bootstrapHash.get(), candidateHash);
    }

    return doInsert(req, candidateHash);
  }

  /**
   * Escape hatch: archive every prior version of {@code modelName} (in the same transaction) and
   * register {@code req} as a fresh bootstrap whose feature schema hash becomes the new pinned
   * value. Requires an explicit {@link ResetFeatureSchemaConfirmation} whose {@code modelName}
   * matches the request, plus a written justification (logged + appended to the new version's
   * {@code notes}).
   *
   * <p>This is the only path that can replace a model's pinned feature schema. The friction is
   * deliberate per the leaf's "reset escape hatch" requirement + the existing {@link
   * RegistryException.ResetConfirmationMissing} guard against typo'd boolean flags.
   */
  @Transactional
  public ModelVersion registerWithBootstrap(
      RegisterRequest req, ResetFeatureSchemaConfirmation confirmation) {
    if (confirmation == null) {
      throw new RegistryException.ResetConfirmationMissing(req.modelName());
    }
    if (!confirmation.modelName().equals(req.modelName())) {
      throw new IllegalArgumentException(
          "registry: ResetFeatureSchemaConfirmation modelName="
              + confirmation.modelName()
              + " doesn't match request modelName="
              + req.modelName());
    }
    assertArtifactExists(req.artifactPath());
    assertArtifactExists(req.metadataPath());
    assertArtifactExists(req.featurePipelinePath());

    String candidateHash = hasher.compute(Path.of(req.featurePipelinePath()));
    int archived = repo.archiveAllForModel(req.modelName());
    log.warn(
        "registry: bootstrap reset for {} — archived {} prior version(s); new pinned hash={};"
            + " reason: {}",
        req.modelName(),
        archived,
        candidateHash,
        confirmation.reason());

    RegisterRequest reqWithReason = appendNotesReason(req, confirmation);
    return doInsert(reqWithReason, candidateHash);
  }

  private ModelVersion doInsert(RegisterRequest req, String featureSchemaHash) {
    // 3a.5: copy the caller's source files into the canonical snapshot layout
    // <local-base>/<model_name>/<version>/{model.onnx, metadata.json, feature_pipeline.json}.
    // The registered paths point at the canonical destination so retention + restore have a
    // single place to flip. featurePipelinePath isn't a tracked column (the schema_hash is the
    // proxy), but we still archive the file so the pipeline can be reconstituted from S3.
    // BUG-1c: also copy the calibrator + ONNX external-data sidecar when the trainer produced them
    // beside the model. Both are co-located in the source dir but were omitted from the copy-list,
    // so registered snapshots served UNCALIBRATED (no calibrator.json) and external-data ONNX
    // models
    // failed to load (no model.onnx.data). Both are optional - the toy / small in-graph models have
    // neither, so include only when the source file is actually present.
    Path artifactSource = Path.of(req.artifactPath());
    Path featurePipelineSource = Path.of(req.featurePipelinePath());
    Map<String, Path> sources = new java.util.LinkedHashMap<>();
    sources.put(SnapshotStorage.ARTIFACT_FILE, artifactSource);
    sources.put(SnapshotStorage.METADATA_FILE, Path.of(req.metadataPath()));
    sources.put(SnapshotStorage.FEATURE_PIPELINE_FILE, featurePipelineSource);
    Path sourceDir = artifactSource.getParent();
    if (sourceDir != null) {
      Path calibrator = sourceDir.resolve(SnapshotStorage.CALIBRATOR_FILE);
      if (java.nio.file.Files.isRegularFile(calibrator)) {
        sources.put(SnapshotStorage.CALIBRATOR_FILE, calibrator);
      }
      Path externalData = sourceDir.resolve(SnapshotStorage.ARTIFACT_FILE + ".data");
      if (java.nio.file.Files.isRegularFile(externalData)) {
        sources.put(SnapshotStorage.ARTIFACT_FILE + ".data", externalData);
      }
    }
    // BUG-1c-for-pitch (W4a): a registered pitch model resolves its Tier-2 lookups
    // (park_id_mapping.json, pitcher_te.json, batter_te.json, and for post pitch_type_mapping.json)
    // from the snapshot dir at load time - LoadedPitchModel.loadPre / loadPost fail loud when any
    // is
    // absent. The 3a.5 copy-list only relocated model.onnx + metadata.json + feature_pipeline.json
    // +
    // calibrator + external-data, so a pitch snapshot loaded with missing lookups and the load
    // threw.
    // Drive the extra copies off the feature_pipeline.json itself: every lookup the serving
    // pipeline
    // needs is DECLARED as a `lookup_path` under one of its `preprocess` entries (the contract the
    // ml-engineer's W4b export driver emits to - it places these beside model.onnx). This stays
    // model-agnostic: any model that declares lookups gets them copied, no per-model-name branch,
    // and
    // a contract with no lookups (the toy / batted-ball in-graph models) is a no-op.
    //
    // Co-located-and-optional, exactly like the calibrator / external-data sidecars above: copy
    // each
    // declared lookup that is actually present beside the model. We do NOT hard-fail here when a
    // declared lookup is absent from the SOURCE dir - that keeps registration decoupled from how
    // the
    // caller stages files (some callers stage the snapshot dir directly), and the real fail-loud
    // for
    // a genuinely-missing lookup already lives at LOAD time in LoadedPitchModel, which serves
    // before
    // any user sees a prediction. What we DO assert post-copy is that everything we asked
    // placeArtifacts to relocate actually landed - that catches a copy-list / placeArtifacts
    // regression at registration rather than at first load.
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
    String canonicalArtifact = snapshotDir.resolve(SnapshotStorage.ARTIFACT_FILE).toString();
    String canonicalMetadata = snapshotDir.resolve(SnapshotStorage.METADATA_FILE).toString();
    ModelVersion inserted =
        repo.insert(
            req.modelName(),
            req.version(),
            canonicalArtifact,
            canonicalMetadata,
            req.trainingDataHash(),
            req.trainingDataWindow(),
            featureSchemaHash,
            req.evalMetricsJson(),
            req.trainedAt(),
            Stage.CANDIDATE,
            req.createdBy(),
            req.notes());
    log.info(
        "registry: registered {}/{} as CANDIDATE (id={})",
        inserted.modelName(),
        inserted.version(),
        inserted.id());
    // Retention sweep: any non-CHAMPION / non-SHADOW row beyond keepLocally is pushed to S3 and
    // its paths flipped. No-op when no R2 client is configured (dev without S3_ENDPOINT_URL).
    snapshotStorage.enforceRetention(inserted.modelName());
    return inserted;
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

  private static RegisterRequest appendNotesReason(
      RegisterRequest req, ResetFeatureSchemaConfirmation confirmation) {
    String prefix = req.notes() == null ? "" : req.notes() + " | ";
    String notesWithReason = prefix + "BOOTSTRAP RESET: " + confirmation.reason();
    return new RegisterRequest(
        req.modelName(),
        req.version(),
        req.artifactPath(),
        req.metadataPath(),
        req.featurePipelinePath(),
        req.trainingDataHash(),
        req.trainingDataWindow(),
        req.evalMetricsJson(),
        req.trainedAt(),
        req.createdBy(),
        notesWithReason);
  }

  // --- reads --------------------------------------------------------------

  public Optional<ModelVersion> getById(long id) {
    return repo.findById(id);
  }

  public List<ModelVersion> findByName(String modelName) {
    return repo.findByName(modelName);
  }

  /** Every registered version across all models — feeds the Ops dashboard Model Fleet table. */
  public List<ModelVersion> findAll() {
    return repo.findAll();
  }

  /** Distinct model names — feeds the Ops dashboard model-name dropdown. */
  public List<String> findAllModelNames() {
    return repo.findAllModelNames();
  }

  public Optional<ModelVersion> findChampion(String modelName) {
    return repo.findChampion(modelName);
  }

  public Optional<ModelVersion> findChallenger(String modelName) {
    return repo.findChallenger(modelName);
  }

  // --- state transitions --------------------------------------------------

  /**
   * Move {@code id} from its current stage to {@code newStage}, enforcing the {@link Stage}
   * transition matrix.
   *
   * <p>When the target is {@link Stage#CHAMPION}, the prior champion (if any) is archived in the
   * SAME transaction so there's no instant where the model has two champions. The {@code
   * findChampion} invariant in the repository validates this is a single-row-per-stage rule
   * downstream.
   *
   * <p>Idempotent on already-at-target: transitioning to the current stage is a no-op (logged at
   * debug) — the leaf body's "CANDIDATE → CANDIDATE" rejection was a bug surfaced during the
   * service-author pass; the right behaviour is no-op rather than throw, because the promotion
   * controller may issue the same call twice on retry.
   */
  @Transactional
  public ModelVersion transitionStage(long id, Stage newStage) {
    ModelVersion current =
        repo.findById(id)
            .orElseThrow(
                () -> new IllegalArgumentException("registry: no model_version with id " + id));
    if (current.stage() == newStage) {
      log.debug("registry: transitionStage no-op id={} already at {}", id, newStage);
      return current;
    }
    if (!current.stage().canTransitionTo(newStage)) {
      throw new RegistryException.IllegalTransition(current.stage(), newStage);
    }
    if (newStage == Stage.CHAMPION) {
      assertPromotionCriteriaMet(current);
      promoteToChampionAtomically(current);
    } else if (current.stage() == Stage.CHAMPION && newStage == Stage.SHADOW) {
      // INC-1 (decision [150]) controlled rollback. champion_version_id is NOT NULL, so the routing
      // row can't be emptied - remove it so InferenceRouter finds none and the legacy fallback
      // serves. Same @Transactional unit (model_versions + model_routing are both SQLite
      // post-BUG-9).
      // The version stays SHADOW (re-promotable) and, if it's the only version, keeps the rule-5
      // bootstrap exemption - which is how a stuck first champion (the 2026-06-07 incident)
      // recovers:
      // demote, fix the snapshot, re-promote the same version.
      repo.updateStage(id, Stage.SHADOW);
      routingService.removeRouting(current.modelName());
      log.warn(
          "registry: ROLLBACK {}/{} (id={}) CHAMPION->SHADOW, routing row removed",
          current.modelName(),
          current.version(),
          id);
    } else {
      repo.updateStage(id, newStage);
    }
    return repo.findById(id)
        .orElseThrow(
            () -> new IllegalStateException("registry: id=" + id + " vanished after updateStage"));
  }

  /**
   * Rule 5 / decision [72]: every promotion to {@link Stage#CHAMPION} needs a recorded {@code
   * experiment_results.status='passed'} row for {@code (modelName, challengerVersionId)}.
   *
   * <p>Bootstrap exemption (leaf 3a.4 "Known edge cases"): when a model has exactly one
   * ever-registered version (i.e., this is the first version of a brand-new model), the gate is
   * skipped — there's no prior baseline to evaluate against, so an experiment row is impossible by
   * construction. Once a second version exists (even if the first was later archived), the gate
   * fires for every {@code -> CHAMPION} transition.
   */
  private void assertPromotionCriteriaMet(ModelVersion incoming) {
    int totalVersions = repo.findByName(incoming.modelName()).size();
    if (totalVersions <= 1) {
      log.info(
          "registry: bootstrap promotion of {}/{} (id={}) — only version ever registered, gate"
              + " skipped",
          incoming.modelName(),
          incoming.version(),
          incoming.id());
      return;
    }
    if (experimentRepo.findLatestPassing(incoming.modelName(), incoming.id()).isEmpty()) {
      throw new RegistryException.PromotionCriteriaMissing(
          incoming.modelName(), incoming.id(), incoming.version());
    }
  }

  /**
   * Inside the enclosing transaction: archive the prior champion (if any) BEFORE flipping the new
   * row to CHAMPION. Per the leaf's "promotion is atomic" criterion + the
   * findChampion-returns-at-most-one invariant.
   */
  private void promoteToChampionAtomically(ModelVersion incoming) {
    Optional<ModelVersion> priorChampion = repo.findChampion(incoming.modelName());
    if (priorChampion.isPresent() && priorChampion.get().id() != incoming.id()) {
      ModelVersion prior = priorChampion.get();
      repo.updateStage(prior.id(), Stage.ARCHIVED);
      log.info(
          "registry: archived prior champion {}/{} (id={}) before promoting {}/{} (id={})",
          prior.modelName(),
          prior.version(),
          prior.id(),
          incoming.modelName(),
          incoming.version(),
          incoming.id());
    }
    repo.updateStage(incoming.id(), Stage.CHAMPION);
    // 3b.1: ensure model_routing has a row for this model pointing at the new champion. First
    // promotion auto-creates with SHADOW mode + 0 traffic; subsequent promotions just update
    // the champion_version_id. Same enclosing transaction as the stage update.
    routingService.ensureRoutingForChampion(incoming.modelName(), incoming.id());
  }

  // --- helpers ------------------------------------------------------------

  /**
   * Local-filesystem existence + readability check. The artifact paths land here as plain strings
   * (could be relative or absolute); we resolve them with {@link Path#of(String, String...)} and
   * fall through to {@link Files#exists(Path, java.nio.file.LinkOption...)}.
   *
   * <p>Once 3a.5 lands, this becomes an S3 HEAD call instead — the swap is local because every
   * caller goes through this single method.
   */
  private void assertArtifactExists(String pathStr) {
    try {
      Path path = Path.of(pathStr);
      if (!Files.exists(path)) {
        throw new RegistryException.ArtifactMissing(pathStr);
      }
    } catch (RegistryException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new RegistryException.ArtifactMissing(pathStr, e);
    }
  }
}
