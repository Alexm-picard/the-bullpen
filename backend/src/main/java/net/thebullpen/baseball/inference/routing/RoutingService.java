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
 * #getRouting(String)} — Caffeine-cached at 30s TTL (see {@link CacheConfig}) so the SQLite hit is
 * amortized across thousands of predictions. Writes ({@link #setChallenger}, {@link
 * #setTrafficPct}, {@link #setMode}, {@link #ensureRoutingForChampion}) evict the cache for the
 * affected model so admin slider changes are visible within the TTL window in the worst case +
 * immediate from the writer's perspective.
 *
 * <p>Validation rules (mapped to {@link RoutingException} subclasses for exhaustive HTTP-status
 * pattern matching in {@link net.thebullpen.baseball.api.admin.RoutingAdminController}):
 *
 * <ul>
 *   <li>Champion = challenger ID → {@link RoutingException.ChallengerSameAsChampion}.
 *   <li>Challenger stage != SHADOW → {@link RoutingException.ChallengerNotInShadow}.
 *   <li>{@code traffic_pct} outside [0, 100] → {@link RoutingException.InvalidTrafficPct}.
 *   <li>SHADOW mode with {@code traffic_pct > 0} → {@link RoutingException.ShadowModeWithTraffic}.
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
   * from SQLite; the value is held for 30s.
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
  public void removeRouting(String modelName) {
    int deleted = repo.deleteByModelName(modelName);
    log.warn(
        "routing: removed routing row for {} ({} row(s)) - champion rolled back, falling back to legacy",
        modelName,
        deleted);
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
}
