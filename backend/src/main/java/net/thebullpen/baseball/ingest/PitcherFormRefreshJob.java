package net.thebullpen.baseball.ingest;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.thebullpen.baseball.data.PitcherFormRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DP2 / WS3: the nightly job that materialises {@code pitcher_form_current} so the live pitch path
 * has real Tier-3 form to serve instead of NaN. Without it, every live prediction feeds the model
 * null form features (documented skew, V007 / decision [143]).
 *
 * <p>Runs at 02:40 ET - after the feature/drift jobs (02:00-02:10) and before the 03:00 snapshot,
 * in the off-peak window outside any live game. FORM LAG IS NOT BOUNDED BY THE SCHEDULE: it equals
 * today minus the corpus edge (max usable game_date in pitches), which grows without limit between
 * manual backfills - the 2026-07-27 finding was that it had silently reached 63 days while this
 * comment claimed "at most one day". The age gauge below is the exposure; the acceptable bound
 * belongs to the open /decide.
 *
 * <p>Worker-profile. Failures are logged, not thrown: a missed refresh degrades to yesterday's form
 * (or NaN), it must not crash the worker.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PitcherFormRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(PitcherFormRefreshJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final PitcherFormRepository repo;

  /** Days between today and max(game_date) in pitches; -1 until the first run this process sees. */
  private final AtomicLong ageDays = new AtomicLong(-1);

  public PitcherFormRefreshJob(PitcherFormRepository repo, MeterRegistry meters) {
    this.repo = repo;
    // THE HALF THAT WOULD HAVE MADE THIS VISIBLE IN JUNE. The 2026-07-27 finding: this job's
    // windows read `pitches`, a manually-backfilled corpus (last backfill ran 2026-05-28, loading
    // games through 2026-05-25), and for two months the nightly refresh selected nothing while
    // its clock-anchored stamp said "fresh". An honest as_of_date alone is a truthful column
    // nobody reads - this gauge is the alerting PRECONDITION, not yet an alert: no rule exists
    // for either age gauge, and the threshold ("how far is too far") belongs to the open /decide.
    // When the rule lands it MUST be `< 0 or > N`, because -1 means never-ran-this-process and
    // passes a bare `> N` silently. Mirrors bullpen_pitchtype_prior_age_days (V030), whose
    // data-anchored refusal is what surfaced the problem. Expressed in DAYS BEHIND so the
    // threshold reads in the units the staleness is felt in.
    Gauge.builder("bullpen_pitcher_form_age_days", ageDays, AtomicLong::doubleValue)
        .description(
            "Days between today and the newest usable game_date in pitches - the corpus the"
                + " Tier-3 28-day form windows read. -1 before the first refresh this process"
                + " ran, so any alert rule must be `< 0 or > N` (a bare `> N` reads -1 as fresher"
                + " than fresh). Climbs between manual backfills; a large value means the nightly"
                + " full-cohort refresh selects little or nothing and most pitchers serve NaN"
                + " form. Set only on a successful run: a DEAD job pins it at its last value, so"
                + " it cannot distinguish corpus-stale from job-dead - the last-success-timestamp"
                + " companion (the bullpen_ingest_last_poll idiom) is the queued fix for that"
                + " half.")
        .register(meters);
  }

  @Scheduled(cron = "0 40 2 * * *", zone = "America/New_York")
  public void run() {
    long n;
    try {
      n = repo.refreshCurrentForm();
    } catch (RuntimeException e) {
      log.error("PitcherFormRefreshJob: refresh failed", e);
      return;
    }
    try {
      updateAgeGauge();
    } catch (RuntimeException e) {
      // Named separately so a gauge failure is never logged as "refresh failed" - the refresh
      // itself succeeded, and this PR is about log lines telling the truth.
      log.error("PitcherFormRefreshJob: corpus-age gauge update failed (refresh succeeded)", e);
    }
    log.info(
        "PitcherFormRefreshJob: {} pitcher(s) have a row at the corpus anchor; corpus is {} day(s)"
            + " behind today",
        n,
        ageDays.get());
  }

  /**
   * Visible-for-tests entry point. Returns the count of pitchers with a row AT the data anchor.
   *
   * <p>NOT a write count, and not always zero when the refresh writes nothing: once the corpus ages
   * past the 28-day window, earlier nights' rows still sit at the same anchor and keep the count at
   * cohort size (COUNT_AT_ANCHOR documents this). What the anchor-based count fixes is narrower and
   * real: a live-leg stray stamped with a CURRENT date can no longer be reported as a refreshed
   * pitcher, which is exactly the "refreshed 7" lie from the morning of the finding. The gauge, not
   * this count, is the staleness signal.
   */
  public long runOnce() {
    long n = repo.refreshCurrentForm();
    updateAgeGauge();
    return n;
  }

  private void updateAgeGauge() {
    ageDays.set(ChronoUnit.DAYS.between(repo.corpusMaxGameDate(), LocalDate.now(ET)));
  }
}
