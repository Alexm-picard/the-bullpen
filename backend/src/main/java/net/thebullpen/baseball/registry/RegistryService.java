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
 * Orchestrates registry operations on top of {@link RegistryRepository}. It owns the registration-
 * and promotion-time discipline:
 *
 * <ul>
 *   <li>artifact-presence check (the ONNX + metadata + pipeline files exist);
 *   <li>the feature-schema-hash check (rule 7) via {@link FeatureSchemaHasher}, in two layers (B1):
 *       a BOOTSTRAP registration (first version of a model) must hash equal to the family's
 *       canonical {@code /contracts} file ({@link CanonicalContracts}); every later version must
 *       match the bootstrap-pinned hash. Either mismatch is a HARD FAIL at registration;
 *   <li>the lifecycle state machine + the atomic CHAMPION promotion (archive the prior champion in
 *       the same transaction);
 *   <li>the rule-5 promotion-criteria gate ({@link #assertPromotionCriteriaMet}): SHADOW -&gt;
 *       CHAMPION requires a passing {@code experiment_results} row recorded against the CURRENT
 *       champion and fresh within {@link #PROMOTION_EVIDENCE_MAX_AGE} (decision [145], B2);
 *   <li>the rule-9 baseline-presence gate (B4): a primary head cannot reach CHAMPION while its
 *       partner LR baseline has never been registered.
 * </ul>
 *
 * <p>What stays out of here: the snapshot copy to S3 / R2 (handled in the storage layer) and the
 * HTTP exposure of these calls (the registry admin controller).
 *
 * <p>Register is idempotent on {@code (model_name, version)} (decision [65]): a second register
 * call with the same key returns the existing row unchanged. Repeated registrations are the common
 * case when retrains finish - the trainer doesn't track what's already registered.
 */
@Service
public class RegistryService {

  private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

  /**
   * B2: promotion evidence older than this is stale - the monthly scheduled-retrain floor (decision
   * [79]) means a >30d-old comparison spans at least one retrain generation, and the champion /
   * data distribution it measured may no longer be the one being displaced.
   */
  static final java.time.Duration PROMOTION_EVIDENCE_MAX_AGE = java.time.Duration.ofDays(30);

  /**
   * B4 / rule 9: each primary head's partner LR baseline. Promotion of a key to CHAMPION requires
   * at least one non-archived registered version of the value. Hardcoded (vs a baseline_model_name
   * column) deliberately: the pairing is a design-time fact from decision [37]/[46], the map is
   * tiny, and it avoids a migration into the L5-noted duplicate-numbering minefield. Baseline model
   * names themselves are absent from the map, so baselines promote without self-reference.
   */
  private static final Map<String, String> BASELINE_FOR_PRIMARY =
      Map.of(
          "pitch_outcome_pre", "pitch_outcome_lr_baseline",
          "pitch_outcome_post", "pitch_outcome_lr_baseline",
          "battedball_outcome", "lr_baseline_batted_ball",
          "battedball_lgbm_per_park", "lr_baseline_batted_ball");

  private final RegistryRepository repo;
  private final FeatureSchemaHasher hasher;
  private final ExperimentResultsRepository experimentRepo;
  private final SnapshotStorage snapshotStorage;
  private final SnapshotRestoreService restoreService;
  private final CanonicalContracts canonicalContracts;
  // @Lazy breaks the circular dep: RoutingService -> RegistryService (challenger lookup) and
  // RegistryService -> RoutingService (ensureRoutingForChampion on promote). The Spring-injected
  // proxy resolves on first call, so by the time we promote the bean is fully constructed.
  private final net.thebullpen.baseball.inference.routing.RoutingService routingService;

  public RegistryService(
      RegistryRepository repo,
      FeatureSchemaHasher hasher,
      ExperimentResultsRepository experimentRepo,
      SnapshotStorage snapshotStorage,
      SnapshotRestoreService restoreService,
      CanonicalContracts canonicalContracts,
      @org.springframework.context.annotation.Lazy
          net.thebullpen.baseball.inference.routing.RoutingService routingService) {
    this.repo = repo;
    this.hasher = hasher;
    this.experimentRepo = experimentRepo;
    this.snapshotStorage = snapshotStorage;
    this.restoreService = restoreService;
    this.canonicalContracts = canonicalContracts;
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
      // B1: a bootstrap pin must equal the family's CANONICAL /contracts hash - before this
      // check, the first-ever registration pinned whatever the caller submitted and /contracts
      // was never read on the Java side. An unmapped family (no canonical contract yet) keeps
      // the pin-as-submitted behaviour, loudly; registerWithBootstrap stays the deliberate
      // schema-reset path and is exempt by design.
      Optional<String> canonicalHash = canonicalContracts.canonicalHashFor(req.modelName());
      if (canonicalHash.isPresent() && !canonicalHash.get().equals(candidateHash)) {
        throw new RegistryException.FeatureSchemaMismatch(
            req.modelName(), canonicalHash.get(), candidateHash);
      }
      if (canonicalHash.isEmpty()) {
        log.warn(
            "registry: no canonical contract mapped for {} - pinning the submitted hash {};"
                + " add the family to CanonicalContracts when it becomes a real model",
            req.modelName(),
            candidateHash);
      } else {
        log.info(
            "registry: bootstrap feature schema hash for {} = {} (matches canonical contract)",
            req.modelName(),
            candidateHash);
      }
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
    // Assemble the copy-list, place the snapshot, and derive the canonical registered paths. The
    // "what goes in the snapshot" policy (calibrator + external-data sidecars + pipeline-declared
    // Tier-2 lookups) lives in SnapshotRestoreService; this runs inside register()'s transaction
    // (stageForRegistration is not @Transactional, so it joins it exactly as the inline block did).
    SnapshotRestoreService.StagedSnapshot staged = restoreService.stageForRegistration(req);
    String canonicalArtifact = staged.canonicalArtifactPath();
    String canonicalMetadata = staged.canonicalMetadataPath();
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
   * Pull an archived version's snapshot back from S3 to local disk. Delegates to {@link
   * SnapshotRestoreService#restoreVersion(long)}; kept on this service boundary because the runbook
   * and {@code SnapshotStorageIT} reference {@code RegistryService.restoreVersion}.
   */
  @Transactional
  public Path restoreVersion(long versionId) {
    return restoreService.restoreVersion(versionId);
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
      assertBaselineRegistered(current);
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
    // B2: the evidence row must be FRESH and must have been measured against the CURRENT
    // champion - a months-old pass, or a pass against a since-replaced champion, no longer
    // describes the comparison this promotion is making.
    java.time.Instant cutoff = java.time.Instant.now().minus(PROMOTION_EVIDENCE_MAX_AGE);
    Optional<ModelVersion> champion = repo.findChampion(incoming.modelName());
    Optional<net.thebullpen.baseball.registry.dto.ExperimentResult> evidence;
    if (champion.isPresent()) {
      evidence =
          experimentRepo.findLatestPassing(
              incoming.modelName(), incoming.id(), champion.get().id(), cutoff);
    } else {
      // No current champion (e.g. post-[150] rollback on a multi-version model): there is no
      // champion id to bind the evidence to, so accept a fresh pass against ANY champion -
      // blocking here would wedge rollback recovery. Logged loudly because it is the weaker
      // form of the gate.
      evidence =
          experimentRepo.findLatestPassingAnyChampion(incoming.modelName(), incoming.id(), cutoff);
      evidence.ifPresent(
          e ->
              log.warn(
                  "registry: promoting {}/{} (id={}) with NO current champion - accepting fresh"
                      + " evidence row id={} measured against champion_version_id={}",
                  incoming.modelName(),
                  incoming.version(),
                  incoming.id(),
                  e.id(),
                  e.championVersionId()));
    }
    if (evidence.isEmpty()) {
      throw new RegistryException.PromotionCriteriaMissing(
          incoming.modelName(),
          incoming.id(),
          incoming.version(),
          champion
              .map(
                  c ->
                      "no passing experiment_results row against the CURRENT champion (id="
                          + c.id()
                          + ") within the last "
                          + PROMOTION_EVIDENCE_MAX_AGE.toDays()
                          + " days (rule 5 + decision [72]; B2: a stale pass, or a pass against a"
                          + " replaced champion, does not count)")
              .orElse(
                  "no passing experiment_results row within the last "
                      + PROMOTION_EVIDENCE_MAX_AGE.toDays()
                      + " days (rule 5 + decision [72]; no current champion - the any-champion"
                      + " fallback also found nothing)"));
    }
  }

  /**
   * B4 / rule 9: a primary head cannot reach CHAMPION while its partner LR baseline (decision
   * [37]/[46], {@link #BASELINE_FOR_PRIMARY}) has never been registered. Any non-archived stage
   * counts - the baseline only has to EXIST in the registry, not serve. Until now this rule lived
   * only in the Python dry-run gate; nothing in the JVM enforced it.
   */
  private void assertBaselineRegistered(ModelVersion incoming) {
    String baseline = BASELINE_FOR_PRIMARY.get(incoming.modelName());
    if (baseline == null) {
      return; // not a mapped primary (baselines themselves land here)
    }
    boolean present = repo.findByName(baseline).stream().anyMatch(v -> v.stage() != Stage.ARCHIVED);
    if (!present) {
      throw new RegistryException.BaselineMissing(
          incoming.modelName(), incoming.version(), baseline);
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
   * (could be relative or absolute); we resolve them with {@link Path#of(String, String...)}.
   *
   * <p>ENOENT vs EACCES matters here: {@code Files.exists()} returns false BOTH for a genuinely
   * absent file and for one whose parent directory the api process cannot traverse. C-31 attempt
   * #11 burned a full GPU training run on that ambiguity ("does not exist" actually meant "exists,
   * unreadable" - /home/&lt;user&gt; is 750). We stat via {@code Files.readAttributes} so the two
   * failure modes throw distinct, actionable 422 messages; the documented staging default is {@code
   * /opt/bullpen/retrain-artifacts} (trainer-writable, api-readable).
   *
   * <p>Once 3a.5 lands, this becomes an S3 HEAD call instead - the swap is local because every
   * caller goes through this single method.
   */
  private void assertArtifactExists(String pathStr) {
    Path path;
    try {
      path = Path.of(pathStr);
    } catch (RuntimeException e) {
      throw new RegistryException.ArtifactMissing(pathStr, "path is not parseable", e);
    }
    try {
      Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
    } catch (java.nio.file.NoSuchFileException e) {
      throw new RegistryException.ArtifactMissing(pathStr, "does not exist on disk (ENOENT)", e);
    } catch (java.nio.file.AccessDeniedException e) {
      throw new RegistryException.ArtifactMissing(
          pathStr,
          "is not accessible by the api process (EACCES - a parent directory the service user"
              + " cannot traverse, e.g. a 750 home dir; stage artifacts under"
              + " /opt/bullpen/retrain-artifacts)",
          e);
    } catch (java.io.IOException e) {
      throw new RegistryException.ArtifactMissing(
          pathStr, "could not be checked (" + e.getClass().getSimpleName() + ")", e);
    }
    if (!Files.isReadable(path)) {
      throw new RegistryException.ArtifactMissing(
          pathStr, "exists but is not readable by the api process (EACCES on the file itself)");
    }
  }
}
