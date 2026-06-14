package net.thebullpen.baseball.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
      // This captured fixture predates &hydrate=team and carries no game-level gameDate, so the
      // new fields degrade to null (the read path then falls back to the full name / no time).
      assertNull(g.homeAbbr());
      assertNull(g.gameTimeUtc());
    }
  }

  @Test
  void parseSchedule_extracts_start_time_abbreviations_and_probable_pitchers() throws IOException {
    // Synthetic hydrated schedule (&hydrate=team,probablePitcher + a real gameDate) - the captured
    // fixture has none of these, so this is the one place the new fields are exercised end-to-end.
    String json =
        "{\"dates\":[{\"games\":[{"
            + "\"gamePk\":777001,"
            + "\"gameDate\":\"2026-06-04T17:10:00Z\","
            + "\"status\":{\"detailedState\":\"Scheduled\"},"
            + "\"teams\":{"
            + "  \"home\":{\"team\":{\"name\":\"Boston Red Sox\",\"abbreviation\":\"BOS\"},"
            + "           \"probablePitcher\":{\"id\":605483,\"fullName\":\"Brayan Bello\"}},"
            + "  \"away\":{\"team\":{\"name\":\"Baltimore Orioles\",\"abbreviation\":\"BAL\"},"
            + "           \"probablePitcher\":{\"id\":669203,\"fullName\":\"Grayson Rodriguez\"}}"
            + "}}]}]}";
    List<ScheduledGame> games = parser.parseSchedule(json);

    assertEquals(1, games.size());
    ScheduledGame g = games.get(0);
    assertEquals(777001L, g.gamePk());
    assertNotNull(g.status());
    assertEquals("BOS", g.homeAbbr());
    assertEquals("BAL", g.awayAbbr());
    assertEquals("Boston Red Sox", g.homeName());
    assertEquals("Baltimore Orioles", g.awayName());
    assertEquals(Instant.parse("2026-06-04T17:10:00Z"), g.gameTimeUtc());
    assertEquals(605483L, g.homeProbableId());
    assertEquals("Brayan Bello", g.homeProbableName());
    assertEquals(669203L, g.awayProbableId());
    assertEquals("Grayson Rodriguez", g.awayProbableName());
  }

  @Test
  void parseSchedule_defaults_probable_pitchers_when_not_announced() throws IOException {
    // No probablePitcher node (TBD) -> id 0 / name "" sentinels (matching the column defaults).
    String json =
        "{\"dates\":[{\"games\":[{"
            + "\"gamePk\":777002,"
            + "\"status\":{\"detailedState\":\"Scheduled\"},"
            + "\"teams\":{\"home\":{\"team\":{\"name\":\"Chicago Cubs\"}},"
            + "           \"away\":{\"team\":{\"name\":\"St. Louis Cardinals\"}}"
            + "}}]}]}";
    ScheduledGame g = parser.parseSchedule(json).get(0);
    assertEquals(0L, g.homeProbableId());
    assertEquals("", g.homeProbableName());
    assertEquals(0L, g.awayProbableId());
  }

  @Test
  void parseSeasonStats_reads_era_and_computes_woba_from_hitting_components() throws IOException {
    String json =
        "{\"people\":[{\"id\":605141,\"stats\":["
            + "{\"group\":{\"displayName\":\"hitting\"},\"splits\":[{\"season\":\"2026\","
            + "  \"stat\":{\"atBats\":500,\"baseOnBalls\":60,\"intentionalWalks\":5,"
            + "  \"hitByPitch\":8,\"hits\":150,\"doubles\":30,\"triples\":3,\"homeRuns\":25,"
            + "  \"sacFlies\":5,\"plateAppearances\":573}}]},"
            + "{\"group\":{\"displayName\":\"pitching\"},\"splits\":[{\"season\":\"2026\","
            + "  \"stat\":{\"era\":\"3.45\",\"battersFaced\":700}}]}"
            + "]}]}";
    List<PlayerSeasonStat> stats = parser.parseSeasonStats(json);

    assertEquals(2, stats.size());
    PlayerSeasonStat hitting =
        stats.stream().filter(s -> s.statGroup().equals("hitting")).findFirst().orElseThrow();
    PlayerSeasonStat pitching =
        stats.stream().filter(s -> s.statGroup().equals("pitching")).findFirst().orElseThrow();

    // uBB=55, 1B=92, denom=500+60-5+5+8=568; num=0.69*55+0.72*8+0.89*92+1.27*30+1.62*3+2.10*25.
    assertEquals(221.05 / 568.0, hitting.woba(), 1e-9);
    assertNull(hitting.era());
    assertEquals(573, hitting.sample().intValue());
    assertEquals(3.45, pitching.era(), 1e-9);
    assertNull(pitching.woba());
    assertEquals(700, pitching.sample().intValue());
  }

  @Test
  void parseSeasonStats_woba_is_null_without_plate_appearances() throws IOException {
    String json =
        "{\"people\":[{\"id\":1,\"stats\":[{\"group\":{\"displayName\":\"hitting\"},"
            + "\"splits\":[{\"season\":\"2026\",\"stat\":{}}]}]}]}";
    PlayerSeasonStat s = parser.parseSeasonStats(json).get(0);
    assertNull(s.woba()); // denom 0 -> null
  }

  @Test
  void parseLineup_reads_batting_orders_and_names() throws IOException {
    String json =
        "{\"teams\":{"
            + "\"home\":{\"battingOrder\":[660271,592450],\"players\":{"
            + "  \"ID660271\":{\"person\":{\"id\":660271,\"fullName\":\"Shohei Ohtani\"}},"
            + "  \"ID592450\":{\"person\":{\"id\":592450,\"fullName\":\"Aaron Judge\"}}}},"
            + "\"away\":{\"battingOrder\":[605141],\"players\":{"
            + "  \"ID605141\":{\"person\":{\"id\":605141,\"fullName\":\"Mookie Betts\"}}}}"
            + "}}";
    Lineup lu = parser.parseLineup(json, 745804L);
    assertEquals(745804L, lu.gamePk());
    assertEquals(2, lu.home().size());
    assertEquals(660271L, lu.home().get(0).id());
    assertEquals("Shohei Ohtani", lu.home().get(0).name());
    assertEquals("Aaron Judge", lu.home().get(1).name());
    assertEquals(1, lu.away().size());
    assertEquals("Mookie Betts", lu.away().get(0).name());
  }

  @Test
  void parseLineup_is_empty_before_the_lineup_posts() throws IOException {
    Lineup lu = parser.parseLineup("{\"teams\":{\"home\":{},\"away\":{}}}", 1L);
    assertTrue(lu.home().isEmpty());
    assertTrue(lu.away().isEmpty());
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

  @Test
  void parseLiveFeed_extracts_the_next_pitch_context_from_currentPlay() throws IOException {
    LiveGameFeed feed = parser.parseLiveFeed(resource("/mlb/feed_live_inprogress.json"));

    assertEquals(GameStatus.IN_PROGRESS, feed.status());
    LiveNextPitch np = feed.nextPitch();
    assertNotNull(np, "an in-progress at-bat is awaiting a pitch");
    assertEquals(822810L, np.gameId());
    assertEquals(77, np.atBatIndex());
    assertEquals(1, np.pitchNumber(), "0 pitches thrown this at-bat -> next is pitch 1");
    assertEquals(9, np.inning());
    assertFalse(np.topInning(), "bottom of the 9th");
    assertEquals(689296L, np.pitcherId());
    assertEquals(676391L, np.batterId());
    assertEquals("R", np.pitchHand());
    assertEquals("R", np.batSide());
    assertEquals(0, np.balls());
    assertEquals(0, np.strikes());
    assertEquals(0, np.outs());
    assertTrue(np.onFirst(), "runner on first");
    assertFalse(np.onSecond());
    assertEquals(1, np.baseState());
    assertEquals("TOR", np.parkId(), "park id is the home team");
    assertEquals(LocalDate.of(2026, 6, 5), np.gameDate());
  }

  @Test
  void parseLiveFeed_has_no_next_pitch_for_a_completed_game() throws IOException {
    // The full-game fixture is Final and carries no currentPlay -> nothing to predict.
    assertNull(parser.parseLiveFeed(resource("/mlb/feed_live_824753.json")).nextPitch());
  }

  @Test
  void parsePlayers_reads_the_captured_roster() throws IOException {
    // Trimmed real capture of /api/v1/sports/1/players?season=2026 (2026-06-10): five players
    // covering a two-way player, a pitcher, a catcher, a switch hitter, and a shortstop.
    List<MlbPlayer> players = parser.parsePlayers(resource("/mlb/players_2026.json"));

    assertEquals(5, players.size());

    MlbPlayer ohtani = players.stream().filter(p -> p.id() == 660271L).findFirst().orElseThrow();
    assertEquals("Shohei Ohtani", ohtani.name());
    assertEquals("TW", ohtani.primaryPosition(), "TWP clamps to V014's FixedString(2)");
    assertEquals("L", ohtani.bats());
    assertEquals("R", ohtani.throwsHand());
    assertTrue(ohtani.active());

    MlbPlayer albies = players.stream().filter(p -> p.id() == 645277L).findFirst().orElseThrow();
    assertEquals("Ozzie Albies", albies.name());
    assertEquals("2B", albies.primaryPosition());
    assertEquals("S", albies.bats(), "switch hitter keeps the S code");

    MlbPlayer abbott = players.stream().filter(p -> p.id() == 671096L).findFirst().orElseThrow();
    assertEquals("P", abbott.primaryPosition());
    assertEquals("L", abbott.throwsHand());
  }

  @Test
  void parsePlayers_skips_unusable_entries_and_defaults_missing_codes() throws IOException {
    // Hand-written degenerate document: only the first entry is usable; it is missing every
    // optional code (older-season payload defensiveness) so the codes default to "".
    String json =
        """
        {"people": [
          {"id": 123, "fullName": "No Codes Guy"},
          {"fullName": "Missing Id"},
          {"id": 0, "fullName": "Zero Id"},
          {"id": 9, "fullName": "   "},
          {"id": -4, "fullName": "Negative Id"}
        ]}
        """;

    List<MlbPlayer> players = parser.parsePlayers(json);

    assertEquals(1, players.size());
    MlbPlayer p = players.get(0);
    assertEquals(123L, p.id());
    assertEquals("No Codes Guy", p.name());
    assertEquals("", p.primaryPosition());
    assertEquals("", p.bats());
    assertEquals("", p.throwsHand());
    assertFalse(p.active(), "missing active defaults to false");
  }

  @Test
  void parsePlayers_returns_empty_for_a_document_without_people() throws IOException {
    assertTrue(parser.parsePlayers("{}").isEmpty());
  }
}
