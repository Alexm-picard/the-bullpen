package net.thebullpen.baseball.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Parses captured real-game MLB Stats API fixtures (BAL @ BOS, gamePk 824753, 2026-06-04) - the one
 * place mocking the MLB HTTP boundary is allowed (testing posture). The full-game feed exercises
 * the pre-pitch count reconstruction, the half-inning base/out carry, and the description mapping
 * against every outcome type the game produced.
 */
class MlbFeedParserTest {

  private static final String CANONICAL_DESCRIPTIONS_DOC =
      "ball/called_strike/swinging_strike/foul/in_play/hit_by_pitch/unknown";
  private static final Set<String> CANONICAL =
      Set.of(
          "ball", "called_strike", "swinging_strike", "foul", "in_play", "hit_by_pitch", "unknown");

  private final MlbFeedParser parser = new MlbFeedParser(new ObjectMapper());

  private static String resource(String path) throws IOException {
    try (InputStream in = MlbFeedParserTest.class.getResourceAsStream(path)) {
      assertNotNull(in, "missing test fixture " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void parseSchedule_reads_the_days_games_and_status() throws IOException {
    List<ScheduledGame> games = parser.parseSchedule(resource("/mlb/schedule_2026-06-04.json"));

    assertEquals(4, games.size());
    for (ScheduledGame g : games) {
      assertTrue(g.gamePk() > 0, "gamePk parsed");
      assertEquals(GameStatus.COMPLETED, g.status(), "all four were Final");
      assertNotNull(g.homeName());
      assertNotNull(g.awayName());
    }
  }

  @Test
  void parseLiveFeed_reads_game_metadata() throws IOException {
    LiveGameFeed feed = parser.parseLiveFeed(resource("/mlb/feed_live_824753.json"));

    assertEquals(824753L, feed.gamePk());
    assertEquals(GameStatus.COMPLETED, feed.status());
    assertEquals(LocalDate.of(2026, 6, 4), feed.gameDate());
    assertEquals("BOS", feed.homeAbbrev());
    assertEquals("BAL", feed.awayAbbrev());
    assertTrue(feed.homeTeamId() > 0 && feed.awayTeamId() > 0);
    assertEquals(300, feed.pitches().size(), "every pitch event in the game");
  }

  @Test
  void parseLiveFeed_reconstructs_prepitch_state_for_a_known_at_bat() throws IOException {
    LiveGameFeed feed = parser.parseLiveFeed(resource("/mlb/feed_live_824753.json"));

    // At-bat 1: Gunnar Henderson (683002, L) vs Brayan Bello (678394, R), inning 1 top, 6 pitches
    // ending in a hit-by-pitch. Enters with a runner on second (the at-bat-0 leadoff double).
    List<LivePitch> ab1 =
        feed.pitches().stream().filter(p -> p.atBatIndex() == 1).collect(Collectors.toList());

    assertEquals(6, ab1.size());
    LivePitch first = ab1.get(0);
    LivePitch last = ab1.get(ab1.size() - 1);

    assertEquals(1, first.pitchNumber());
    assertEquals(0, first.preBalls(), "first pitch of the at-bat is a 0-0 count");
    assertEquals(0, first.preStrikes());
    assertEquals(678394L, first.pitcherId());
    assertEquals(683002L, first.batterId());
    assertEquals("R", first.pitchHand());
    assertEquals("L", first.batSide());
    assertEquals(1, first.inning());
    assertTrue(first.topInning());
    assertTrue(first.onSecond(), "carry: at-bat 0 was a leadoff double -> runner on second");
    assertFalse(first.onFirst());

    assertTrue(last.terminal(), "the last pitch ended the at-bat");
    assertEquals("hit_by_pitch", last.description());
    // Pre-pitch count advances monotonically with the feed's post-counts (2-2 going into pitch 6).
    assertEquals(2, last.preBalls());
    assertEquals(2, last.preStrikes());
  }

  @Test
  void parseLiveFeed_maps_every_description_to_the_canonical_vocabulary() throws IOException {
    LiveGameFeed feed = parser.parseLiveFeed(resource("/mlb/feed_live_824753.json"));

    long unknown = feed.pitches().stream().filter(p -> "unknown".equals(p.description())).count();
    for (LivePitch p : feed.pitches()) {
      assertTrue(
          CANONICAL.contains(p.description()),
          "description '" + p.description() + "' not in " + CANONICAL_DESCRIPTIONS_DOC);
    }
    assertEquals(0, unknown, "the mapper should cover every pitch call this real game produced");

    Set<String> seen =
        feed.pitches().stream().map(LivePitch::description).collect(Collectors.toSet());
    assertTrue(seen.contains("in_play"), "game had balls in play");
    assertTrue(seen.contains("hit_by_pitch"), "game had a HBP");
    assertTrue(
        seen.contains("swinging_strike") || seen.contains("called_strike"), "game had strikes");
  }

  @Test
  void parseLiveFeed_marks_exactly_one_terminal_pitch_per_pitched_at_bat() throws IOException {
    LiveGameFeed feed = parser.parseLiveFeed(resource("/mlb/feed_live_824753.json"));

    long terminals = feed.pitches().stream().filter(LivePitch::terminal).count();
    long pitchedAtBats = feed.pitches().stream().map(LivePitch::atBatIndex).distinct().count();
    assertEquals(pitchedAtBats, terminals, "one terminal pitch per at-bat that had pitches");

    for (LivePitch p : feed.pitches()) {
      assertTrue(p.preBalls() >= 0 && p.preBalls() <= 3, "balls in 0..3");
      assertTrue(p.preStrikes() >= 0 && p.preStrikes() <= 2, "strikes in 0..2");
      assertTrue(p.outs() >= 0 && p.outs() <= 2, "outs in 0..2");
      assertTrue(p.baseState() >= 0 && p.baseState() <= 7, "base state is a 3-bit mask");
    }
  }
}
