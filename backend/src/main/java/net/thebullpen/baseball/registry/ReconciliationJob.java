package net.thebullpen.baseball.registry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.thebullpen.baseball.data.PredictionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly cross-DB integrity check (Risk Register G2 / leaf 3a.5): finds any {@code (model_name,
 * model_version)} pair that appears in ClickHouse {@code prediction_log} but isn't in the SQLite
 * {@code model_versions} table — an orphan id means a prediction was logged against a model the
 * registry doesn't know about. That's only possible via direct DB write or a registry-archive race;
 * either way the operator wants to know.
 *
 * <p>Cron: Sundays 4 AM America/New_York (post-baseball-window, low-traffic). Worker-profile only —
 * the API JVM doesn't run the schedule.
 *
 * <p>Lookback: last 7 days. Older orphan history is interesting but not actionable; the
 * pre-existing rows will keep firing the same alert otherwise. If the operator wants a full history
 * sweep, they can extend the window via {@code bullpen.reconciliation.lookback-days}.
 *
 * <p>ClickHouse access is optional — if the worker profile boots without ClickHouse configured
 * (dev), the job logs at INFO and returns. The pure {@link #detectOrphans} helper is what the unit
 * test exercises, so the schedule wiring stays out of the test path.
 */
@Component
@Profile("worker")
public class ReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

  private final RegistryRepository registryRepo;
  private final PredictionLogRepository predictionLogRepo;
  private final DiscordNotifier discord;
  private final int lookbackDays;

  public ReconciliationJob(
      RegistryRepository registryRepo,
      PredictionLogRepository predictionLogRepo,
      DiscordNotifier discord,
      @org.springframework.beans.factory.annotation.Value(
              "${bullpen.reconciliation.lookback-days:7}")
          int lookbackDays) {
    this.registryRepo = registryRepo;
    this.predictionLogRepo = predictionLogRepo;
    this.discord = discord;
    this.lookbackDays = lookbackDays;
  }

  /**
   * Cron entry point. Sundays 4 AM ET — after the post-game-window deploy guard expires and before
   * the 6 AM ET retrain cutoff (decision [19]).
   */
  @Scheduled(cron = "0 0 4 ? * SUN", zone = "America/New_York")
  public void run() {
    try {
      List<String[]> known = registryRepo.findAllNameVersionPairs();
      List<String[]> seen = querySeenPairsFromPredictionLog();
      List<String[]> orphans = detectOrphans(known, seen);
      if (orphans.isEmpty()) {
        log.info(
            "reconciliation: clean — {} prediction_log pair(s) all match registry (known={})",
            seen.size(),
            known.size());
        return;
      }
      log.warn(
          "reconciliation: {} orphan (model_name,version) pair(s) in prediction_log not"
              + " present in registry — {}",
          orphans.size(),
          formatPairs(orphans));
      discord.send(
          DiscordNotifier.Severity.WARN,
          "Orphan model_versions in prediction_log",
          Map.of(
              "count", orphans.size(),
              "orphans", formatPairs(orphans),
              "lookback_days", lookbackDays));
    } catch (RuntimeException e) {
      // Don't let a scheduled-task failure crash the worker — log loudly + page on the next run.
      log.error("reconciliation: job failed", e);
      discord.send(
          DiscordNotifier.Severity.CRITICAL,
          "Reconciliation job crashed",
          Map.of("error", e.getClass().getSimpleName(), "message", String.valueOf(e.getMessage())));
    }
  }

  /**
   * Pure orphan-detection: pairs in {@code seen} that aren't present in {@code known}. Public +
   * static so a unit test can call it without spinning a Spring context.
   */
  public static List<String[]> detectOrphans(List<String[]> known, List<String[]> seen) {
    Set<String> knownKeys = new HashSet<>(known.size());
    for (String[] pair : known) {
      knownKeys.add(joinKey(pair));
    }
    List<String[]> out = new ArrayList<>();
    Set<String> alreadyReported = new HashSet<>();
    for (String[] pair : seen) {
      String key = joinKey(pair);
      if (!knownKeys.contains(key) && alreadyReported.add(key)) {
        out.add(pair);
      }
    }
    return out;
  }

  private List<String[]> querySeenPairsFromPredictionLog() {
    return predictionLogRepo.distinctServedModelVersions(lookbackDays);
  }

  private static String joinKey(String[] pair) {
    // Pair format is {model_name, version}. Joining with a sentinel makes the Set membership
    // unambiguous when either field happens to contain the other's value.
    return pair[0] + "/" + pair[1];
  }

  private static String formatPairs(List<String[]> pairs) {
    StringBuilder sb = new StringBuilder("[");
    boolean first = true;
    for (String[] pair : pairs) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      sb.append(pair[0]).append('/').append(pair[1]);
    }
    return sb.append(']').toString();
  }
}
