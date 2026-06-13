package net.thebullpen.baseball.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.data.PitcherFormRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The live-game write loop (issue #1 step 6): the keystone that ties the producer together. On a
 * fixed tick it discovers today's games (schedule), then for each non-terminal game polls the GUMBO
 * feed at the per-state cadence ({@link GameStateMachine#pollIntervalFor}), writes newly-seen
 * pitches to {@code pitches_live}, and predicts the about-to-be-thrown pitch (decision [143]).
 *
 * <p>Worker-profile, gated on {@code bullpen.ingest.live.enabled} (default false) so deploys don't
 * silently start polling. Prediction degrades gracefully when no model is loaded (the {@link
 * LivePitchPredictor} bean is absent → {@link Optional#empty()}).
 *
 * <p>Two dedup guards keep the loop idempotent across polls: a per-game high-water cursor so only
 * pitches past the last-seen cursor are inserted, and a per-game last-predicted key so the same
 * upcoming pitch isn't re-predicted (and re-logged) on every poll while the at-bat sits at one
 * count.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "bullpen.ingest.live.enabled", havingValue = "true")
public class LivePollingService {

  private static final Logger log = LoggerFactory.getLogger(LivePollingService.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final MlbStatsApiClient client;
  private final LivePitchesRepository repo;
  private final Optional<LivePitchPredictor> predictor;
  // Intra-day form upsert (A3.2). Empty when ClickHouse is disabled (no bean) - then form stays at
  // the nightly snapshot and the predictor degrades to NaN, same as before A3.
  private final Optional<PitcherFormRepository> formRepo;
  private final GameStateMachine stateMachine = new GameStateMachine();
  private final long minApiGapMs;
  private final long scheduleRefreshMin;

  private final Map<Long, GameStatus> statusByGame = new ConcurrentHashMap<>();
  // L1: games whose status row this PROCESS has written. Empty after a worker restart, so the
  // first poll of every game re-persists its current status even without a transition -
  // refreshScheduleIfStale primes statusByGame from the schedule, which otherwise swallows the
  // first write (restart mid-game left the game invisible to /v1/games/today until its next
  // transition).
  private final java.util.Set<Long> statusPersisted = ConcurrentHashMap.newKeySet();
  private final Map<Long, Instant> lastPollAt = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastCursorByGame = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastPredictedKeyByGame = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastFailedKeyByGame = new ConcurrentHashMap<>();
  private volatile List<ScheduledGame> schedule = List.of();
  private volatile Instant scheduleFetchedAt = Instant.EPOCH;
  private long lastApiCallMs;

  public LivePollingService(
      MlbStatsApiClient client,
      LivePitchesRepository repo,
      Optional<LivePitchPredictor> predictor,
      Optional<PitcherFormRepository> formRepo,
      @Value("${bullpen.ingest.live.api-min-gap-ms:500}") long minApiGapMs,
      @Value("${bullpen.ingest.live.schedule-refresh-min:15}") long scheduleRefreshMin) {
    this.client = client;
    this.repo = repo;
    this.predictor = predictor;
    this.formRepo = formRepo;
    this.minApiGapMs = minApiGapMs;
    this.scheduleRefreshMin = scheduleRefreshMin;
  }

  @Scheduled(fixedDelayString = "${bullpen.ingest.live.tick-ms:5000}")
  public void tick() {
    try {
      refreshScheduleIfStale();
      for (ScheduledGame g : schedule) {
        GameStatus status = statusByGame.getOrDefault(g.gamePk(), g.status());
        if (GameStateMachine.shouldPoll(status) && isDue(g.gamePk(), status)) {
          rateLimit();
          try {
            pollGame(g.gamePk());
          } catch (Exception e) {
            // Per-game isolation (C1): a failure polling/predicting one game must not abort the
            // whole tick and starve every other live game. The next tick retries this game on its
            // own cadence. The outer catch below stays as the schedule-iteration backstop.
            log.warn("live poll failed for game {}; continuing the tick", g.gamePk(), e);
          }
        }
      }
    } catch (Exception e) {
      log.warn("live poll tick failed", e);
    }
  }

  /** Poll one game: fetch the feed, adopt its status, write new pitches, predict the next pitch. */
  void pollGame(long gamePk) {
    LiveGameFeed feed;
    try {
      feed = client.fetchLiveFeed(gamePk);
    } catch (Exception e) {
      log.warn("live feed fetch failed for game {}", gamePk, e);
      return;
    }
    GameStatus prev = statusByGame.get(gamePk);
    GameStatus current =
        stateMachine.transition(gamePk, prev == null ? GameStatus.SCHEDULED : prev, feed.status());
    statusByGame.put(gamePk, current);
    lastPollAt.put(gamePk, Instant.now());
    // Persist status on a transition (step 7b) OR on this process's first poll of the game (L1:
    // restart-robustness - the schedule prime makes prev == current after a mid-game restart, so
    // transition-only persistence left the game invisible to /v1/games/today until its next
    // transition). The ReplacingMergeTree dedups the re-write.
    if (prev == null || prev != current || !statusPersisted.contains(gamePk)) {
      if (feed.gameDate() != null) {
        repo.upsertGameStatus(gamePk, feed.gameDate(), current.name());
        statusPersisted.add(gamePk);
      } else {
        // No parseable gameData.datetime in the feed: the row cannot key into live_game_status,
        // so /v1/games/today will not surface this game (C-3 replay finding, 2026-06-11).
        log.debug(
            "game {} status transition {} -> {} not persisted: feed carried no gameDate",
            gamePk,
            prev,
            current);
      }
    }
    writeNewPitches(gamePk, feed);
    predictNextPitch(gamePk, feed);
  }

  private void writeNewPitches(long gamePk, LiveGameFeed feed) {
    long since = lastCursorByGame.getOrDefault(gamePk, 0L);
    List<LivePitch> fresh = feed.pitches().stream().filter(p -> cursor(p) > since).toList();
    if (fresh.isEmpty()) {
      return;
    }
    repo.insertPitches(withPitches(feed, fresh));
    lastCursorByGame.put(
        gamePk, fresh.stream().mapToLong(LivePollingService::cursor).max().orElse(since));
    refreshIntraDayForm(gamePk, fresh);
  }

  /**
   * A3.2: after new pitches land, refresh each active pitcher's intra-day signals ({@code
   * pitches_in_game} + {@code days_since_last_appearance}=0) in {@code pitcher_form_current}, so
   * the next predict-the-next-pitch on this game reads current in-game fatigue instead of the
   * nightly {@code pitches_in_game}=0. Tiny write: only the distinct pitchers in THIS tick's fresh
   * pitches (usually one - the current pitcher). Best-effort - a form-refresh failure is contained
   * so it never aborts the poll (the prediction would just use slightly staler form).
   */
  private void refreshIntraDayForm(long gamePk, List<LivePitch> fresh) {
    if (formRepo.isEmpty()) {
      return;
    }
    fresh.stream()
        .mapToLong(LivePitch::pitcherId)
        .distinct()
        .forEach(
            pitcherId -> {
              try {
                formRepo.get().upsertIntraDayForm(pitcherId, gamePk);
              } catch (RuntimeException e) {
                log.warn(
                    "intra-day form upsert failed for pitcher {} game {}; continuing",
                    pitcherId,
                    gamePk,
                    e);
              }
            });
  }

  private void predictNextPitch(long gamePk, LiveGameFeed feed) {
    LiveNextPitch np = feed.nextPitch();
    if (np == null || predictor.isEmpty()) {
      return;
    }
    if (!LivePitchPredictor.hasResolvableMatchup(np)) {
      // Early GUMBO payload before the matchup populates (null pitchHand/batSide). Skip WITHOUT
      // advancing a cursor so a later poll retries once the hand fills in (C5). debug, not warn:
      // this is an expected sub-second transient at the top of an at-bat, not an error.
      log.debug(
          "live prediction skipped for game {}: matchup (pitchHand/batSide) not yet populated",
          gamePk);
      return;
    }
    long key = (long) np.atBatIndex() * 100 + np.pitchNumber();
    if (key == lastPredictedKeyByGame.getOrDefault(gamePk, -1L)
        || key == lastFailedKeyByGame.getOrDefault(gamePk, -1L)) {
      return; // already predicted (or already failed) this upcoming pitch on an earlier poll
    }
    try {
      predictor.get().predictAndLog(np);
      lastPredictedKeyByGame.put(gamePk, key);
    } catch (Exception e) {
      // Containment + failure-dedup (C1/C2): any model-load or inference failure - e.g. a stale
      // routing row whose snapshot will not load (ModelUnavailableException) - degrades THIS game's
      // prediction instead of escaping the tick. Record the failed key so the same doomed pitch is
      // not re-attempted every tick (no hot-loop); a NEW pitch (new key) is still attempted, so a
      // transient failure self-heals.
      lastFailedKeyByGame.put(gamePk, key);
      log.warn("live prediction failed for game {} at key {}; skipping this pitch", gamePk, key, e);
    }
  }

  private void refreshScheduleIfStale() {
    if (Duration.between(scheduleFetchedAt, Instant.now()).toMinutes() < scheduleRefreshMin) {
      return;
    }
    try {
      rateLimit();
      LocalDate today = LocalDate.now(ET);
      schedule = client.fetchSchedule(today);
      scheduleFetchedAt = Instant.now();
      for (ScheduledGame g : schedule) {
        statusByGame.putIfAbsent(g.gamePk(), g.status());
      }
      // Persist the full day's card so /v1/games/today surfaces every game (names + start time)
      // BEFORE first pitch - the slate is schedule-driven now, not pitch-driven. Best-effort: a
      // persist failure must not abort discovery/polling.
      try {
        repo.upsertScheduledGames(schedule, today);
      } catch (Exception e) {
        log.warn("scheduled_games upsert failed ({} games); slate may lag", schedule.size(), e);
      }
    } catch (Exception e) {
      log.warn("schedule refresh failed", e);
    }
  }

  private boolean isDue(long gamePk, GameStatus status) {
    Instant last = lastPollAt.get(gamePk);
    return last == null
        || Duration.between(last, Instant.now()).compareTo(GameStateMachine.pollIntervalFor(status))
            >= 0;
  }

  /**
   * Be a good citizen: keep at least {@code minApiGapMs} between MLB API calls (~2 req/s ceiling).
   */
  private void rateLimit() {
    // Reserve this caller's slot under the lock, then sleep OUTSIDE it. Holding the monitor across
    // Thread.sleep blocks every other caller for the whole gap (SpotBugs SWL_SLEEP_WITH_LOCK_HELD)
    // and serializes nothing useful. Advancing lastApiCallMs to the reserved target also staggers
    // concurrent callers (each reserves the next slot) instead of releasing a thundering herd.
    long sleepFor;
    synchronized (this) {
      long now = System.currentTimeMillis();
      long target = Math.max(now, lastApiCallMs + minApiGapMs);
      sleepFor = target - now;
      lastApiCallMs = target;
    }
    if (sleepFor > 0) {
      try {
        Thread.sleep(sleepFor);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static long cursor(LivePitch p) {
    return (long) p.atBatIndex() * 100 + p.pitchNumber();
  }

  private static LiveGameFeed withPitches(LiveGameFeed f, List<LivePitch> pitches) {
    return new LiveGameFeed(
        f.gamePk(),
        f.status(),
        f.gameDate(),
        f.homeTeamId(),
        f.awayTeamId(),
        f.homeAbbrev(),
        f.awayAbbrev(),
        pitches,
        f.nextPitch());
  }
}
