package net.thebullpen.baseball.retraining.triggers;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.retraining.RetrainingException;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Monthly cron retrain floor (decision [79] hybrid trigger design). Fires at midnight ET on the 1st
 * of each month for every active CHAMPION model. Per rule 6 / decision [44]: success produces a
 * CANDIDATE-stage row; promotion stays a human action via 3a.4.
 *
 * <p>Idempotency: the trigger_id encodes the month + model_name so re-running the @Scheduled method
 * (e.g. across worker restarts on the 1st) is a no-op insert error caught + swallowed.
 */
@Component
@Profile("worker")
public class ScheduledTrigger {

  private static final Logger log = LoggerFactory.getLogger(ScheduledTrigger.class);
  private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

  private final RegistryRepository registryRepo;
  private final RetrainingQueueService queue;

  public ScheduledTrigger(RegistryRepository registryRepo, RetrainingQueueService queue) {
    this.registryRepo = registryRepo;
    this.queue = queue;
  }

  @Scheduled(cron = "0 0 0 1 * *", zone = "America/New_York")
  public void monthly() {
    try {
      runOnce(LocalDate.now());
    } catch (RuntimeException e) {
      log.error("ScheduledTrigger: monthly run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns the number of triggers actually enqueued. */
  public int runOnce(LocalDate today) {
    String month = today.format(MONTH_FMT);
    int enqueued = 0;
    for (ModelVersion champ : registryRepo.findActiveChampions()) {
      String triggerId = "sched-" + month + "-" + champ.modelName();
      try {
        queue.enqueue(
            champ.modelName(),
            TriggerType.SCHEDULED,
            triggerId,
            Map.of("schedule", "monthly", "month", month, "champion_version_id", champ.id()));
        enqueued++;
      } catch (RetrainingException.DuplicateTriggerId e) {
        log.info(
            "ScheduledTrigger: {} already enqueued for {} — dedup (worker restart?)",
            champ.modelName(),
            month);
      }
    }
    log.info("ScheduledTrigger: monthly run for {} — enqueued {} model(s)", month, enqueued);
    return enqueued;
  }
}
