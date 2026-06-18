package net.thebullpen.baseball.ingest;

import java.io.IOException;
import java.time.Year;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.thebullpen.baseball.data.PlayersRefreshRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DP3 / WS3: keeps the {@code players} dimension (V014) populated so {@code /v1/players/search}
 * resolves real names instead of reading an empty table. Two entry points:
 *
 * <ul>
 *   <li><b>Backfill-on-empty</b> at worker startup: when {@code players} has zero rows, pull every
 *       season {@value #FIRST_SEASON}..current and write the per-id latest row. Runs on a virtual
 *       thread off the startup path - deploy.sh's 30s worker smoke must never wait on ~12 MLB API
 *       calls. Idempotent: any rows at all means skip (the weekly refresh keeps it current), so
 *       this is a no-op on every deploy after the first.
 *   <li><b>Weekly refresh</b> (Mondays 03:40 ET - after the 03:00 snapshot, outside any live-game
 *       window): re-pull the current season only. The ReplacingMergeTree re-write refreshes names,
 *       positions, and active flags; FINAL reads pick the newest row.
 * </ul>
 *
 * <p>{@value #FIRST_SEASON} matches the analytical backfill floor (decision [86] / rolling-CV span
 * 2015-2025), so every {@code pitcher_id} / {@code batter_id} in {@code pitches} resolves to a
 * name. Roster pulls for historical seasons still carry CURRENT truth for mutable fields (an
 * MLB-id'd player retired since 2015 comes back {@code active=false}), and ascending-season
 * iteration keeps the most recent season's row per id.
 *
 * <p>NOT a live-flip gate (campaign cut line). Failures log and degrade - search serves whatever
 * rows exist - and must never crash the worker.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(
    name = "bullpen.ingest.players.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PlayersRefreshJob {

  static final int FIRST_SEASON = 2015;

  private static final Logger log = LoggerFactory.getLogger(PlayersRefreshJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final MlbStatsApiClient client;
  private final PlayersRefreshRepository repo;
  private final boolean forceRefreshOnBoot;

  public PlayersRefreshJob(
      MlbStatsApiClient client,
      PlayersRefreshRepository repo,
      @Value("${bullpen.ingest.players.force-refresh-on-boot:false}") boolean forceRefreshOnBoot) {
    this.client = client;
    this.repo = repo;
    this.forceRefreshOnBoot = forceRefreshOnBoot;
  }

  /**
   * On boot: the normal path backfills only when the table is empty; when {@code
   * bullpen.ingest.players.force-refresh-on-boot} is set, force a current-season re-pull even on a
   * populated table. The forced path exists to backfill a newly-added column (V024 {@code team})
   * onto an existing roster - {@link #backfillIfEmpty} no-ops once rows exist and the weekly
   * refresh is Monday-only. Set the flag for one deploy, then clear it.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    Runnable task = forceRefreshOnBoot ? this::forceRefreshSafely : this::backfillIfEmptySafely;
    Thread.ofVirtual().name("players-backfill").start(task);
  }

  /** Visible-for-tests: forced current-season re-pull (writes even on a populated table). */
  void forceRefreshSafely() {
    try {
      int n = refreshOnce();
      log.info("PlayersRefreshJob: forced boot refresh wrote {} players", n);
    } catch (IOException | RuntimeException e) {
      log.error("PlayersRefreshJob: forced boot refresh failed", e);
    }
  }

  /** Visible-for-tests: the swallow wrapper the startup thread runs. */
  void backfillIfEmptySafely() {
    try {
      backfillIfEmpty();
    } catch (RuntimeException e) {
      // ClickHouse down at boot, etc. Degrades to an empty search; retried next worker restart.
      log.error("PlayersRefreshJob: startup backfill failed", e);
    }
  }

  /**
   * Visible-for-tests. Returns the number of player rows written (0 when the table already has
   * rows). Per-season fetch failures are logged and skipped - partial coverage now beats none, and
   * the empty-table check only passes when NOTHING was ever written, so a fully-failed backfill
   * retries on the next worker restart.
   */
  public int backfillIfEmpty() {
    long existing = repo.countAll();
    if (existing > 0) {
      log.info("PlayersRefreshJob: players has {} rows - backfill not needed", existing);
      return 0;
    }
    int currentSeason = Year.now(ET).getValue();
    Map<Long, MlbPlayer> byId = new LinkedHashMap<>();
    for (int season = FIRST_SEASON; season <= currentSeason; season++) {
      try {
        List<MlbPlayer> players = client.fetchPlayers(season);
        for (MlbPlayer p : players) {
          byId.put(p.id(), p); // ascending seasons: the latest season's row wins per id
        }
        log.info("PlayersRefreshJob: season {} returned {} players", season, players.size());
      } catch (IOException e) {
        log.warn("PlayersRefreshJob: season {} fetch failed - skipping it", season, e);
      }
    }
    if (byId.isEmpty()) {
      log.error(
          "PlayersRefreshJob: backfill fetched 0 players; table stays empty, retried on next startup");
      return 0;
    }
    int written = repo.upsertAll(List.copyOf(byId.values()));
    log.info(
        "PlayersRefreshJob: backfilled {} players (seasons {}..{})",
        written,
        FIRST_SEASON,
        currentSeason);
    return written;
  }

  @Scheduled(cron = "0 40 3 * * MON", zone = "America/New_York")
  public void weeklyRefresh() {
    try {
      int n = refreshOnce();
      log.info("PlayersRefreshJob: weekly refresh wrote {} players", n);
    } catch (IOException | RuntimeException e) {
      // A missed week degrades to last week's roster; it must not crash the worker.
      log.error("PlayersRefreshJob: weekly refresh failed", e);
    }
  }

  /** Visible-for-tests. Re-pull the current season; returns rows written. */
  public int refreshOnce() throws IOException {
    return repo.upsertAll(client.fetchPlayers(Year.now(ET).getValue()));
  }
}
