package net.thebullpen.baseball.matchup;

import java.time.LocalDate;
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
}
