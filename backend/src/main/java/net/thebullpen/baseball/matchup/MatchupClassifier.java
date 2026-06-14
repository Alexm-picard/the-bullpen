package net.thebullpen.baseball.matchup;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.springframework.stereotype.Component;

/**
 * Computes a game's matchup. The DEFAULT (morning) classification has only the probable pitchers
 * (no lineup yet), so the lean is always {@code "pitching"} and the two featured people are the
 * probables; the battle score rewards a low combined ERA (two aces = the best pitching duel). The
 * lineup-aware re-classification (Phase 3) layers wOBA on top and can flip the lean to
 * hitters/mixed.
 *
 * <p>Thresholds + scoring are the documented defaults: a TBD probable (id 0) yields score 0 (you
 * cannot feature an unannounced starter), and a missing ERA is treated as a league-average 4.50 so
 * an unrated probable does not top the slate. Higher battle score = the better matchup.
 */
@Component
public class MatchupClassifier {

  static final double LEAGUE_AVG_ERA = 4.50;
  // Combined-ERA anchor: score = base - (homeEra + awayEra), so two ~2.00 aces ~= 8, two ~6.00 = 0.
  static final double DUEL_BASE = 12.0;

  public GameMatchup classifyDefault(
      ScheduledGame g, LocalDate date, Double homeEra, Double awayEra) {
    return new GameMatchup(
        g.gamePk(),
        date,
        "pitching",
        g.homeProbableId(),
        g.homeProbableName(),
        "pitcher",
        g.awayProbableId(),
        g.awayProbableName(),
        "pitcher",
        pitchingDuelScore(g.homeProbableId(), homeEra, g.awayProbableId(), awayEra),
        "default");
  }

  /** Higher = better pitching duel (lower combined ERA). 0 when either starter is unannounced. */
  static double pitchingDuelScore(long homeId, Double homeEra, long awayId, Double awayEra) {
    if (homeId == 0 || awayId == 0) {
      return 0.0;
    }
    double h = homeEra == null ? LEAGUE_AVG_ERA : homeEra;
    double a = awayEra == null ? LEAGUE_AVG_ERA : awayEra;
    return Math.max(0.0, DUEL_BASE - (h + a));
  }

  // --- lineup-aware (stage 'lineup') re-classification ------------------------------------------

  // Hitting-axis scaling (documented defaults, tunable): calibrated so a good pitcher (ERA ~3) and
  // a good offense (lineup wOBA ~0.330) score similarly, making the min/max across the pitching +
  // hitting axes a meaningful comparison. League-average wOBA ~0.315.
  static final double LEAGUE_AVG_WOBA = 0.315;

  /** A lineup hitter with a (possibly null) season wOBA - the re-classification input. */
  public record Hitter(long id, String name, Double woba) {}

  /**
   * The lineup-aware re-classification. Scores the pitching axis (both probables' ERA) and the
   * hitting axis (each lineup's wOBA), then features the strongest storyline: a PITCHING duel (both
   * aces -> the two probables), a HITTERS duel (both offenses -> the two best opposing bats), or
   * MIXED (one side's ace vs the other side's masher). battle_score is the winning storyline's
   * strength; stage is 'lineup'. Falls back to the default when a side's data is missing.
   */
  public GameMatchup classifyWithLineups(
      ScheduledGame g,
      LocalDate date,
      Double homeEra,
      Double awayEra,
      List<Hitter> homeLineup,
      List<Hitter> awayLineup) {
    double homePitch = pitcherQuality(homeEra);
    double awayPitch = pitcherQuality(awayEra);
    double homeHit = offenseQuality(meanWoba(homeLineup));
    double awayHit = offenseQuality(meanWoba(awayLineup));
    Hitter homeBest = bestHitter(homeLineup);
    Hitter awayBest = bestHitter(awayLineup);

    // A storyline is only available when both featured slots can be filled; -1 = unavailable.
    double pitching =
        (g.homeProbableId() != 0 && g.awayProbableId() != 0) ? Math.min(homePitch, awayPitch) : -1;
    double hitters = (homeBest != null && awayBest != null) ? Math.min(homeHit, awayHit) : -1;
    double crossHomeP =
        (g.homeProbableId() != 0 && awayBest != null) ? Math.min(homePitch, awayHit) : -1;
    double crossAwayP =
        (g.awayProbableId() != 0 && homeBest != null) ? Math.min(awayPitch, homeHit) : -1;
    double mixed = Math.max(crossHomeP, crossAwayP);

    double best = Math.max(pitching, Math.max(hitters, mixed));
    if (best < 0) {
      return classifyDefault(g, date, homeEra, awayEra);
    }
    if (pitching == best) {
      return lineupMatchup(
          g.gamePk(),
          date,
          "pitching",
          pitching,
          g.homeProbableId(),
          g.homeProbableName(),
          "pitcher",
          g.awayProbableId(),
          g.awayProbableName(),
          "pitcher");
    }
    if (hitters == best) {
      return lineupMatchup(
          g.gamePk(),
          date,
          "hitters",
          hitters,
          homeBest.id(),
          homeBest.name(),
          "hitter",
          awayBest.id(),
          awayBest.name(),
          "hitter");
    }
    // mixed: the stronger cross-confrontation (a strong pitcher vs the opposing strong offense).
    if (crossHomeP >= crossAwayP) {
      return lineupMatchup(
          g.gamePk(),
          date,
          "mixed",
          crossHomeP,
          g.homeProbableId(),
          g.homeProbableName(),
          "pitcher",
          awayBest.id(),
          awayBest.name(),
          "hitter");
    }
    return lineupMatchup(
        g.gamePk(),
        date,
        "mixed",
        crossAwayP,
        homeBest.id(),
        homeBest.name(),
        "hitter",
        g.awayProbableId(),
        g.awayProbableName(),
        "pitcher");
  }

  private static GameMatchup lineupMatchup(
      long gameId,
      LocalDate date,
      String lean,
      double score,
      long homeId,
      String homeName,
      String homeRole,
      long awayId,
      String awayName,
      String awayRole) {
    return new GameMatchup(
        gameId, date, lean, homeId, homeName, homeRole, awayId, awayName, awayRole, score,
        "lineup");
  }

  /** Pitcher strength from ERA (lower = stronger), 0..6; missing ERA = league average. */
  static double pitcherQuality(Double era) {
    double e = era == null ? LEAGUE_AVG_ERA : era;
    return clamp(6.5 - e, 0.0, 6.0);
  }

  /** Offense strength from a lineup's mean wOBA (higher = stronger), 0..6; missing = league avg. */
  static double offenseQuality(Double meanWoba) {
    double w = meanWoba == null ? LEAGUE_AVG_WOBA : meanWoba;
    return clamp((w - 0.290) * 110.0, 0.0, 6.0);
  }

  static Double meanWoba(List<Hitter> lineup) {
    if (lineup == null || lineup.isEmpty()) {
      return null;
    }
    double sum = 0;
    for (Hitter h : lineup) {
      sum += h.woba() == null ? LEAGUE_AVG_WOBA : h.woba();
    }
    return sum / lineup.size();
  }

  static Hitter bestHitter(List<Hitter> lineup) {
    if (lineup == null || lineup.isEmpty()) {
      return null;
    }
    return lineup.stream()
        .max(Comparator.comparingDouble(h -> h.woba() == null ? LEAGUE_AVG_WOBA : h.woba()))
        .orElse(null);
  }

  private static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }
}
