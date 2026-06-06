package net.thebullpen.baseball.ingest;

import ai.onnxruntime.OrtException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.thebullpen.baseball.data.LivePitchesRepository;
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
  private final GameStateMachine stateMachine = new GameStateMachine();
  private final long minApiGapMs;
  private final long scheduleRefreshMin;

  private final Map<Long, GameStatus> statusByGame = new ConcurrentHashMap<>();
  private final Map<Long, Instant> lastPollAt = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastCursorByGame = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastPredictedKeyByGame = new ConcurrentHashMap<>();
  private volatile List<ScheduledGame> schedule = List.of();
  private volatile Instant scheduleFetchedAt = Instant.EPOCH;
  private long lastApiCallMs;

  public LivePollingService(
      MlbStatsApiClient client,
      LivePitchesRepository repo,
      Optional<LivePitchPredictor> predictor,
      @Value("${bullpen.ingest.live.api-min-gap-ms:500}") long minApiGapMs,
      @Value("${bullpen.ingest.live.schedule-refresh-min:15}") long scheduleRefreshMin) {
    this.client = client;
    this.repo = repo;
    this.predictor = predictor;
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
          pollGame(g.gamePk());
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
    // Persist status only on a transition (step 7b) so the api read path can surface it; the
    // in-memory map alone is invisible across the profile/process boundary.
    if ((prev == null || prev != current) && feed.gameDate() != null) {
      repo.upsertGameStatus(gamePk, feed.gameDate(), current.name());
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
  }

  private void predictNextPitch(long gamePk, LiveGameFeed feed) {
    LiveNextPitch np = feed.nextPitch();
    if (np == null || predictor.isEmpty()) {
      return;
    }
    long key = (long) np.atBatIndex() * 100 + np.pitchNumber();
    if (key == lastPredictedKeyByGame.getOrDefault(gamePk, -1L)) {
      return; // already predicted this upcoming pitch on an earlier poll
    }
    try {
      predictor.get().predictAndLog(np);
      lastPredictedKeyByGame.put(gamePk, key);
    } catch (OrtException e) {
      log.warn("live prediction failed for game {}", gamePk, e);
    }
  }

  private void refreshScheduleIfStale() {
    if (Duration.between(scheduleFetchedAt, Instant.now()).toMinutes() < scheduleRefreshMin) {
      return;
    }
    try {
      rateLimit();
      schedule = client.fetchSchedule(LocalDate.now(ET));
      scheduleFetchedAt = Instant.now();
      for (ScheduledGame g : schedule) {
        statusByGame.putIfAbsent(g.gamePk(), g.status());
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
  private synchronized void rateLimit() {
    long wait = minApiGapMs - (System.currentTimeMillis() - lastApiCallMs);
    if (wait > 0) {
      try {
        Thread.sleep(wait);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    lastApiCallMs = System.currentTimeMillis();
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
