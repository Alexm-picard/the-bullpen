package net.thebullpen.baseball.matchup;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.thebullpen.baseball.data.GameMatchupsRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.data.PlayerSeasonStatsRepository;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.Lineup;
import net.thebullpen.baseball.ingest.MlbStatsApiClient;
import net.thebullpen.baseball.ingest.PlayerSeasonStat;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The ~3:45 ET morning matchup refresh (Phase 2c). After the 03:00 snapshot / 03:30 offsite, it
 * refreshes the day's probable-pitcher season stats (ERA) and writes the DEFAULT pitcher-vs-pitcher
 * matchup per game. The lineup-aware re-classification (~1-2h before first pitch, layering wOBA,
 * which can flip the lean to hitters/mixed) is Phase 3.
 *
 * <p>Worker profile, gated on {@code bullpen.ingest.live.enabled} (same as the poller) - it needs
 * the MLB API + ClickHouse. Best-effort + logged; a failure must not crash the worker.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "bullpen.ingest.live.enabled", havingValue = "true")
public class MatchupRefreshJob {

  private static final Logger log = LoggerFactory.getLogger(MatchupRefreshJob.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final MlbStatsApiClient client;
  private final LivePitchesRepository slateRepo;
  private final PlayerSeasonStatsRepository statsRepo;
  private final GameMatchupsRepository matchupsRepo;
  private final MatchupClassifier classifier;

  public MatchupRefreshJob(
      MlbStatsApiClient client,
      LivePitchesRepository slateRepo,
      PlayerSeasonStatsRepository statsRepo,
      GameMatchupsRepository matchupsRepo,
      MatchupClassifier classifier) {
    this.client = client;
    this.slateRepo = slateRepo;
    this.statsRepo = statsRepo;
    this.matchupsRepo = matchupsRepo;
    this.classifier = classifier;
  }

  @Scheduled(cron = "${bullpen.matchup.morning-cron:0 45 3 * * *}", zone = "America/New_York")
  public void refresh() {
    try {
      refreshFor(LocalDate.now(ET));
    } catch (Exception e) {
      log.warn("matchup morning refresh failed", e);
    }
  }

  /** Refresh the default (pitcher-vs-pitcher) matchups for a date. Visible for testing. */
  void refreshFor(LocalDate date) {
    int season = date.getYear();
    List<ScheduledGame> games = slateRepo.findScheduledGames(date);
    if (games.isEmpty()) {
      log.info("matchup refresh {}: no scheduled games", date);
      return;
    }
    Set<Long> pitcherIds = new LinkedHashSet<>();
    for (ScheduledGame g : games) {
      if (g.homeProbableId() != 0) {
        pitcherIds.add(g.homeProbableId());
      }
      if (g.awayProbableId() != 0) {
        pitcherIds.add(g.awayProbableId());
      }
    }
    try {
      statsRepo.upsert(client.fetchSeasonStats(pitcherIds, season));
    } catch (Exception e) {
      // The stats fetch is best-effort - classify on whatever stats already landed (a missing ERA
      // becomes the classifier's league-average default).
      log.warn(
          "matchup refresh {}: season-stats fetch failed; classifying on existing stats", date, e);
    }
    Map<Long, Double> eraById =
        statsRepo.findForPlayers(pitcherIds, season).stream()
            .filter(s -> "pitching".equals(s.statGroup()) && s.era() != null)
            .collect(
                Collectors.toMap(PlayerSeasonStat::playerId, PlayerSeasonStat::era, (a, b) -> a));
    List<GameMatchup> matchups =
        games.stream()
            .map(
                g ->
                    classifier.classifyDefault(
                        g, date, eraById.get(g.homeProbableId()), eraById.get(g.awayProbableId())))
            .toList();
    matchupsRepo.upsert(matchups);
    log.info(
        "matchup refresh {}: wrote {} default matchups ({} probables)",
        date,
        matchups.size(),
        pitcherIds.size());
  }

  // --- lineup-aware re-classification (~1-2h before first pitch) --------------------------------

  // A game becomes due when first pitch is within LEAD minutes; still processed up to GRACE minutes
  // after start (the lineup may post late), after which the live poller owns it.
  static final long LINEUP_LEAD_MINUTES = 120;
  static final long POST_START_GRACE_MINUTES = 180;

  /** Every ~20 min: re-classify games whose lineups have posted (stage 'default' -> 'lineup'). */
  @Scheduled(fixedDelayString = "${bullpen.matchup.lineup-tick-ms:1200000}")
  public void refreshLineups() {
    try {
      refreshLineupsFor(LocalDate.now(ET), Instant.now());
    } catch (Exception e) {
      log.warn("lineup matchup refresh failed", e);
    }
  }

  /** Re-classify the day's due games with their posted lineups. Visible for testing. */
  void refreshLineupsFor(LocalDate date, Instant now) {
    int season = date.getYear();
    List<ScheduledGame> games = slateRepo.findScheduledGames(date);
    Map<Long, String> stageById =
        matchupsRepo.findForDate(date).stream()
            .collect(Collectors.toMap(GameMatchup::gameId, GameMatchup::stage, (a, b) -> a));
    for (ScheduledGame g : games) {
      Instant t = g.gameTimeUtc();
      if (t == null) {
        continue;
      }
      long minsToStart = Duration.between(now, t).toMinutes();
      if (minsToStart > LINEUP_LEAD_MINUTES || minsToStart < -POST_START_GRACE_MINUTES) {
        continue; // too early, or already underway (the poller owns it)
      }
      if ("lineup".equals(stageById.get(g.gamePk()))) {
        continue; // already re-classified with a lineup
      }
      try {
        reclassifyWithLineup(g, date, season);
      } catch (Exception e) {
        log.warn("lineup reclassify failed for game {}", g.gamePk(), e);
      }
    }
  }

  private void reclassifyWithLineup(ScheduledGame g, LocalDate date, int season) throws Exception {
    Lineup lu = client.fetchLineup(g.gamePk());
    if (lu.home().isEmpty() && lu.away().isEmpty()) {
      return; // lineup not posted yet - retry next tick
    }
    // The probable pitchers come from the schedule (a late scratch is a known follow-up: re-read
    // the actual starter from the boxscore). Refresh the season stats for the hitters + probables.
    Set<Long> ids = new LinkedHashSet<>();
    lu.home().forEach(b -> ids.add(b.id()));
    lu.away().forEach(b -> ids.add(b.id()));
    if (g.homeProbableId() != 0) {
      ids.add(g.homeProbableId());
    }
    if (g.awayProbableId() != 0) {
      ids.add(g.awayProbableId());
    }
    statsRepo.upsert(client.fetchSeasonStats(ids, season));
    List<PlayerSeasonStat> stats = statsRepo.findForPlayers(ids, season);
    Map<Long, Double> woba =
        stats.stream()
            .filter(s -> "hitting".equals(s.statGroup()) && s.woba() != null)
            .collect(
                Collectors.toMap(PlayerSeasonStat::playerId, PlayerSeasonStat::woba, (a, b) -> a));
    Map<Long, Double> era =
        stats.stream()
            .filter(s -> "pitching".equals(s.statGroup()) && s.era() != null)
            .collect(
                Collectors.toMap(PlayerSeasonStat::playerId, PlayerSeasonStat::era, (a, b) -> a));
    List<MatchupClassifier.Hitter> homeLineup =
        lu.home().stream()
            .map(b -> new MatchupClassifier.Hitter(b.id(), b.name(), woba.get(b.id())))
            .toList();
    List<MatchupClassifier.Hitter> awayLineup =
        lu.away().stream()
            .map(b -> new MatchupClassifier.Hitter(b.id(), b.name(), woba.get(b.id())))
            .toList();
    GameMatchup m =
        classifier.classifyWithLineups(
            g,
            date,
            era.get(g.homeProbableId()),
            era.get(g.awayProbableId()),
            homeLineup,
            awayLineup);
    matchupsRepo.upsert(List.of(m));
    log.info("lineup reclassify game {}: lean={} stage=lineup", g.gamePk(), m.lean());
  }
}
