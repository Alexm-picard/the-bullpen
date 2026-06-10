package net.thebullpen.baseball.ingest;

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

  private final PitcherFormRepository repo;

  public PitcherFormRefreshJob(PitcherFormRepository repo) {
    this.repo = repo;
  }

  @Scheduled(cron = "0 40 2 * * *", zone = "America/New_York")
  public void run() {
    try {
      long n = runOnce();
      log.info("PitcherFormRefreshJob: refreshed current form for {} pitcher(s)", n);
    } catch (RuntimeException e) {
      log.error("PitcherFormRefreshJob: refresh failed", e);
    }
  }

  /**
   * Visible-for-tests entry point. Returns the number of pitchers whose current form was refreshed.
   */
  public long runOnce() {
    return repo.refreshCurrentForm();
  }
}
