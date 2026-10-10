package net.thebullpen.baseball.api.ops;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.api.dto.ModelAccuracyScorecard;
import net.thebullpen.baseball.api.dto.OpsEventsPage;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.Calibration;
import net.thebullpen.baseball.api.dto.RollingAccuracyResponse.ModelRollingAccuracy;
import net.thebullpen.baseball.data.LiveReliabilityRepository;
import net.thebullpen.baseball.data.OpsEventsRepository;
import net.thebullpen.baseball.data.PredictionLogRepository;
import net.thebullpen.baseball.data.RollingAccuracyRepository;
import net.thebullpen.baseball.domain.LatencyStat;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.TaggedDriftMetric;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingRepository;
import net.thebullpen.baseball.registry.AccuracyService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public Ops dashboard read API (leaves 4e.2 + 4e.3 + 4e.4). Single controller because each
 * endpoint is a thin pass-through to an existing service, all share the same auth boundary
 * (decision [29]: ops reads are public, no Basic auth), and a single class is easier to grep for
 * "what does /v1/ops/* serve" than five tiny controllers.
 *
 * <p>Three endpoints:
 *
 * <ul>
 *   <li>{@code GET /v1/ops/drift?model=…} — recent drift metric rows for one model. Empty when
 *       ClickHouse isn't wired (no DriftMetricsRepository bean → empty list).
 *   <li>{@code GET /v1/ops/routing} — list of all A/B routing rows.
 *   <li>{@code GET /v1/ops/retrain} — queued + running retraining triggers, optionally filtered by
 *       model.
 * </ul>
 *
 * <p>The drift repo is optional ({@code @Autowired(required=false)}) so the controller still
 * materialises when CH isn't around — the drift section then surfaces an empty list and the UI
 * shows its "no drift data yet" placeholder.
 */
@Tag(
    name = "Ops dashboard",
    description =
        "Public read API behind the Ops dashboard: drift metrics, A/B routing, retrain queue,"
            + " recent ops events, latency, and calibration + accuracy scorecards. No auth"
            + " (decision [29]); returns empty rather than 404 for speculative polling.")
@RestController
@RequestMapping("/v1/ops")
@Profile("api")
public class OpsController {

  private static final Logger log = LoggerFactory.getLogger(OpsController.class);

  private static final int OPS_EVENTS_MIN_SIZE = 1;
  private static final int OPS_EVENTS_MAX_SIZE = 200;
  private static final int LATENCY_MIN_DAYS = 1;
  // 365 days caps the prediction_log window scan: an uncapped `days` (the live days=2000000000
  // case)
  // is a full-table-scan DoS on an anonymous public read. Inline check (not class @Validated) so it
  // enforces under standaloneSetup, matching PlayerController's documented pattern.
  private static final int LATENCY_MAX_DAYS = 365;
  private static final int ROLLING_ACCURACY_MIN_DAYS = 1;
  // 30 caps the truth-join scan; beyond the 14-day pitches_live TTL there is no joinable truth
  // anyway, so a larger window would widen the prediction_log scan for zero additional signal.
  private static final int ROLLING_ACCURACY_MAX_DAYS = 30;
  // The calibration window reads the tiny live_reliability_daily rollup (V035), not the truth join,
  // so 30 is the product window rather than a scan fence; it stays capped at the same 30.
  private static final int CALIBRATION_MIN_DAYS = 1;
  private static final int CALIBRATION_MAX_DAYS = 30;

  private final DriftMetricsRepository driftRepo;
  private final RoutingRepository routingRepo;
  private final RetrainingQueueService retrain;
  private final RegistryService registry;
  private final OpsEventsRepository opsEvents;
  private final PredictionLogRepository predictionLog;
  private final AccuracyService accuracyService;
  private final RollingAccuracyRepository rollingAccuracy;
  private final LiveReliabilityRepository liveReliability;

  public OpsController(
      @Autowired(required = false) DriftMetricsRepository driftRepo,
      RoutingRepository routingRepo,
      RetrainingQueueService retrain,
      RegistryService registry,
      OpsEventsRepository opsEvents,
      @Autowired(required = false) PredictionLogRepository predictionLog,
      AccuracyService accuracyService,
      @Autowired(required = false) RollingAccuracyRepository rollingAccuracy,
      @Autowired(required = false) LiveReliabilityRepository liveReliability) {
    this.driftRepo = driftRepo;
    this.routingRepo = routingRepo;
    this.retrain = retrain;
    this.registry = registry;
    this.opsEvents = opsEvents;
    this.predictionLog = predictionLog;
    this.accuracyService = accuracyService;
    this.rollingAccuracy = rollingAccuracy;
    this.liveReliability = liveReliability;
  }

  /**
   * Leaf 4e.2: recent drift rows for a model. The repo returns rows ordered newest-first; the UI
   * sparklines flip back to chronological for plotting. E-4: rows carry the V027 {@code tag} (empty
   * = organic) so the dashboard can label [175] induced-drill evidence rows honestly instead of
   * rendering a synthetic PSI spike as organic drift - additive field, same row shape.
   */
  @GetMapping("/drift")
  public List<TaggedDriftMetric> drift(@RequestParam("model") String modelName) {
    if (driftRepo == null) {
      return List.of();
    }
    return driftRepo.findAllForModelTagged(modelName);
  }

