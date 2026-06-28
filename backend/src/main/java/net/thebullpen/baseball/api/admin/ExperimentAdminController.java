package net.thebullpen.baseball.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import net.thebullpen.baseball.api.admin.dto.AbortExperimentRequest;
import net.thebullpen.baseball.registry.dto.ExperimentResult;
import net.thebullpen.baseball.registry.experiment.ExperimentException;
import net.thebullpen.baseball.registry.experiment.ExperimentService;
import net.thebullpen.baseball.registry.experiment.OfflineGateImportService;
import net.thebullpen.baseball.registry.experiment.dto.ExperimentVerdict;
import net.thebullpen.baseball.registry.experiment.dto.ImportOfflineGateRequest;
import net.thebullpen.baseball.registry.experiment.dto.StartExperimentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin HTTP surface for experiment lifecycle (leaf 3b.4). Gated by HTTP Basic via {@code
 * SecurityConfig}'s {@code /v1/admin/**} matcher.
 *
 * <p>Five operations:
 *
 * <ul>
 *   <li>{@code GET /v1/admin/experiments?modelName=...} — list a model's experiments.
 *   <li>{@code GET /v1/admin/experiments/{id}} — one row.
 *   <li>{@code POST /v1/admin/experiments/start} — declare new experiment.
 *   <li>{@code POST /v1/admin/experiments/{id}/evaluate} — read-only would-verdict peek.
 *   <li>{@code POST /v1/admin/experiments/{id}/complete} — finalize verdict (passed/failed).
 *   <li>{@code POST /v1/admin/experiments/{id}/abort} — abandon without verdict.
 * </ul>
 *
 * <p>Exception mapping (exhaustive over sealed {@link ExperimentException}):
 *
 * <ul>
 *   <li>{@link ExperimentException.UnknownExperiment} → 404
 *   <li>{@link ExperimentException.AlreadyRunning} → 409
 *   <li>{@link ExperimentException.InvalidStateTransition} → 409
 *   <li>{@link ExperimentException.InsufficientSampleSize} → 409
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/experiments")
@Profile("api")
public class ExperimentAdminController {

  private static final Logger log = LoggerFactory.getLogger(ExperimentAdminController.class);

  private final ExperimentService experiments;
  private final OfflineGateImportService offlineImport;

  public ExperimentAdminController(
      ExperimentService experiments, OfflineGateImportService offlineImport) {
    this.experiments = experiments;
    this.offlineImport = offlineImport;
  }

  @GetMapping
  public List<ExperimentResult> list(@RequestParam String modelName) {
    return experiments.findByModel(modelName);
  }

  @GetMapping("/{id}")
  public ExperimentResult get(@PathVariable long id) {
    try {
      return experiments.getById(id);
    } catch (ExperimentException.UnknownExperiment e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @PostMapping("/start")
  public ExperimentResult start(@Valid @RequestBody StartExperimentRequest req) {
    try {
      ExperimentResult inserted = experiments.start(req);
      log.info(
          "admin: experiment {} started for {} (champ={}, chall={}, reason: {})",
          inserted.id(),
          req.modelName(),
          req.championVersionId(),
          req.challengerVersionId(),
          req.reason());
      return inserted;
    } catch (ExperimentException.AlreadyRunning e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  @PostMapping("/{id}/evaluate")
  public ExperimentVerdict evaluate(@PathVariable long id) {
    try {
      return experiments.evaluate(id);
    } catch (ExperimentException.UnknownExperiment e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @PostMapping("/{id}/complete")
  public ExperimentResult complete(@PathVariable long id) {
    try {
      ExperimentResult after = experiments.complete(id);
      log.info("admin: experiment {} completed with status={}", id, after.status());
      return after;
    } catch (ExperimentException.UnknownExperiment e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (ExperimentException.InvalidStateTransition
        | ExperimentException.InsufficientSampleSize e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  @PostMapping("/{id}/abort")
  public ExperimentResult abort(
      @PathVariable long id, @Valid @RequestBody AbortExperimentRequest req) {
    try {
      ExperimentResult after = experiments.abort(id, req.reason());
      log.warn("admin: experiment {} aborted (reason: {})", id, req.reason());
      return after;
    } catch (ExperimentException.UnknownExperiment e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (ExperimentException.InvalidStateTransition e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  /**
   * Import a committed OFFLINE promotion-gate artifact (decision [166] / ADR-0012) as a terminal
   * {@code passed} experiment_results row - the OFFLINE-evidence path the online
   * start/evaluate/complete lifecycle cannot serve (a negative non-inferiority threshold, and a
   * challenger that has no shadow predictions because it is not serving). No promotion is performed
   * (rule 6); this only creates the row the separate, human-gated promote then reads. {@link
   * ExperimentException.OfflineGateInvalid} maps to 422.
   */
  @PostMapping("/import-offline")
  public ExperimentResult importOffline(@Valid @RequestBody ImportOfflineGateRequest req) {
    try {
      ExperimentResult row =
          offlineImport.importGate(
              req.modelName(),
              req.championVersionId(),
              req.challengerVersionId(),
              req.artifactName(),
              req.reason());
      log.info(
          "admin: imported offline-gate evidence as experiment {} for {} (champ={}, chall={},"
              + " artifact={})",
          row.id(),
          req.modelName(),
          req.championVersionId(),
          req.challengerVersionId(),
          req.artifactName());
      return row;
    } catch (ExperimentException.OfflineGateInvalid e) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
    }
  }
}
