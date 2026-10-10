package net.thebullpen.baseball.drift.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.data.LiveReliabilityRepository;
import net.thebullpen.baseball.data.RollingAccuracyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily writer of {@code live_reliability_daily} (V035), the rollup behind the /accuracy live
 * calibration charts. {@code pitches_live} holds ~14 days of truth; the chart window is 30, so the
 * truth join has to be materialised before the TTL expires it.
 *
 * <p><b>Schedule:</b> 06:30 ET, after the 02:00-06:00 retrain window and long before any game. Each
 * run recomputes, per pitch head, the trailing {@value #RECOMPUTE_DAYS} ET days through yesterday,
 * which absorbs late feed corrections ({@code pitches_live FINAL}) and late ingests; older days are
 * frozen, which is correct because their truth is final. If the newest rolled-up day is further
 * back than that (worker down for a few days) the run widens to cover the gap, and a model with no
 * row at all (first run) backfills {@value #BACKFILL_DAYS} days: the full game days {@code
 * pitches_live} still holds at 06:30 (its TTL is {@code toDate(ingested_at) + 14 DAY}, so the
 * 14th-oldest day is already partly expired and is deliberately not frozen half-counted).
 *
 * <p><b>Freshness</b> (the [186] silent-staleness lesson, [189] alert pattern): {@code
 * bullpen_reliability_rollup_last_success_timestamp_seconds} advances ONLY when every head rolled
 * up; {@code bullpen_reliability_rollup_runs_total{outcome}} counts success / failure runs. The 36h
 * staleness and never-ran alerts in {@code bullpen-alerts.yml} read the stamp. The page also shows
 * {@code truthThrough}, so a stalled rollup is visible to every reader, not just the operator.
 *
 * <p>Writes nothing to {@code prediction_log} ([188]) and reads {@code pitches_live} only - it is
 * not a live-to-historical promotion job ([186]). LOCKED per ET fire date ({@link
 * JobLockRepository}) like the rest of the scheduled fleet; the write is idempotent anyway.
 * Failures are logged, not thrown: a missed day is re-covered by the next run's 3-day window.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class LiveReliabilityRollupJob {

  private static final Logger log = LoggerFactory.getLogger(LiveReliabilityRollupJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");
  static final String JOB_NAME = "live_reliability_rollup";

  static final String LAST_SUCCESS_METRIC =
      "bullpen_reliability_rollup_last_success_timestamp_seconds";
  static final String RUNS_METRIC = "bullpen_reliability_rollup_runs_total";

  /** Steady-state recompute window: the last 3 ET game days through yesterday. */
  static final int RECOMPUTE_DAYS = 3;

  /** First-run / catch-up ceiling: the full game days pitches_live still holds at 06:30 ET. */
  static final int BACKFILL_DAYS = 13;

  private final LiveReliabilityRepository repo;
  private final JobLockRepository jobLocks;
  private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);
  private final Counter successRuns;
  private final Counter failureRuns;

  public LiveReliabilityRollupJob(
      LiveReliabilityRepository repo, JobLockRepository jobLocks, MeterRegistry meters) {
    this.repo = repo;
    this.jobLocks = jobLocks;
    Gauge.builder(LAST_SUCCESS_METRIC, lastSuccessEpochSeconds, AtomicLong::doubleValue)
        .description(
            "Epoch seconds of the last run in THIS PROCESS that rolled up every pitch head into"
                + " live_reliability_daily; 0 until the first success after boot. Alert on time()"
                + " - this > 36h (stale) and on == 0 past 36h of process uptime (never ran).")
        .register(meters);
    this.successRuns =
        Counter.builder(RUNS_METRIC)
            .description("live_reliability_daily rollup runs by outcome")
            .tag("outcome", "success")
            .register(meters);
    this.failureRuns =
        Counter.builder(RUNS_METRIC)
            .description("live_reliability_daily rollup runs by outcome")
            .tag("outcome", "failure")
            .register(meters);
  }

  @Scheduled(cron = "0 30 6 * * *", zone = "America/New_York")
  public void run() {
    LocalDate fireDate = LocalDate.now(ET);
    if (!jobLocks.tryAcquire(JOB_NAME, fireDate)) {
      log.info("{} already ran for {} on another instance; skipping", JOB_NAME, fireDate);
      return;
    }
    runOnce();
  }

  /**
   * Visible-for-tests entry point: roll up every pitch head, each in its own failure domain (one
   * head's fault does not skip the others). Returns true iff every head succeeded, which is the
   * only case that advances the freshness stamp.
   */
  public boolean runOnce() {
    boolean allOk = true;
    for (String model : RollingAccuracyRepository.PITCH_HEADS) {
      try {
        int days = recomputeDays(repo.daysSinceLastRollup(model));
        repo.rollup(model, days);
        log.info("{}: {} rolled up over the last {} ET days", JOB_NAME, model, days);
      } catch (RuntimeException e) {
        allOk = false;
        log.error("{}: rollup failed for {}; its previous rows stand", JOB_NAME, model, e);
      }
    }
    if (allOk) {
      lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
      successRuns.increment();
    } else {
      failureRuns.increment();
    }
    return allOk;
  }

  /**
   * Days to recompute given the gap since the newest rolled-up day ({@code -1} = never rolled up):
   * at least {@link #RECOMPUTE_DAYS}, widened to cover a gap, never past {@link #BACKFILL_DAYS}.
   */
  static int recomputeDays(long daysSinceLastRollup) {
    if (daysSinceLastRollup < 0) {
      return BACKFILL_DAYS;
    }
    return (int) Math.min(BACKFILL_DAYS, Math.max(RECOMPUTE_DAYS, daysSinceLastRollup));
  }
}
