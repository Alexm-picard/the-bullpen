package net.thebullpen.baseball.api.admin;

import jakarta.validation.Valid;
import java.util.List;
import net.thebullpen.baseball.api.admin.dto.ManualRetrainRequest;
import net.thebullpen.baseball.retraining.RetrainingException;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.triggers.ManualTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin HTTP surface for the retraining queue (leaf 3d.2). Gated by HTTP Basic via the same {@code
 * /v1/admin/**} matcher used by 3a.4 / 3b.1 / 3b.4. Three operations:
 *
 * <ul>
 *   <li>{@code POST /v1/admin/retrain} — manual enqueue (delegates to {@link ManualTrigger}'s
 *       1-hour dedup).
 *   <li>{@code GET /v1/admin/retrain} — list queued + recent triggers (optionally filtered by
 *       {@code modelName}).
 *   <li>{@code GET /v1/admin/retrain/{triggerId}} — fetch one trigger by id.
 *   <li>{@code DELETE /v1/admin/retrain/{triggerId}} — cancel.
 * </ul>
 *
 * <p>Exception mapping (exhaustive over sealed {@link RetrainingException}):
 *
 * <ul>
 *   <li>{@link RetrainingException.UnknownTrigger} → 404
 *   <li>{@link RetrainingException.DuplicateTriggerId} → 409
 *   <li>{@link RetrainingException.InvalidStateTransition} → 409
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin/retrain")
@Profile("api")
public class RetrainAdminController {

  private static final Logger log = LoggerFactory.getLogger(RetrainAdminController.class);

  private final ManualTrigger manualTrigger;
  private final RetrainingQueueService queue;
  private final int staleClaimThresholdHours;

  public RetrainAdminController(
      ManualTrigger manualTrigger,
      RetrainingQueueService queue,
      @org.springframework.beans.factory.annotation.Value(
              "${bullpen.retraining.stale-claim-threshold-hours:4}")
          int staleClaimThresholdHours) {
    this.manualTrigger = manualTrigger;
    this.queue = queue;
    this.staleClaimThresholdHours = staleClaimThresholdHours;
  }

  @PostMapping
  public RetrainingTrigger enqueue(
      @Valid @RequestBody ManualRetrainRequest req, @AuthenticationPrincipal UserDetails caller) {
    String by = caller == null ? "unknown-admin" : caller.getUsername();
    RetrainingTrigger row = manualTrigger.enqueue(req.modelName(), req.reason(), by);
    log.info(
        "admin: manual retrain enqueued {} (trigger={}) by {} — reason: {}",
        req.modelName(),
        row.triggerId(),
        by,
        req.reason());
    return row;
  }

  @GetMapping
  public List<RetrainingTrigger> list(
      @RequestParam(name = "modelName", required = false) String modelName) {
    if (modelName == null || modelName.isBlank()) {
      return queue.findAllQueued();
    }
    return queue.findByModel(modelName);
  }

  @GetMapping("/{triggerId}")
  public RetrainingTrigger get(@PathVariable String triggerId) {
    try {
      return queue.getByTriggerId(triggerId);
    } catch (RetrainingException.UnknownTrigger e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @DeleteMapping("/{triggerId}")
  public RetrainingTrigger cancel(@PathVariable String triggerId) {
    try {
      RetrainingTrigger after = queue.cancel(triggerId);
      log.warn("admin: trigger {} cancelled", triggerId);
      return after;
    } catch (RetrainingException.UnknownTrigger e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (RetrainingException.InvalidStateTransition e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  // --- worker-facing endpoints (3d.3) ----------------------------------

  /**
   * Worker-facing: atomically claim the next queued trigger. Returns 200 with the row when one was
   * claimed, 204 when the queue was empty. The atomic SELECT-then-UPDATE in {@link
   * RetrainingQueueService#claimNext} guarantees concurrent workers can't both win the same row.
   */
  @PostMapping("/claim")
  public org.springframework.http.ResponseEntity<RetrainingTrigger> claim() {
    return queue
        .claimNext()
        .map(
            t -> {
              log.info(
                  "admin: trigger {} claimed by worker (model={})", t.triggerId(), t.modelName());
              return org.springframework.http.ResponseEntity.ok(t);
            })
        .orElseGet(() -> org.springframework.http.ResponseEntity.noContent().build());
  }

  /**
   * Worker-facing: report retrain result. {@code succeeded=true} requires {@code
   * producedVersionId}; {@code succeeded=false} requires {@code errorMessage}. Returns the
   * post-state row.
   */
  @PostMapping("/{triggerId}/complete")
  public RetrainingTrigger complete(
      @PathVariable String triggerId,
      @Valid @RequestBody net.thebullpen.baseball.api.admin.dto.CompleteRetrainRequest req) {
    if (req.succeeded() && req.producedVersionId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "complete with succeeded=true requires producedVersionId");
    }
    if (!req.succeeded() && (req.errorMessage() == null || req.errorMessage().isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "complete with succeeded=false requires non-blank errorMessage");
    }
    try {
      if (req.succeeded()) {
        return queue.completeSuccess(triggerId, req.producedVersionId());
      } else {
        return queue.completeFailure(triggerId, req.errorMessage());
      }
    } catch (RetrainingException.UnknownTrigger e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (RetrainingException.InvalidStateTransition e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
    }
  }

  /**
   * Stale-claim reaper (leaf 3d.4). Flips {@code running} rows whose {@code started_at} is older
   * than the configured threshold (default 4h, via {@code
   * bullpen.retraining.stale-claim-threshold-hours}) back to {@code queued} so a crashed worker
   * doesn't strand the trigger. Fired by the systemd timer every 30 min via {@code curl -X POST}.
   * Returns the number of rows reaped.
   */
  @PostMapping("/reap-stale")
  public java.util.Map<String, Integer> reapStale() {
    int reaped = queue.reapStaleClaims(java.time.Duration.ofHours(staleClaimThresholdHours));
    log.info(
        "admin: reap-stale (threshold={}h) flipped {} stuck running row(s) back to queued",
        staleClaimThresholdHours,
        reaped);
    return java.util.Map.of("reaped", reaped);
  }
}
