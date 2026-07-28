package net.thebullpen.baseball.inference.routing;

import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.config.CacheConfig;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.registry.dto.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service over {@code model_routing} (3b.1). Read on every inference request via {@link
 * #getRouting(String)} — Caffeine-cached at a configurable TTL (default 30s via {@code
 * bullpen.cache.routing-ttl-seconds}; see {@link CacheConfig}) so the SQLite hit is amortized
 * across thousands of predictions. Writes ({@link #setChallenger}, {@link #setTrafficPct}, {@link
 * #setMode}, {@link #ensureRoutingForChampion}) evict the cache for the affected model so admin
 * slider changes are visible within the TTL window in the worst case + immediate from the writer's
 * perspective.
 *
 * <p>Validation rules (mapped to {@link RoutingException} subclasses for exhaustive HTTP-status
 * pattern matching in {@link net.thebullpen.baseball.api.admin.RoutingAdminController}):
 *
 * <ul>
 *   <li>Champion = challenger ID → {@link RoutingException.ChallengerSameAsChampion}.
 *   <li>Challenger stage != SHADOW → {@link RoutingException.ChallengerNotInShadow}.
 *   <li>{@code traffic_pct} outside [0, 100] → {@link RoutingException.InvalidTrafficPct}.
 *   <li>SHADOW mode with {@code traffic_pct > 0} → {@link RoutingException.ShadowModeWithTraffic}.
 *   <li>Champion not at CHAMPION stage → {@link RoutingException.ChampionNotAtChampionStage}.
 *       Checked before EVERY upsert, including the four paths that merely carry the current
 *       champion forward - perpetuating a bad reference is the same bypass as writing one (task
 *       #94). The V020 triggers enforce the same invariant at the SQLite layer; this check exists
 *       so the failure is a typed, precisely-worded refusal instead of an SQLException.
 * </ul>
 */
@Service
public class RoutingService {

  private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

  private final RoutingRepository repo;
  private final RegistryService registry;

  public RoutingService(RoutingRepository repo, RegistryService registry) {
    this.repo = repo;
    this.registry = registry;
  }

  // --- reads ------------------------------------------------------------

  /**
   * Cached lookup — every inference request hits this and the cache absorbs the load. Misses read
   * from SQLite; the value is held for the routing-cache TTL (default 30s).
   *
   * <p>Throws {@link RoutingException.UnknownModel} when no row exists for {@code modelName}.
   * Callers on the inference path treat this as "model isn't promoted yet" rather than a
   * programming error.
   */
  @Cacheable(value = CacheConfig.ROUTING_CACHE, key = "#modelName")
  public RoutingConfig getRouting(String modelName) {
    return repo.findByModelName(modelName)
        .orElseThrow(() -> new RoutingException.UnknownModel(modelName));
  }

  /** Non-throwing read for callers that want to distinguish missing-row from a real error. */
  @Cacheable(value = CacheConfig.ROUTING_CACHE, key = "'opt:' + #modelName")
  public Optional<RoutingConfig> findRouting(String modelName) {
    return repo.findByModelName(modelName);
  }

  public List<RoutingConfig> listAll() {
    return repo.findAll();
  }

  // --- writes -----------------------------------------------------------

  /**
   * Set the challenger version for {@code modelName}. The candidate must currently be at SHADOW
   * stage (rule 6 / leaf body); cannot match the champion. After this call the routing config
   * carries the new challenger; {@code traffic_pct} is set to 0 (use {@link #setTrafficPct} to
   * route real traffic — explicit second step prevents an accidental swap-and-cutover in one
   * click).
   *
   * <p>{@code mode} is preserved from the existing routing row (or defaults to SHADOW if no row
   * exists yet, though that path is unusual since {@link #ensureRoutingForChampion} would have
   * created the row at first CHAMPION promotion).
   */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public RoutingConfig setChallenger(String modelName, long challengerVersionId) {
    RoutingConfig current =
        repo.findByModelName(modelName)
            .orElseThrow(() -> new RoutingException.UnknownModel(modelName));
    if (current.championVersionId() == challengerVersionId) {
      throw new RoutingException.ChallengerSameAsChampion(challengerVersionId);
    }
    assertChampionStage(modelName, current.championVersionId());
    ModelVersion candidate =
        registry
            .getById(challengerVersionId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "routing: no model_version with id " + challengerVersionId));
    if (candidate.stage() != Stage.SHADOW) {
      throw new RoutingException.ChallengerNotInShadow(
          challengerVersionId, candidate.stage().name());
    }
    // Rule 9, challenger side: a SHADOW version of a DIFFERENT model must not enter this model's
    // challenger slot - its shadow predictions would be logged (and eventually evaluated) under
    // the wrong head. Caller error, not state corruption, so IllegalArgumentException -> 400.
    if (!candidate.modelName().equals(modelName)) {
      throw new IllegalArgumentException(
          "routing: version "
              + challengerVersionId
              + " belongs to model '"
              + candidate.modelName()
              + "', not '"
              + modelName
              + "' - the challenger must be a version of the routed model (rule 9)");
    }
    RoutingConfig updated =
        repo.upsert(
            modelName, current.championVersionId(), challengerVersionId, 0.0, current.mode());
    log.info(
        "routing: {} challenger set to version {} (mode={}, traffic_pct=0)",
        modelName,
        challengerVersionId,
        current.mode());
    return updated;
  }

  /** Clear the challenger slot (champion-only routing). */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public RoutingConfig clearChallenger(String modelName) {
    RoutingConfig current =
        repo.findByModelName(modelName)
            .orElseThrow(() -> new RoutingException.UnknownModel(modelName));
    assertChampionStage(modelName, current.championVersionId());
    RoutingConfig updated =
        repo.upsert(modelName, current.championVersionId(), null, 0.0, RoutingMode.SHADOW);
    log.info("routing: {} challenger cleared, mode set to SHADOW", modelName);
    return updated;
  }

  /**
   * Move the traffic-split slider. Validates [0, 100] and forbids non-zero with SHADOW mode. Caller
   * should set the mode to {@link RoutingMode#AB} first if rolling out real traffic.
   */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public RoutingConfig setTrafficPct(String modelName, double pct) {
    if (pct < 0 || pct > 100) {
      throw new RoutingException.InvalidTrafficPct(pct);
    }
    RoutingConfig current =
        repo.findByModelName(modelName)
            .orElseThrow(() -> new RoutingException.UnknownModel(modelName));
    assertChampionStage(modelName, current.championVersionId());
    if (current.mode() == RoutingMode.SHADOW && pct > 0) {
      throw new RoutingException.ShadowModeWithTraffic(pct);
    }
    RoutingConfig updated =
        repo.upsert(
            modelName,
            current.championVersionId(),
            current.challengerVersionId(),
            pct,
            current.mode());
    log.info("routing: {} traffic_pct set to {}", modelName, pct);
    return updated;
  }

  /** Flip the mode (SHADOW ↔ AB). Going to SHADOW also resets {@code traffic_pct} to 0. */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public RoutingConfig setMode(String modelName, RoutingMode mode) {
    RoutingConfig current =
        repo.findByModelName(modelName)
            .orElseThrow(() -> new RoutingException.UnknownModel(modelName));
    assertChampionStage(modelName, current.championVersionId());
    double pct = mode == RoutingMode.SHADOW ? 0.0 : current.challengerTrafficPct();
    RoutingConfig updated =
        repo.upsert(
            modelName, current.championVersionId(), current.challengerVersionId(), pct, mode);
    log.info("routing: {} mode set to {} (traffic_pct={})", modelName, mode, pct);
    return updated;
  }

  /**
   * Remove the routing row for {@code modelName} entirely. The symmetric counterpart of {@link
   * #ensureRoutingForChampion}: when a CHAMPION is rolled back to SHADOW (INC-1 / decision [150]),
   * {@code champion_version_id} is non-null so the row can't be "emptied" - it must be deleted.
   * With no routing row, {@link net.thebullpen.baseball.inference.InferenceRouter} finds nothing
   * and the legacy fallback serves (the toy for batted-ball). Called inside {@link
   * net.thebullpen.baseball.registry.RegistryService#transitionStage}'s rollback branch, in the
   * same transaction as the stage flip.
   */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public void removeRouting(String modelName, String reason) {
    int deleted = repo.deleteByModelName(modelName);
    // WARN only when a row actually went away - callers (bootstrap in particular, which is the
    // path every FIRST registration takes) invoke this unconditionally, and a WARN claiming the
    // router "fell back" when no row existed would be a false operator signal.
    if (deleted > 0) {
      log.warn(
          "routing: removed routing row for {} ({} row(s)) - {}; router falls back to legacy",
          modelName,
          deleted,
          reason);
    } else {
      log.debug("routing: removeRouting no-op for {} (no row existed) - {}", modelName, reason);
    }
  }

  /**
   * Idempotent: if no routing row exists for {@code modelName}, create one with the given champion
   * and default SHADOW mode + 0 traffic. Otherwise update only the champion (keeps the existing
   * challenger / mode / traffic — the registry promotion is the source of truth for "who's champion
   * now"). Called from {@link net.thebullpen.baseball.registry.RegistryService#transitionStage} on
   * every {@code -> CHAMPION} transition — closes the leaf "Known edge case" for first-ever models.
   */
  @Transactional
  @CacheEvict(value = CacheConfig.ROUTING_CACHE, allEntries = true)
  public RoutingConfig ensureRoutingForChampion(String modelName, long championVersionId) {
    // The registry promote flips the version to CHAMPION before calling here, in the same
    // transaction, so this reads the just-updated stage. Anything else reaching this method with
    // a non-champion id is exactly the bypass the check exists for.
    assertChampionStage(modelName, championVersionId);
    Optional<RoutingConfig> existing = repo.findByModelName(modelName);
    if (existing.isEmpty()) {
      RoutingConfig created =
          repo.upsert(modelName, championVersionId, null, 0.0, RoutingMode.SHADOW);
      log.info(
          "routing: auto-created {} routing row for new champion {} (mode=SHADOW)",
          modelName,
          championVersionId);
      return created;
    }
    RoutingConfig current = existing.get();
    // If the new champion is also the existing challenger, clear the challenger slot — the
    // old champion is archived (registry transaction handled that already) and the challenger
    // promotion semantics mean its slot is empty.
    Long challenger =
        current.challengerVersionId() != null && current.challengerVersionId() == championVersionId
            ? null
            : current.challengerVersionId();
    double pct = challenger == null ? 0.0 : current.challengerTrafficPct();
    RoutingMode mode = challenger == null ? RoutingMode.SHADOW : current.mode();
    RoutingConfig updated = repo.upsert(modelName, championVersionId, challenger, pct, mode);
    log.info(
        "routing: {} champion updated to {} (challenger={}, mode={})",
        modelName,
        championVersionId,
        challenger,
        mode);
    return updated;
  }

  /**
   * The V011-bypass guard (task #94): every write that stores a {@code champion_version_id} - or
   * carries the current one forward - first proves the referenced version is at {@link
   * Stage#CHAMPION}. A routing row is the registry's serving switch; a row referencing any other
   * stage means a version is serving (or about to serve) without having passed the rule-5/rule-6
   * promotion gates. Refusing on the carry-forward paths too is deliberate: an admin write that
   * perpetuates a bad reference launders it.
   */
  private void assertChampionStage(String modelName, long championVersionId) {
    // Detecting corruption must be loud SERVER-SIDE, not only in the HTTP response: the
    // ResponseStatusException path in ApiErrorAdvice does not log, and an operator who never sees
    // the 500 body would otherwise have no trace that the integrity check fired mid-lifetime.
    Optional<ModelVersion> found = registry.getById(championVersionId);
    if (found.isEmpty() || found.get().stage() != Stage.CHAMPION) {
      String badStage = found.map(v -> v.stage().name()).orElse("<no model_versions row>");
      log.error(
          "routing integrity: champion_version_id {} is at stage {}, not CHAMPION - refusing the"
              + " write (task #94)",
          championVersionId,
          badStage);
      throw new RoutingException.ChampionNotAtChampionStage(championVersionId, badStage);
    }
    // Rule 9: a champion of the WRONG MODEL is just as much a bypass as a non-champion - the
    // router would serve model B's weights under model A's name. Stage-only checking would wave
    // this through (the reviewer proved it against the real migration).
    if (!found.get().modelName().equals(modelName)) {
      log.error(
          "routing integrity: champion_version_id {} belongs to model '{}', not '{}' - refusing"
              + " the write (task #94 / rule 9)",
          championVersionId,
          found.get().modelName(),
          modelName);
      throw new RoutingException.ChampionNotAtChampionStage(
          championVersionId, modelName, found.get().modelName());
    }
  }
}
