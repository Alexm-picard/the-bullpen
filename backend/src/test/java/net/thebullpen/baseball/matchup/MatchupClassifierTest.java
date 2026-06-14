package net.thebullpen.baseball.matchup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.GameStatus;
import net.thebullpen.baseball.ingest.ScheduledGame;
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
}
