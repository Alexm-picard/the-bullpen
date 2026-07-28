package net.thebullpen.baseball.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.data.PitcherPitchTypePriorRepository;
import net.thebullpen.baseball.data.PitcherPitchTypePriorSnapshotSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly rebuild of {@code pitcher_pitchtype_prior_current}, the career-expanding pitch-type count
 * snapshot the pitch_type model's Tier-ARS features are composed from.
 *
 * <p>WITHOUT THIS JOB THE TABLE IS EMPTY AND EVERY PITCH-TYPE PREDICTION REFUSES. V030 creates the
 * table; nothing populates it. {@code PitchTypeArsenalDeriver} then throws {@code PriorUnavailable}
 * on every request, which is the correct behaviour for an empty snapshot and useless as a serving
 * surface. That is why this lands with (or before) the endpoint rather than after it.
 *
 * <p>Mirrors {@link PitcherFormRefreshJob}, which does the same thing for {@code
 * pitcher_form_current} (V007) - the table V030 was modelled on. Runs at 02:50 ET: after that job
 * at 02:40 and before the 03:00 snapshot, off-peak and outside any live game.
 *
 * <p>LOCKED per fire date with the {@link JobLockRepository} idiom the drift jobs use. A
 * double-fired INSERT into a ReplacingMergeTree is close to harmless by construction - that is part
 * of why the engine was chosen - but at-most-once-per-day is cheap and keeps this consistent with
 * the rest of the scheduled fleet rather than being the one job that reasons about its own
 * idempotence.
 *
 * <p>PUBLISHES A FRESHNESS GAUGE, and that is not decoration. The deriver enforces a staleness
 * bound and REFUSES predictions past it; without a metric in front of that bound, the first signal
 * a refresh has stopped is the serving path failing. This project has already been bitten by
 * exactly that shape - drift jobs firing on schedule and writing zero rows for roughly a month,
 * where an absent-value signal would have shown it immediately. A flat {@code as_of_date} is
 * visible long before it becomes a refusal.
 *
 * <p>Failures are logged, not thrown: a missed refresh degrades to yesterday's snapshot (still
 * composable, the delta just covers more), and it must not crash the worker.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherPitchTypePriorRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(PitcherPitchTypePriorRefreshJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");
  static final String JOB_NAME = "pitcher_pitchtype_prior_refresh";

  private final PitcherPitchTypePriorRepository repo;
  private final JobLockRepository jobLocks;
  private final String y7Expression;

  /** Days since the snapshot's as_of_date; -1 until the first successful run this process sees. */
  private final AtomicLong ageDays = new AtomicLong(-1);

  /** Days of history covered by NEITHER table; -1 until the first successful run. */
  private final AtomicLong coverageGapDays = new AtomicLong(-1);

  public PitcherPitchTypePriorRefreshJob(
      PitcherPitchTypePriorRepository repo,
      JobLockRepository jobLocks,
      MeterRegistry meters,
      @Value("${bullpen.pitchtype.y7-expression:#{null}}") String y7Expression) {
    this.repo = repo;
    this.jobLocks = jobLocks;
    // Default to the pinned constant; the property exists only as an operational escape hatch.
    this.y7Expression =
        (y7Expression == null || y7Expression.isBlank())
            ? PitcherPitchTypePriorSnapshotSql.CANONICAL_Y7
            : y7Expression;
    // Age rather than the raw date: a gauge is a number, and "days behind" is the quantity the
    // deriver's bound is expressed in, so an alert threshold reads the same as the refusal rule.
    io.micrometer.core.instrument.Gauge.builder(
            "bullpen_pitchtype_prior_age_days", ageDays, AtomicLong::doubleValue)
        .description(
            "Days between today and pitcher_pitchtype_prior_current.as_of_date. -1 before the"
                + " first refresh, so any alert rule must be `< 0 or > N`. Climbs while the corpus"
                + " ages between backfills - and is set only on a SUCCESSFUL run, so a dead job"
                + " pins it at its last value rather than climbing; it cannot distinguish"
                + " corpus-stale from job-dead. The climb precedes the deriver refusing"
                + " predictions at its staleness bound.")
        .register(meters);
    // THE SECOND HALF, and the one [186]'s union made necessary. The age gauge above now reads
    // ~1 in steady state because the live leg dominates the anchor - which means it reads ~1
    // EVEN WHEN weeks of history are missing from both tables, the exact condition that used to
    // make the age gauge scream. Age answers "is the newest data recent"; this answers "is the
    // history contiguous". Shipping the union without this would have traded a loud refusal for
    // a silent undercount on a career-expanding feature the model reads directly.
    io.micrometer.core.instrument.Gauge.builder(
            "bullpen_pitchtype_prior_coverage_gap_days", coverageGapDays, AtomicLong::doubleValue)
        .description(
            "Days of history in NEITHER pitches (manually backfilled) nor pitches_live (14-day"
                + " TTL) - the hole between the corpus edge and the live floor. 0 when the two"
                + " ranges touch. -1 before the first successful refresh, so an alert rule must"
                + " be `< 0 or > N` like its sibling. A positive value means the career priors"
                + " are computed over an incomplete history and prior_n - itself a model feature"
                + " - is biased low for every pitcher active in the gap; it is fixed by running"
                + " the backfill, not by waiting, because this window is career-expanding rather"
                + " than rolling and never ages out.")
        .register(meters);
  }

  @Scheduled(cron = "0 50 2 * * *", zone = "America/New_York")
  public void run() {
    LocalDate fireDate = LocalDate.now(ET);
    if (!jobLocks.tryAcquire(JOB_NAME, fireDate)) {
      log.info("{} already ran for {} on another instance; skipping", JOB_NAME, fireDate);
      return;
    }
    try {
      LocalDate asOf = runOnce();
      log.info("{}: snapshot rebuilt, as_of_date={}", JOB_NAME, asOf);
    } catch (RuntimeException e) {
      log.error("{}: refresh failed; the previous snapshot stands", JOB_NAME, e);
    }
  }

  /** Visible-for-tests entry point. Returns the {@code as_of_date} the snapshot anchored to. */
  public LocalDate runOnce() {
    if (y7Expression.isBlank()) {
      throw new IllegalStateException(
          "bullpen.pitchtype.y7-expression is unset. It must be the training SQL's own y7 fold"
              + " (compute_pitch_type_arsenal.sql); refusing to guess a taxonomy, because a wrong"
              + " fold produces a plausible snapshot rather than an obviously broken one.");
    }
    LocalDate asOf = repo.refreshSnapshot(y7Expression);
    ageDays.set(ChronoUnit.DAYS.between(asOf, LocalDate.now(ET)));
    coverageGapDays.set(repo.coverageGapDays());
    return asOf;
  }
}
