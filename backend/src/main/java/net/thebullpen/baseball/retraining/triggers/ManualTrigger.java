package net.thebullpen.baseball.retraining.triggers;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.retraining.RetrainingException;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manual retrain trigger — enqueues from the 3a.4 admin endpoint. Dedups against {@link
 * RetrainingQueueService#isAlreadyQueuedRecently} with a 1-hour window per leaf body task 4
 * ("second call with same modelName within 1h dedups") so an over-eager admin double-click doesn't
 * queue two retrains.
 *
 * <p>Unlike scheduled / drift, the operator picks the {@code reason} string per request; it lands
 * in the queue row's {@code trigger_metadata}.
 */
@Component
public class ManualTrigger {

  private static final Logger log = LoggerFactory.getLogger(ManualTrigger.class);
  private static final Duration DEDUP_WINDOW = Duration.ofHours(1);

  private final RetrainingQueueService queue;

  public ManualTrigger(RetrainingQueueService queue) {
    this.queue = queue;
  }

  /**
   * Enqueue a manual retrain for {@code modelName}. Returns the inserted trigger row, OR the
   * existing recent row if dedup kicked in (so the admin sees the existing trigger_id and status).
   */
  public RetrainingTrigger enqueue(String modelName, String reason, String requestedBy) {
    if (queue.isAlreadyQueuedRecently(modelName, DEDUP_WINDOW)) {
      Optional<RetrainingTrigger> existing = queue.findByModel(modelName).stream().findFirst();
      if (existing.isPresent()) {
        log.info(
            "ManualTrigger: {} already enqueued within {} — returning existing trigger {}",
            modelName,
            DEDUP_WINDOW,
            existing.get().triggerId());
        return existing.get();
      }
    }
    Map<String, Object> metadata = new HashMap<>();
    metadata.put("reason", reason);
    if (requestedBy != null) {
      metadata.put("requested_by", requestedBy);
    }
    try {
      RetrainingTrigger inserted = queue.enqueue(modelName, TriggerType.MANUAL, null, metadata);
      log.info(
          "ManualTrigger: enqueued {} (trigger_id={}, by={}, reason: {})",
          modelName,
          inserted.triggerId(),
          requestedBy,
          reason);
      return inserted;
    } catch (RetrainingException.DuplicateTriggerId e) {
      // Auto-UUID collision — re-derive existing.
      return queue.findByModel(modelName).stream().findFirst().orElseThrow(() -> e);
    }
  }
}
