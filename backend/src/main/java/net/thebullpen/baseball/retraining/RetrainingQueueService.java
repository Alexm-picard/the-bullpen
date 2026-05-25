package net.thebullpen.baseball.retraining;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.thebullpen.baseball.retraining.dto.QueueStatus;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle service for {@code retraining_queue} (leaf 3d.1). Decision [44] / rule 6 stays baked
 * in: success here produces a {@code candidate}-stage model_version; promotion is a separate human
 * action via 3a.4's admin endpoint. This service NEVER touches stage.
 *
 * <p>State machine (mirrors {@link QueueStatus}):
 *
 * <pre>
 *   enqueue  → queued
 *   claim    → queued → running   (atomic per {@link RetrainingQueueRepository#claimNextQueued})
 *   complete → running → succeeded|failed
 *   cancel   → queued|running → cancelled
 * </pre>
 *
 * <p>Idempotency: {@link #enqueue} accepts a caller-provided {@code triggerId} (or generates one if
 * null) and surfaces {@link RetrainingException.DuplicateTriggerId} when the UNIQUE constraint
 * fires — callers can no-op safely. The 3d.2 trigger producers re-derive a stable trigger_id per
 * (modelName, source-event) so a repeated drift evaluation doesn't double-enqueue.
 */
@Service
public class RetrainingQueueService {

  private static final Logger log = LoggerFactory.getLogger(RetrainingQueueService.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final RetrainingQueueRepository repo;

  public RetrainingQueueService(RetrainingQueueRepository repo) {
    this.repo = repo;
  }

  // --- enqueue ----------------------------------------------------------

  /**
   * Enqueue a retrain for {@code modelName}. {@code triggerId} is the caller-controlled idempo key;
   * pass {@code null} for an auto-generated UUID. {@code metadata} is arbitrary JSON stored on the
   * row (e.g. drift-event id, scheduled-cron timestamp, manual reason). Throws {@link
   * RetrainingException.DuplicateTriggerId} if the trigger was already enqueued — callers
   * idempotently no-op.
   */
  @Transactional
  public RetrainingTrigger enqueue(
      String modelName, TriggerType type, String triggerId, Map<String, ?> metadata) {
    String finalTriggerId = triggerId == null ? UUID.randomUUID().toString() : triggerId;
    String metadataJson = writeJson(metadata == null ? Map.of() : metadata);
    RetrainingTrigger inserted = repo.insertQueued(finalTriggerId, modelName, type, metadataJson);
    log.info(
        "retraining: enqueued {} ({}) for {} — trigger_id={}",
        type,
        inserted.id(),
        modelName,
        finalTriggerId);
    return inserted;
  }

  // --- claim (worker) ---------------------------------------------------

  /**
   * Atomic claim of the next queued trigger. Empty if nothing queued. Workers call this in a
   * polling loop; the SQLite-level UPDATE-with-WHERE ensures only one caller wins a given row.
   */
  @Transactional
  public Optional<RetrainingTrigger> claimNext() {
    Optional<RetrainingTrigger> claimed = repo.claimNextQueued();
    claimed.ifPresent(
        t -> log.info("retraining: claimed trigger {} ({})", t.triggerId(), t.modelName()));
    return claimed;
  }

  // --- complete + cancel ------------------------------------------------

  /**
   * Mark a claimed trigger as succeeded. {@code producedVersionId} is the new {@code
   * model_versions.id} the trainer registered (always at stage=candidate per rule 6).
   */
  @Transactional
  public RetrainingTrigger completeSuccess(String triggerId, long producedVersionId) {
    RetrainingTrigger before = loadOrThrow(triggerId);
    if (before.status() != QueueStatus.RUNNING) {
      throw new RetrainingException.InvalidStateTransition(
          triggerId, before.status().name(), "complete");
    }
    int rows = repo.markComplete(triggerId, true, producedVersionId, null);
    if (rows == 0) {
      // Race: someone else terminal'd this row between our read + write.
      throw new RetrainingException.InvalidStateTransition(
          triggerId, "running", "complete (raced)");
    }
    log.info(
        "retraining: trigger {} succeeded; produced model_version {}",
        triggerId,
        producedVersionId);
    return loadOrThrow(triggerId);
  }

  /** Mark a claimed trigger as failed with the given error message. */
  @Transactional
  public RetrainingTrigger completeFailure(String triggerId, String errorMessage) {
    RetrainingTrigger before = loadOrThrow(triggerId);
    if (before.status() != QueueStatus.RUNNING) {
      throw new RetrainingException.InvalidStateTransition(
          triggerId, before.status().name(), "complete");
    }
    int rows = repo.markComplete(triggerId, false, null, errorMessage);
    if (rows == 0) {
      throw new RetrainingException.InvalidStateTransition(
          triggerId, "running", "complete (raced)");
    }
    log.warn("retraining: trigger {} failed; error: {}", triggerId, errorMessage);
    return loadOrThrow(triggerId);
  }

  /**
   * Cancel a trigger. Legal from {@code queued} or {@code running}; terminal rows throw {@link
   * RetrainingException.InvalidStateTransition}. The Python job is expected to poll status and
   * abort if it sees {@code cancelled} mid-flight (leaf "Known edge cases").
   */
  @Transactional
  public RetrainingTrigger cancel(String triggerId) {
    RetrainingTrigger before = loadOrThrow(triggerId);
    if (before.status().isTerminal()) {
      throw new RetrainingException.InvalidStateTransition(
          triggerId, before.status().name(), "cancel");
    }
    int rows = repo.cancel(triggerId);
    if (rows == 0) {
      throw new RetrainingException.InvalidStateTransition(
          triggerId, before.status().name(), "cancel (raced)");
    }
    log.warn("retraining: trigger {} cancelled (was {})", triggerId, before.status());
    return loadOrThrow(triggerId);
  }

  // --- reaper (3d.4 will schedule this) --------------------------------

  /**
   * Reap stuck {@code running} rows by flipping them back to {@code queued}. Used by the 3d.4
   * stale-claim reaper to recover from a crashed worker. Returns rows touched.
   */
  @Transactional
  public int reapStaleClaims(java.time.Duration staleAfter) {
    int reaped = repo.reapStaleClaims(staleAfter);
    if (reaped > 0) {
      log.warn(
          "retraining: reaped {} stale running claim(s) older than {} back to queued",
          reaped,
          staleAfter);
    }
    return reaped;
  }

  // --- reads ------------------------------------------------------------

  public RetrainingTrigger getByTriggerId(String triggerId) {
    return loadOrThrow(triggerId);
  }

  public List<RetrainingTrigger> findByModel(String modelName) {
    return repo.findByModel(modelName);
  }

  public List<RetrainingTrigger> findAllQueued() {
    return repo.findAllQueued();
  }

  // --- helpers ----------------------------------------------------------

  private RetrainingTrigger loadOrThrow(String triggerId) {
    return repo.findByTriggerId(triggerId)
        .orElseThrow(() -> new RetrainingException.UnknownTrigger(triggerId));
  }

  private static String writeJson(Map<String, ?> map) {
    try {
      return MAPPER.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not serialize trigger metadata to JSON", e);
    }
  }
}
