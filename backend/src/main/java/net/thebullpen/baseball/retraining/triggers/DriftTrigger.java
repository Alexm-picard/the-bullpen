package net.thebullpen.baseball.retraining.triggers;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.drift.DriftMetric;
import net.thebullpen.baseball.drift.DriftMetricsRepository;
import net.thebullpen.baseball.drift.DriftWindows;
import net.thebullpen.baseball.drift.MetricType;
import net.thebullpen.baseball.registry.DiscordNotifier;
import net.thebullpen.baseball.registry.RegistryRepository;
import net.thebullpen.baseball.registry.dto.ModelVersion;
import net.thebullpen.baseball.retraining.RetrainingException;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drift-driven retrain trigger (decision [80]). Daily at 4 AM ET (after 3c's drift batches + the 3
 * AM alert evaluator). For every active CHAMPION, checks the last 7 days of CALIBRATION_ERROR — if
 * every daily sample is above {@code calibrationDriftThreshold} (default 0.10; same scale as 3c.7's
 * PAGE threshold but tighter <em>duration</em>: 7 days vs 3), enqueues a retrain. Per leaf "Known
 * edge cases" the 7-day window is intentionally tighter than 3c.7's 3-day PAGE — gives the operator
 * a 4-day human-investigation window before automatic retrain action fires.
 *
 * <p>Dedup via {@link RetrainingQueueService#isAlreadyQueuedRecently} — if a drift retrain for this
 * model is already QUEUED or RUNNING within 7 days, skip the enqueue. Plus the day-stamped
 * trigger_id catches same-day re-fires across worker restarts.
 *
 * <p>{@code @ConditionalOnBean(DriftMetricsRepository.class)} so dev-without-ClickHouse brings the
 * worker up without trying to wire this trigger.
 */
@Component
@Profile("worker")
@ConditionalOnBean(DriftMetricsRepository.class)
public class DriftTrigger {

  private static final Logger log = LoggerFactory.getLogger(DriftTrigger.class);
  private static final Duration LOOKBACK = Duration.ofDays(7);
  private static final Duration DEDUP_WINDOW = Duration.ofDays(7);

  private final RegistryRepository registryRepo;
  private final DriftMetricsRepository driftRepo;
  private final RetrainingQueueService queue;
  private final DiscordNotifier discord;
  private final double calibrationDriftThreshold;

  public DriftTrigger(
      RegistryRepository registryRepo,
      DriftMetricsRepository driftRepo,
      RetrainingQueueService queue,
      DiscordNotifier discord,
      @Value("${bullpen.retraining.drift.calibration-threshold:0.10}")
          double calibrationDriftThreshold) {
    this.registryRepo = registryRepo;
    this.driftRepo = driftRepo;
    this.queue = queue;
    this.discord = discord;
    this.calibrationDriftThreshold = calibrationDriftThreshold;
  }

  @Scheduled(cron = "0 0 4 * * *", zone = "America/New_York")
  public void daily() {
    try {
      runOnce(Instant.now());
    } catch (RuntimeException e) {
      log.error("DriftTrigger: daily run failed", e);
    }
  }

  /** Visible-for-tests entry point. Returns the number of triggers actually enqueued. */
  public int runOnce(Instant now) {
    int enqueued = 0;
    String dayKey = now.atZone(ZoneId.of("America/New_York")).toLocalDate().toString();
    for (ModelVersion champ : registryRepo.findActiveChampions()) {
      if (!sustainedDrift(champ)) {
        continue;
      }
      if (queue.isAlreadyQueuedRecently(champ.modelName(), DEDUP_WINDOW)) {
        log.debug(
            "DriftTrigger: {} already has a recent queued/running retrain — dedup",
            champ.modelName());
        continue;
      }
      String triggerId = "drift-" + dayKey + "-" + champ.modelName();
      try {
        queue.enqueue(
            champ.modelName(),
            TriggerType.DRIFT,
            triggerId,
            Map.of(
                "calibration_threshold",
                calibrationDriftThreshold,
                "sustained_days",
                7,
                "champion_version_id",
                champ.id()));
        discord.send(
            DiscordNotifier.Severity.NOTICE,
            "Drift-triggered retrain enqueued for " + champ.modelName(),
            Map.of(
                "trigger_id", triggerId,
                "threshold", calibrationDriftThreshold,
                "lookback_days", LOOKBACK.toDays()));
        enqueued++;
      } catch (RetrainingException.DuplicateTriggerId e) {
        log.info(
            "DriftTrigger: {} already enqueued today — dedup (worker restart?)", champ.modelName());
      }
    }
    if (enqueued > 0) {
      log.info("DriftTrigger: enqueued {} drift-triggered retrain(s) this run", enqueued);
    }
    return enqueued;
  }

  private boolean sustainedDrift(ModelVersion champ) {
    List<DriftMetric> recent =
        driftRepo.findRecent(champ.modelName(), MetricType.CALIBRATION_ERROR, "all", LOOKBACK);
    // Collapse to one canonical value per calendar day before counting "7 days": a same-day rerun
    // of the 2:30 calibration batch writes multiple rows, and counting raw rows would let K reruns
    // on one day masquerade as K sustained days and fire a false retrain (DEF-M3 - the same
    // collapse the 3 AM DriftAlertEvaluator already applies for its PAGE). 7 distinct days over
    // threshold => retrain.
    List<Double> daily = DriftWindows.dailyCanonical(recent, ZoneId.of("America/New_York"));
    if (daily.size() < 7) {
      return false;
    }
    return daily.stream().allMatch(v -> v > calibrationDriftThreshold);
  }
}
