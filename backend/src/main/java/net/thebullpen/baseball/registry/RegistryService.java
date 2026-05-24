package net.thebullpen.baseball.registry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.RegisterRequest;
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

  public RegistryService(RegistryRepository repo) {
    this.repo = repo;
  }

  // --- register -----------------------------------------------------------

  /**
   * Insert a new {@link Stage#CANDIDATE} row, or return the existing row if {@code (modelName,
   * version)} is already registered (idempotent).
   *
   * <p>Verifies that both the artifact path and metadata path point at files that exist on disk.
   * Throws {@link RegistryException.ArtifactMissing} otherwise.
   *
   * <p>Local-filesystem-only for v1; 3a.5 swaps the validator for an S3-compatible existence check
   * once snapshot storage lands.
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
    ModelVersion inserted =
        repo.insert(
            req.modelName(),
            req.version(),
            req.artifactPath(),
            req.metadataPath(),
            req.trainingDataHash(),
            req.trainingDataWindow(),
            req.featureSchemaHash(),
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
    return inserted;
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
      promoteToChampionAtomically(current);
    } else {
      repo.updateStage(id, newStage);
    }
    return repo.findById(id)
        .orElseThrow(
            () -> new IllegalStateException("registry: id=" + id + " vanished after updateStage"));
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
