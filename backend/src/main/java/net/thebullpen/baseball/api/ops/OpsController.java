package net.thebullpen.baseball.api.ops;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.api.dto.LatencyStat;
import net.thebullpen.baseball.api.dto.ModelAccuracyScorecard;
import net.thebullpen.baseball.api.dto.OpsEvent;
import net.thebullpen.baseball.data.OpsEventsRepository;
import net.thebullpen.baseball.data.PredictionLogRepository;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingRepository;
import net.thebullpen.baseball.registry.AccuracyService;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

  private final DriftMetricsRepository driftRepo;
  private final RoutingRepository routingRepo;
  private final RetrainingQueueService retrain;
  private final RegistryService registry;
  private final OpsEventsRepository opsEvents;
  private final PredictionLogRepository predictionLog;
  private final AccuracyService accuracyService;

  public OpsController(
      @Autowired(required = false) DriftMetricsRepository driftRepo,
      RoutingRepository routingRepo,
      RetrainingQueueService retrain,
      RegistryService registry,
      OpsEventsRepository opsEvents,
      @Autowired(required = false) PredictionLogRepository predictionLog,
      AccuracyService accuracyService) {
    this.driftRepo = driftRepo;
    this.routingRepo = routingRepo;
    this.retrain = retrain;
    this.registry = registry;
    this.opsEvents = opsEvents;
    this.predictionLog = predictionLog;
    this.accuracyService = accuracyService;
  }

  /**
   * Leaf 4e.2: recent drift rows for a model. The repo returns rows ordered newest-first; the UI
   * sparklines flip back to chronological for plotting.
   */
  @GetMapping("/drift")
  public List<DriftMetric> drift(@RequestParam("model") String modelName) {
    if (driftRepo == null) {
      return List.of();
    }
    return driftRepo.findAllForModel(modelName);
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
   * completions, restore drills) for the dashboard's Ops Log. {@code limit} defaults to 20, capped
   * at 200 by the repository. Empty list on a fresh DB — the UI then falls back to its showcase
   * fixtures.
   */
  @GetMapping("/events")
  public List<OpsEvent> events(@RequestParam(name = "limit", defaultValue = "20") int limit) {
    return opsEvents.findRecent(limit);
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
    if (predictionLog == null) {
      return List.of();
    }
    return predictionLog.latencyQuantiles(days);
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
