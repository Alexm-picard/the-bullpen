package net.thebullpen.baseball.ingest;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.thebullpen.baseball.config.IngestProperties;
import net.thebullpen.baseball.data.JobLeaseRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.data.PitcherFormRepository;
import net.thebullpen.baseball.domain.CurrentMatchup;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.LivePitch;
import net.thebullpen.baseball.domain.ScheduledGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  // D-37: the single-owner lease name this service holds. One instance polls the MLB API at a time.
  private static final String LIVE_POLL_LEASE = "live_polling";

  private final MlbStatsApiClient client;
  private final LivePitchesRepository repo;
  private final Optional<LivePitchPredictor> predictor;
  // Intra-day form upsert (A3.2). Empty when ClickHouse is disabled (no bean) - then form stays at
  // the nightly snapshot and the predictor degrades to NaN, same as before A3.
  private final Optional<PitcherFormRepository> formRepo;
  private final IngestMetrics metrics;
  // D-37: single-owner heartbeat lease so a second worker instance stays dormant instead of
  // double-INSERTing pitches and doubling MLB API load. Renewed at the top of every tick.
  private final JobLeaseRepository jobLease;
  // Stable per-instance owner id, generated once at construction (NOT static, NOT Math.random) so a
  // restarted process presents a fresh identity and the old lease simply ages out to stale.
  private final String leaseOwner = java.util.UUID.randomUUID().toString();
  private final long leaseStaleSeconds;
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
  // The matchup key (at-bat index + batter id) last WRITTEN to live_game_status for a game, so the
  // status row is re-upserted when the matchup moves - not only when the game's STATUS transitions.
  // Without this the matchup would be written once (at the first transition of a game) and then sit
  // frozen for nine innings, which is the very staleness this feature exists to remove. Keyed on
  // BOTH fields because a pinch hitter keeps the at-bat index and changes the batter. The empty
  // string encodes "no current play", so the null transition is written too and the row stops
  // naming a batter who has finished hitting.
  private final Map<Long, String> lastMatchupKey = new ConcurrentHashMap<>();

  /**
   * Highest pitch cursor already written WITH batted-ball physics, per game.
   *
   * <p>This exists because {@link #writeNewPitches} writes each pitch EXACTLY ONCE - it filters on
   * {@code cursor(p) > since}, so a pitch never gets a second look. That is fine for pitch data,
   * which is complete when the pitch lands, but batted-ball physics is not: hitData populates while
   * the play is still in flight and the parser declines it until the play completes. A ball in play
   * first seen mid-flight would therefore keep empty physics for the rest of the game, and the live
   * batted-ball card would stay empty for exactly the games it exists to serve.
   *
   * <p>Tracking the MAX key rather than a set of keys is sound because at-bats are monotonic: a
   * later BIP always has a higher cursor, so "greater than the highest already written" is
   * equivalent to "not yet written" without unbounded growth per game. (Per-game map growth across
   * a season is tracked in issue 399, which covers all of this class's per-game maps.)
   */
  private final Map<Long, Long> lastBattedBallKey = new ConcurrentHashMap<>();

  private final Map<Long, Instant> lastPollAt = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastCursorByGame = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastPredictedKeyByGame = new ConcurrentHashMap<>();
  // F2.1a: per-game high-water for the POST head so a completed pitch is post-predicted at most
  // once
  // across polls (belt-and-suspenders alongside the cursor high-water that gates the fresh list).
  private final Map<Long, Long> lastPostPredictedKeyByGame = new ConcurrentHashMap<>();
  private final Map<Long, Long> lastFailedKeyByGame = new ConcurrentHashMap<>();
  private volatile List<ScheduledGame> schedule = List.of();
  private volatile Instant scheduleFetchedAt = Instant.EPOCH;
  private long lastApiCallMs;

  public LivePollingService(
      MlbStatsApiClient client,
      LivePitchesRepository repo,
      Optional<LivePitchPredictor> predictor,
      Optional<PitcherFormRepository> formRepo,
      IngestMetrics metrics,
      JobLeaseRepository jobLease,
      IngestProperties props) {
    IngestProperties.Live live = props.live();
    this.client = client;
    this.repo = repo;
    this.predictor = predictor;
    this.formRepo = formRepo;
    this.metrics = metrics;
    this.jobLease = jobLease;
    this.minApiGapMs = live.apiMinGapMs();
    this.scheduleRefreshMin = live.scheduleRefreshMin();
    this.leaseStaleSeconds = live.leaseStaleSeconds();
  }

  @Scheduled(fixedDelayString = "${bullpen.ingest.live.tick-ms:5000}")
  public void tick() {
    if (!jobLease.tryAcquireOrRenew(LIVE_POLL_LEASE, leaseOwner, leaseStaleSeconds)) {
      return; // another instance holds the live-polling lease; stay dormant (singleton poller)
    }
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

  /**
   * The feed's live current-play matchup, or null when it carries no usable one.
   *
   * <p>{@code parseNextPitch} already yields null between plays and once the game is final; the
   * extra {@link CurrentMatchup#isPopulated()} guard catches the OTHER absence shape - the parser's
   * {@code asLong()} yields {@code 0L}, not null, for a missing id, so an early-GUMBO tick produces
   * a matchup naming batter 0. Both collapse to "no matchup", which is WRITTEN - as the V031 0/''
   * sentinels - rather than skipped, so the row stops advertising a finished batter.
   */
  private static CurrentMatchup currentMatchupOf(LiveGameFeed feed) {
    LiveNextPitch next = feed.nextPitch();
    if (next == null) {
      return null;
    }
    CurrentMatchup m =
        new CurrentMatchup(
            next.batterId(), next.pitcherId(), next.batSide(), next.pitchHand(), next.atBatIndex());
    return m.isPopulated() ? m : null;
  }

  /** Change key for the write gate; {@code ""} means "no current play" so null transitions fire. */
  private static String matchupKeyOf(CurrentMatchup m) {
    return m == null ? "" : m.atBatIndex() + ":" + m.batterId();
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
    Instant polledAt = Instant.now();
    lastPollAt.put(gamePk, polledAt);
    metrics.markPollCompleted(polledAt);
    if (feed.status() == GameStatus.UNKNOWN) {
      // Schema-drift tripwire: the feed's detailedState matched nothing we know.
      metrics.incrementParseAnomaly("unknown_game_status");
    }
    // Persist when ANY of four things is true: first-ever observation of the game, a status
    // transition (step 7b), this process's first poll of it (L1: restart-robustness - the schedule
    // prime makes prev == current after a mid-game restart, so transition-only persistence left
    // the game invisible to /v1/games/today until its next transition), OR the MATCHUP MOVED.
    // That last disjunct is what makes the current batter dynamic (V031): the matchup changes
    // roughly once per at-bat while the status changes a handful of times per game, so a
    // transition-only cadence would freeze the batter at whatever he was on the last transition.
    // The ReplacingMergeTree dedups the re-writes.
    CurrentMatchup matchup = currentMatchupOf(feed);
    String matchupKey = matchupKeyOf(matchup);
    boolean matchupMoved = !matchupKey.equals(lastMatchupKey.get(gamePk));
    if (prev == null || prev != current || !statusPersisted.contains(gamePk) || matchupMoved) {
      if (feed.gameDate() != null) {
        repo.upsertGameStatus(gamePk, feed.gameDate(), current.name(), matchup);
        statusPersisted.add(gamePk);
        lastMatchupKey.put(gamePk, matchupKey);
      } else {
        // No parseable gameData.datetime in the feed: the row cannot key into live_game_status,
        // so /v1/games/today will not surface this game (C-3 replay finding, 2026-06-11).
        metrics.incrementParseAnomaly("missing_game_date");
        // This branch also fires on a MATCHUP move with prev == current, so it must not describe
        // what was dropped as a "status transition".
        log.debug(
            "game {} status/matchup row not persisted (status {} -> {}): feed carried no gameDate",
            gamePk,
            prev,
            current);
      }
    }
    writeNewPitches(gamePk, feed);
    backfillCompletedBattedBalls(gamePk, feed);
    predictNextPitch(gamePk, feed);
  }

  /**
   * Re-write balls in play that have GAINED physics since their pitch was first stored.
   *
   * <p>Ordered AFTER {@link #writeNewPitches} on purpose: that call may have just written this BIP
   * complete on its first sighting, in which case it has already bumped the key and there is
   * nothing here to do. What remains is the case the cursor gate cannot serve - a pitch stored
   * while its play was in flight, whose hitData only became complete on a later poll.
   *
   * <p>Targeted rather than a rolling re-write window: roughly ONE extra insert per ball in play
   * (~50 per game) instead of re-writing the last N pitches every 5s tick, which would be ~80x
   * write amplification into a 14-day TTL table. The ReplacingMergeTree supersedes on (game_id,
   * at_bat_index, pitch_number), so the re-insert replaces the physics-less row rather than
   * duplicating it - the capability V015's header always claimed and nothing exercised.
   */
  private void backfillCompletedBattedBalls(long gamePk, LiveGameFeed feed) {
    long lastKey = lastBattedBallKey.getOrDefault(gamePk, 0L);
    List<LivePitch> completed =
        feed.pitches().stream().filter(p -> p.battedBall() != null && cursor(p) > lastKey).toList();
    if (completed.isEmpty()) {
      return;
    }
    repo.insertPitches(withPitches(feed, completed));
    metrics.incrementPitchesIngested(completed.size());
    completed.stream()
        .mapToLong(LivePollingService::cursor)
        .max()
        .ifPresent(key -> bumpBattedBallKey(gamePk, key));
  }

  /**
   * Raise the watermark.
   *
   * <p>Uses max rather than a plain put, but HONESTLY: with today's callers a lower key cannot
   * arrive. Cursors are monotonic within a game, and both call sites bump with the max of a set
   * already filtered to values above the watermark, so no mutation of this line changes any
   * observable behaviour - I checked, and replacing it with put() reds nothing. It is kept because
   * it states the invariant the field depends on at the point that depends on it, not because it is
   * guarding a case anyone has found. Do not add a test asserting it fires; there is no input that
   * makes it fire.
   */
  private void bumpBattedBallKey(long gamePk, long key) {
    lastBattedBallKey.merge(gamePk, key, Math::max);
  }

  private void writeNewPitches(long gamePk, LiveGameFeed feed) {
    long since = lastCursorByGame.getOrDefault(gamePk, 0L);
    List<LivePitch> fresh = feed.pitches().stream().filter(p -> cursor(p) > since).toList();
    if (fresh.isEmpty()) {
      return;
    }
    repo.insertPitches(withPitches(feed, fresh));
    metrics.incrementPitchesIngested(fresh.size());
    // Schema-drift tripwire: a pitch whose result vocabulary collapsed to "unknown" means the
    // feed is using words the parser's mapping table has never seen.
    metrics.incrementParseAnomalies(
        "unknown_pitch_description",
        fresh.stream().filter(p -> "unknown".equals(p.description())).count());
    lastCursorByGame.put(
        gamePk, fresh.stream().mapToLong(LivePollingService::cursor).max().orElse(since));
    // A pitch that ALREADY carried physics on its first sighting needs no backfill later.
    fresh.stream()
        .filter(p -> p.battedBall() != null)
        .mapToLong(LivePollingService::cursor)
        .max()
        .ifPresent(key -> bumpBattedBallKey(gamePk, key));
    refreshIntraDayForm(gamePk, fresh);
    predictPostForCompletedPitches(gamePk, feed, fresh);
  }

  /**
   * F2.1a: after new COMPLETED pitches land, run the {@code pitch_outcome_post} head on each and
   * enqueue a keyed {@code prediction_log} row. Uses the same containment as {@link
   * #predictNextPitch} - a model-load / inference failure (or an incomplete-Tier-4 skip) degrades
   * THIS pitch, logged at warn, and never escapes the poll tick - plus a per-game dedup so a
   * completed pitch is post-predicted at most once across polls. The park comes from the feed's
   * {@code nextPitch} (may be null at game end, in which case the predictor's completeness gate
   * skips the pitch).
   */
  private void predictPostForCompletedPitches(
      long gamePk, LiveGameFeed feed, List<LivePitch> fresh) {
    if (predictor.isEmpty()) {
      return;
    }
    String parkId = feed.nextPitch() == null ? null : feed.nextPitch().parkId();
    for (LivePitch pitch : fresh) {
      long key = (long) pitch.atBatIndex() * 100 + pitch.pitchNumber();
      if (key == lastPostPredictedKeyByGame.getOrDefault(gamePk, -1L)) {
        continue; // already post-predicted this completed pitch on an earlier poll
      }
      try {
        predictor.get().predictPostAndLog(pitch, parkId, feed.gameDate());
        lastPostPredictedKeyByGame.put(gamePk, key);
      } catch (Exception e) {
        log.warn(
            "live post prediction failed for game {} at key {}; skipping this pitch",
            gamePk,
            key,
            e);
      }
    }
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