  /** Leaf 4e.3: every A/B routing row, including current traffic split + mode. */
  @GetMapping("/routing")
  public List<RoutingConfig> routing() {
    return routingRepo.findAll();
  }

  /**
   * Leaf 4e.4: queued + recently-finished retrain triggers. {@code modelName} filter narrows to one
   * model when present; absent returns all queued rows across every model. The deliberately-thin
   * payload — same DTO the admin endpoint returns — lets the UI surface the same status /
   * timestamps without leaking write capability.
   */
  @GetMapping("/retrain")
  public List<RetrainingTrigger> retrain(
      @RequestParam(name = "model", required = false) String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return retrain.findAllQueued();
    }
    return retrain.findByModel(modelName);
  }

  /**
   * B3: most-recent ops events (registrations, promotions, deploys, drift alerts, retrain
   * completions, restore drills) for the dashboard's Ops Log, newest first. Offset-paginated
   * ({@code page} 0-based, {@code size} 1..200, defaulting to the newest 20) so a caller can page
   * past the newest {@code size} events instead of being stuck at a hard cap; {@code hasNext} comes
   * from a size+1 over-fetch, mirroring {@code GET /v1/games/{id}/post-predictions}. An empty page
   * on a fresh DB is a legitimate "no events yet" state - the UI shows its own empty path, NOT the
   * showcase fixtures (those appear only when the query fails to resolve).
   */
  @GetMapping("/events")
  public OpsEventsPage events(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    if (page < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
    }
    if (size < OPS_EVENTS_MIN_SIZE || size > OPS_EVENTS_MAX_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "size must be between " + OPS_EVENTS_MIN_SIZE + " and " + OPS_EVENTS_MAX_SIZE);
    }
    var events = opsEvents.findRecentPage(page, size);
    return new OpsEventsPage(events.rows(), page, size, events.hasNext());
  }

  /**
   * Per-model serving-latency percentiles (p50 / p95 / p99, ms) over the last {@code days} days,
   * read from {@code prediction_log.latency_ms}. Backs the Ops fleet p99 column + Latency Detail
   * table — the first real latency numbers on the dashboard. Empty list when ClickHouse isn't wired
   * ({@code predictionLog == null}) or no predictions fall in the window; the UI then shows its
   * no-data state.
   */
  @GetMapping("/latency")
  public List<LatencyStat> latency(@RequestParam(name = "days", defaultValue = "7") int days) {
    if (days < LATENCY_MIN_DAYS || days > LATENCY_MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "days must be between " + LATENCY_MIN_DAYS + " and " + LATENCY_MAX_DAYS);
    }
    if (predictionLog == null) {
      return List.of();
    }
    return predictionLog.latencyQuantiles(days);
  }

  /**
   * The /accuracy Live Scorecard: rolling realized top-1 accuracy for all four families over the
   * trailing ET-day window (default 7, max 30). The two families without live truth SAY WHY ({@code
   * status: "no_live_truth"} + reason) rather than being omitted - the endpoint's whole point is
   * that absence is stated, never implied. Champion-served rows only; dedup + join discipline lives
   * in {@link RollingAccuracyRepository}.
   *
   * <p>Honesty constraints carried into the payload: every live figure travels with its n and the
   * window; pitch_type_pre is a calibrated PRIOR promoted on calibration ([183]) - its top-1 is
   * supplementary and the frontend captions it as such and floors rendering at n >= 500;
   * battedball's [163] reality-gap framing is restated in its reason string.
   *
   * <p>Each pitch head also carries a {@code calibration} object: live top-label reliability bins
   * over a SEPARATE {@code calibrationDays} window (default 30, max 30) read from the {@code
   * live_reliability_daily} rollup through yesterday ET. It is independent of the top-1 {@code
   * status} (present even when the 7-day window is {@code no_live_truth}); when the calendar window
   * is empty it falls back to the most recent game days with data and says so via {@code
   * windowKind}. Null for batted-ball and when the store is not configured.
   */
  @GetMapping("/rolling-accuracy")
  public RollingAccuracyResponse rollingAccuracy(
      @Parameter(
              description = "Top-1 window in ET days (1..30).",
              schema =
                  @Schema(
                      type = "integer",
                      format = "int32",
                      minimum = "1",
                      maximum = "30",
                      defaultValue = "7"))
          @RequestParam(name = "days", defaultValue = "7")
          int days,
      @Parameter(
              description = "Live calibration window in ET days through yesterday (1..30).",
              schema =
                  @Schema(
                      type = "integer",
                      format = "int32",
                      minimum = "1",
                      maximum = "30",
                      defaultValue = "30"))
          @RequestParam(name = "calibrationDays", defaultValue = "30")
          int calibrationDays) {
    if (days < ROLLING_ACCURACY_MIN_DAYS || days > ROLLING_ACCURACY_MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "days must be between "
              + ROLLING_ACCURACY_MIN_DAYS
              + " and "
              + ROLLING_ACCURACY_MAX_DAYS);
    }
    if (calibrationDays < CALIBRATION_MIN_DAYS || calibrationDays > CALIBRATION_MAX_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "calibrationDays must be between "
              + CALIBRATION_MIN_DAYS
              + " and "
              + CALIBRATION_MAX_DAYS);
    }
    List<ModelRollingAccuracy> models =
        List.of(
            pitchOutcomeEntry("pitch_outcome_pre", days)
                .withCalibration(calibration("pitch_outcome_pre", calibrationDays)),
            pitchOutcomeEntry("pitch_outcome_post", days)
                .withCalibration(calibration("pitch_outcome_post", calibrationDays)),
            ModelRollingAccuracy.noTruth(
                "battedball_outcome",
                "prediction_log rows for this family carry no live pitch keys - the served"
                    + " surface is the park heatmap, a calibrated physics estimate (decision"
                    + " [163]) - so realized live accuracy is structurally unavailable, not"
                    + " merely pending"),
            pitchTypeEntry(days)
                .withCalibration(
                    calibration(RollingAccuracyRepository.PITCH_TYPE_MODEL, calibrationDays)));
    return new RollingAccuracyResponse(days, calibrationDays, Instant.now(), models);
  }

  /**
   * One pitch head's live calibration, or null when the store is absent or the rollup read fails. A
   * failed read degrades to null (the frontend's "calibration unavailable" state) rather than
   * failing the whole scorecard: the top-1 figures do not depend on the rollup.
   */
  private Calibration calibration(String modelName, int calibrationDays) {
    if (liveReliability == null) {
      return null;
    }
    try {
      return Calibration.from(liveReliability.window(modelName, calibrationDays), calibrationDays);
    } catch (RuntimeException e) {
      // Broad on purpose: any rollup fault (JDBC-translated or a mapper surprise) degrades the
      // calibration to null; it must never take the top-1 scorecard down with it.
      log.warn("rolling-accuracy: calibration read failed for {}; serving null", modelName, e);
      return null;
    }
  }

  private ModelRollingAccuracy pitchOutcomeEntry(String modelName, int days) {
    if (rollingAccuracy == null) {
      return ModelRollingAccuracy.noTruth(
          modelName, "analytical store not configured in this environment");
    }
    return ModelRollingAccuracy.live(
        modelName,
        rollingAccuracy.pitchOutcomeDaily(modelName, days),
        "no truth-joined champion predictions in the window (live volume and the 14-day"
            + " pitches_live TTL bound what is joinable)",
        null);
  }

  private ModelRollingAccuracy pitchTypeEntry(int days) {
    if (rollingAccuracy == null) {
      return ModelRollingAccuracy.noTruth(
          "pitch_type_pre", "analytical store not configured in this environment");
    }
    return ModelRollingAccuracy.live(
        "pitch_type_pre",
        rollingAccuracy.pitchTypeDaily(days),
        "scored from the live-ingest per-pitch loop (worker profile); truth-joinable volume"
            + " accumulates as games are polled",
        // The endpoint is public and self-describing (its own honesty contract): a direct API
        // caller sees the [183] framing without needing the frontend's caption.
        "calibrated prior ([183]): top-1 is supplementary, never the claim; the site renders no"
            + " % below n = 500");
  }

  /**
   * Leaf 4e.5 placeholder: aggregated per-model calibration summary. Reads the eval_metrics JSON of
   * each {@code model_name}'s latest registered version and surfaces it as the canonical
   * calibration source for the dashboard. The detailed reliability diagram (Phase 4b.3 component)
   * is reused on the Ops page; this endpoint just hands the diagram bins for now.
   */
  @GetMapping("/calibration-summary")
  public Map<String, String> calibrationSummary() {
    // Map model_name → latest version's eval_metrics JSON. UI parses what it knows.
    return registry.findAllModelNames().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                name -> name,
                name ->
                    registry.findByName(name).stream()
                        .findFirst()
                        .map(net.thebullpen.baseball.registry.dto.ModelVersion::evalMetrics)
                        .orElse(""),
                (a, b) -> a,
                java.util.LinkedHashMap::new));
  }

  /**
   * Phase 3 model-accuracy scorecard: per-model OFFLINE held-out eval (Brier / ECE / vs-baseline /
   * sample size / gate verdict) from the committed promotion-evidence. Every row is labeled offline
   * - NOT live production accuracy - and carries the gate status + calibration note so a failed
   * model is never implied to be serving. Empty list when no evidence is bundled.
   */
  @GetMapping("/accuracy")
  public List<ModelAccuracyScorecard> accuracy() {
    return accuracyService.scorecards();
  }

  /**
   * Phase 3 batted-ball backfill: the offline real-vs-predicted scoring of the battedball_outcome
   * champion over historical in-play balls, served verbatim. 204 No Content until the box hand-off
   * commits the artifact (it is box/R2-only, ADR-0006), which the UI renders as its empty state.
   */
  @GetMapping("/backfill-accuracy")
  public ResponseEntity<JsonNode> backfillAccuracy() {
    return accuracyService
        .backfill()
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
