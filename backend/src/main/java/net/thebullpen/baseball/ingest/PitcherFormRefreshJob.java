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
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DP2 / WS3: the nightly job that materialises {@code pitcher_form_current} so the live pitch path
 * has real Tier-3 form to serve instead of NaN. Without it, every live prediction feeds the model
 * null form features (documented skew, V007 / decision [143]).
 *
 * <p>Runs at 02:40 ET - after the feature/drift jobs (02:00-02:10) and before the 03:00 snapshot,
 * in the off-peak window outside any live game. Form lag is at most one day, which V007 accepts for
 * v1; within-game freshness is the intra-day-upsert follow-up.
 *
 * <p>Worker-profile. Failures are logged, not thrown: a missed refresh degrades to yesterday's form
 * (or NaN), it must not crash the worker.
 */
@Component
@Profile("worker")
public class PitcherFormRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(PitcherFormRefreshJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final PitcherFormRepository repo;

  /** Days between today and max(game_date) in pitches; -1 until the first run this process sees. */
  private final AtomicLong ageDays = new AtomicLong(-1);

  public PitcherFormRefreshJob(PitcherFormRepository repo, MeterRegistry meters) {
    this.repo = repo;
    // THE HALF THAT WOULD HAVE CAUGHT THIS IN JUNE. The 2026-07-27 finding: this job's windows
    // read `pitches`, a manually-backfilled corpus that ended 2026-05-25, and for two months the
    // nightly refresh selected nothing while its clock-anchored stamp said "fresh". An honest
    // as_of_date alone is a truthful column nobody reads - this gauge is what alerts. Mirrors
    // bullpen_pitchtype_prior_age_days (V030), whose data-anchored refusal is what surfaced the
    // problem. Expressed in DAYS BEHIND so a dashboard threshold reads in the same units the
    // staleness is felt in; it climbs between backfills by construction, and the alerting
    // question is "how far is too far", which the open /decide owns.
    Gauge.builder("bullpen_form_age_days", ageDays, AtomicLong::doubleValue)
        .description(
            "Days between today and max(game_date) in pitches - the corpus the Tier-3 28-day form"
                + " windows read. -1 before the first refresh this process ran. Climbs between"
                + " manual backfills; a large value means the nightly full-cohort refresh is"
                + " selecting little or nothing and most pitchers serve NaN form.")
        .register(meters);
  }

  @Scheduled(cron = "0 40 2 * * *", zone = "America/New_York")
  public void run() {
    try {
      long n = runOnce();
      log.info(
          "PitcherFormRefreshJob: refreshed current form for {} pitcher(s); corpus is {} day(s)"
              + " behind today",
          n,
          ageDays.get());
    } catch (RuntimeException e) {
      log.error("PitcherFormRefreshJob: refresh failed", e);
    }
  }

  /**
   * Visible-for-tests entry point. Returns the count of pitchers with a row at the data anchor (an
   * honest ZERO when the corpus has aged out of every 28-day window - the old today()-based count
   * reported live-leg strays as refreshed pitchers instead).
   */
  public long runOnce() {
    long n = repo.refreshCurrentForm();
    ageDays.set(ChronoUnit.DAYS.between(repo.corpusMaxGameDate(), LocalDate.now(ET)));
    return n;
  }
}
