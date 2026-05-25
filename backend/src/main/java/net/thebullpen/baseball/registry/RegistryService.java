package net.thebullpen.baseball.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private final RegistryRepository repo;
  private final FeatureSchemaHasher hasher;
  private final ExperimentResultsRepository experimentRepo;
  private final SnapshotStorage snapshotStorage;

  public RegistryService(
      RegistryRepository repo,
      FeatureSchemaHasher hasher,
      ExperimentResultsRepository experimentRepo,
      SnapshotStorage snapshotStorage) {
    this.repo = repo;
    this.hasher = hasher;
    this.experimentRepo = experimentRepo;
    this.snapshotStorage = snapshotStorage;
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
    Path snapshotDir =
        snapshotStorage.placeArtifacts(
            req.modelName(),
            req.version(),
            Map.of(
                SnapshotStorage.ARTIFACT_FILE, Path.of(req.artifactPath()),
                SnapshotStorage.METADATA_FILE, Path.of(req.metadataPath()),
                SnapshotStorage.FEATURE_PIPELINE_FILE, Path.of(req.featurePipelinePath())));
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
