package net.thebullpen.baseball.matchup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.domain.GameStatus;
import net.thebullpen.baseball.domain.ScheduledGame;
import org.junit.jupiter.api.Test;

class MatchupClassifierTest {

  private final MatchupClassifier classifier = new MatchupClassifier();
  private static final LocalDate DATE = LocalDate.of(2026, 6, 5);

  private static ScheduledGame game(long homeId, String homeName, long awayId, String awayName) {
    return new ScheduledGame(
        1L,
        GameStatus.SCHEDULED,
        "BOS",
        "NYY",
        "Boston",
        "New York",
        Instant.now(),
        homeId,
        homeName,
        awayId,
        awayName);
  }

  @Test
  void default_is_a_pitching_duel_featuring_both_probables() {
    GameMatchup m = classifier.classifyDefault(game(1L, "Ace A", 2L, "Ace B"), DATE, 2.10, 2.50);
    assertEquals("pitching", m.lean());
    assertEquals("pitcher", m.homeRole());
    assertEquals("pitcher", m.awayRole());
    assertEquals(1L, m.homePlayerId());
    assertEquals("Ace A", m.homePlayerName());
    assertEquals(2L, m.awayPlayerId());
    assertEquals("Ace B", m.awayPlayerName());
    assertEquals("default", m.stage());
    assertEquals(12.0 - 4.60, m.battleScore(), 1e-9); // 12 - (2.10 + 2.50)
  }

  @Test
  void two_aces_outscore_two_back_end_starters() {
    double aces = MatchupClassifier.pitchingDuelScore(1L, 2.00, 2L, 2.20);
    double backEnd = MatchupClassifier.pitchingDuelScore(1L, 5.10, 2L, 4.90);
    assertTrue(aces > backEnd, "a low-ERA pair is the better duel");
  }

  @Test
  void unannounced_probable_scores_zero() {
    assertEquals(0.0, MatchupClassifier.pitchingDuelScore(0L, 2.00, 2L, 2.00), 1e-9);
    assertEquals(0.0, MatchupClassifier.pitchingDuelScore(1L, 2.00, 0L, 2.00), 1e-9);
  }

  @Test
  void missing_era_uses_league_average_not_a_dominating_default() {
    double bothMissing = MatchupClassifier.pitchingDuelScore(1L, null, 2L, null);
    assertEquals(12.0 - 9.0, bothMissing, 1e-9); // 12 - (4.50 + 4.50)
    assertTrue(MatchupClassifier.pitchingDuelScore(1L, 2.0, 2L, 2.0) > bothMissing);
  }

  @Test
  void very_high_combined_era_clamps_at_zero() {
    assertEquals(0.0, MatchupClassifier.pitchingDuelScore(1L, 7.0, 2L, 8.0), 1e-9);
  }

  // --- lineup-aware re-classification --------------------------------------------------------

  private static MatchupClassifier.Hitter h(long id, String name, double woba) {
    return new MatchupClassifier.Hitter(id, name, woba);
  }

  @Test
  void lineups_two_aces_vs_weak_offenses_is_a_pitching_duel() {
    List<MatchupClassifier.Hitter> weak =
        List.of(h(10, "a", 0.290), h(11, "b", 0.285), h(12, "c", 0.295));
    GameMatchup m =
        classifier.classifyWithLineups(
            game(1L, "Ace A", 2L, "Ace B"), DATE, 2.00, 2.10, weak, weak);
    assertEquals("pitching", m.lean());
    assertEquals("pitcher", m.homeRole());
    assertEquals(1L, m.homePlayerId());
    assertEquals("lineup", m.stage());
  }

  @Test
  void lineups_strong_offenses_vs_weak_pitchers_is_a_hitters_duel() {
    List<MatchupClassifier.Hitter> home =
        List.of(h(20, "Masher H", 0.400), h(21, "x", 0.340), h(22, "y", 0.345));
    List<MatchupClassifier.Hitter> away =
        List.of(h(30, "Masher A", 0.395), h(31, "z", 0.350), h(32, "w", 0.342));
    GameMatchup m =
        classifier.classifyWithLineups(
            game(1L, "Back A", 2L, "Back B"), DATE, 5.50, 5.40, home, away);
    assertEquals("hitters", m.lean());
    assertEquals("hitter", m.homeRole());
    assertEquals(20L, m.homePlayerId()); // home's best bat
    assertEquals(30L, m.awayPlayerId()); // away's best bat
  }

  @Test
  void lineups_ace_vs_strong_opposing_offense_is_mixed() {
    List<MatchupClassifier.Hitter> weakHome = List.of(h(40, "wk", 0.285), h(41, "wk2", 0.290));
    List<MatchupClassifier.Hitter> strongAway =
        List.of(h(50, "Slugger", 0.400), h(51, "ok", 0.330));
    // home ace (2.0) + weak home offense; away back-end (5.5) + strong away offense
    GameMatchup m =
        classifier.classifyWithLineups(
            game(1L, "Ace H", 2L, "Back A"), DATE, 2.00, 5.50, weakHome, strongAway);
    assertEquals("mixed", m.lean());
    assertEquals("pitcher", m.homeRole()); // the home ace
    assertEquals(1L, m.homePlayerId());
    assertEquals("hitter", m.awayRole()); // the away masher
    assertEquals(50L, m.awayPlayerId());
  }

  @Test
  void lineups_missing_data_falls_back_to_default() {
    GameMatchup m =
        classifier.classifyWithLineups(
            game(0L, "", 0L, ""), DATE, null, null, List.of(), List.of());
    assertEquals("default", m.stage());
    assertEquals("pitching", m.lean());
  }
}
