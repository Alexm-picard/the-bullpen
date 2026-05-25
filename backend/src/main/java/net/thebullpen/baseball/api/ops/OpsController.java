package net.thebullpen.baseball.api.ops;

import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingRepository;
import net.thebullpen.baseball.registry.RegistryService;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
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
@RestController
@RequestMapping("/v1/ops")
@Profile("api")
public class OpsController {

  private final DriftMetricsRepository driftRepo;
  private final RoutingRepository routingRepo;
  private final RetrainingQueueService retrain;
  private final RegistryService registry;

  public OpsController(
      @Autowired(required = false) DriftMetricsRepository driftRepo,
      RoutingRepository routingRepo,
      RetrainingQueueService retrain,
      RegistryService registry) {
    this.driftRepo = driftRepo;
    this.routingRepo = routingRepo;
    this.retrain = retrain;
    this.registry = registry;
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
}
