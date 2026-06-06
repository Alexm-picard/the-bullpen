package net.thebullpen.baseball.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Pure parser for the two MLB Stats API documents the live poller consumes: the schedule ({@code
 * /api/v1/schedule}) and the GUMBO live feed ({@code /api/v1.1/game/{pk}/feed/live}). No HTTP lives
 * here - the transport is {@link MlbStatsApiClient} - so this class is unit-tested against captured
 * real-game fixtures (the MLB HTTP boundary is the one place mocking is allowed, per the CLAUDE.md
 * testing posture).
 *
 * <p>Navigation is defensive ({@code path()} not {@code get()}): GUMBO is large and partially
 * optional, and an unexpected-missing field must degrade (null / skip) rather than NPE the worker.
 */
@Component
public class MlbFeedParser {

  private final ObjectMapper mapper;

  public MlbFeedParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** Parse the schedule document into the day's games. */
  public List<ScheduledGame> parseSchedule(String json) throws IOException {
    JsonNode root = mapper.readTree(json);
    List<ScheduledGame> games = new ArrayList<>();
    for (JsonNode date : root.path("dates")) {
      for (JsonNode g : date.path("games")) {
        games.add(
            new ScheduledGame(
                g.path("gamePk").asLong(),
                GameStatus.fromMlbDetailedState(textOrNull(g.path("status").path("detailedState"))),
                textOrNull(g.path("teams").path("home").path("team").path("name")),
                textOrNull(g.path("teams").path("away").path("team").path("name"))));
      }
    }
    return games;
  }

  /**
   * Parse a GUMBO live feed into the game's status and every pitch seen so far. Pre-pitch count,
   * base occupancy, outs, and score are reconstructed by walking the plays in order (see {@link
   * LivePitch}); base occupancy and outs reset at each half-inning boundary.
   */
  public LiveGameFeed parseLiveFeed(String json) throws IOException {
    JsonNode root = mapper.readTree(json);
    JsonNode gameData = root.path("gameData");
    long gamePk = root.path("gamePk").asLong(gameData.path("game").path("pk").asLong());
    GameStatus status =
        GameStatus.fromMlbDetailedState(textOrNull(gameData.path("status").path("detailedState")));
    JsonNode home = gameData.path("teams").path("home");
    JsonNode away = gameData.path("teams").path("away");

    List<LivePitch> pitches = new ArrayList<>();

    // State carried across at-bats within a half-inning (base occupancy + outs) and across the
    // whole
    // game (running score). The feed's per-pitch count is post-pitch, so we reconstruct pre-pitch.
    int prevInning = -1;
    String prevHalf = null;
    boolean onFirst = false;
    boolean onSecond = false;
    boolean onThird = false;
    int enteringOuts = 0;
    int homeScore = 0;
    int awayScore = 0;

    for (JsonNode play : root.path("liveData").path("plays").path("allPlays")) {
      JsonNode about = play.path("about");
      int inning = about.path("inning").asInt();
      String half = about.path("halfInning").asText();
      boolean topInning = about.path("isTopInning").asBoolean();
      if (inning != prevInning || !half.equals(prevHalf)) {
        onFirst = false;
        onSecond = false;
        onThird = false;
        enteringOuts = 0;
        prevInning = inning;
        prevHalf = half;
      }

      int atBatIndex = about.path("atBatIndex").asInt();
      JsonNode matchup = play.path("matchup");
      long pitcherId = matchup.path("pitcher").path("id").asLong();
      long batterId = matchup.path("batter").path("id").asLong();
      String pitchHand = textOrNull(matchup.path("pitchHand").path("code"));
      String batSide = textOrNull(matchup.path("batSide").path("code"));

      // Snapshot the state ENTERING this at-bat; it applies to all of its pitches (v1
      // approximation).
      boolean abFirst = onFirst;
      boolean abSecond = onSecond;
      boolean abThird = onThird;
      int abOuts = enteringOuts;
      int abHome = homeScore;
      int abAway = awayScore;

      List<JsonNode> pitchEvents = new ArrayList<>();
      for (JsonNode e : play.path("playEvents")) {
        if (e.path("isPitch").asBoolean()) {
          pitchEvents.add(e);
        }
      }
      int preBalls = 0;
      int preStrikes = 0;
      for (int i = 0; i < pitchEvents.size(); i++) {
        JsonNode e = pitchEvents.get(i);
        JsonNode details = e.path("details");
        JsonNode pd = e.path("pitchData");
        pitches.add(
            new LivePitch(
                gamePk,
                atBatIndex,
                e.path("pitchNumber").asInt(),
                inning,
                topInning,
                pitcherId,
                batterId,
                pitchHand,
                batSide,
                preBalls,
                preStrikes,
                abOuts,
                abFirst,
                abSecond,
                abThird,
                abHome,
                abAway,
                mapDescription(details),
                textOrNull(details.path("type").path("code")),
                asDouble(pd.path("startSpeed")),
                asDouble(pd.path("coordinates").path("pX")),
                asDouble(pd.path("coordinates").path("pZ")),
                i == pitchEvents.size() - 1));
        // The next pitch's pre-count is this pitch's post-count (read from the feed, not computed).
        JsonNode c = e.path("count");
        preBalls = c.path("balls").asInt(preBalls);
        preStrikes = c.path("strikes").asInt(preStrikes);
      }

      // Update the carry for the next at-bat from this play's resolved state.
      onFirst = isOccupied(matchup.path("postOnFirst"));
      onSecond = isOccupied(matchup.path("postOnSecond"));
      onThird = isOccupied(matchup.path("postOnThird"));
      enteringOuts = play.path("count").path("outs").asInt(abOuts);
      homeScore = play.path("result").path("homeScore").asInt(homeScore);
      awayScore = play.path("result").path("awayScore").asInt(awayScore);
    }

    return new LiveGameFeed(
        gamePk,
        status,
        parseGameDate(gameData),
        home.path("id").asInt(),
        away.path("id").asInt(),
        textOrNull(home.path("abbreviation")),
        textOrNull(away.path("abbreviation")),
        pitches);
  }

  private static LocalDate parseGameDate(JsonNode gameData) {
    String official = textOrNull(gameData.path("datetime").path("officialDate"));
    if (official != null && !official.isBlank()) {
      return LocalDate.parse(official);
    }
    String dt = textOrNull(gameData.path("datetime").path("dateTime"));
    return dt == null ? null : OffsetDateTime.parse(dt).toLocalDate();
  }

  private static boolean isOccupied(JsonNode base) {
    return !base.isMissingNode() && !base.isNull();
  }

  private static Double asDouble(JsonNode n) {
    return n.isNumber() ? n.asDouble() : null;
  }

  private static String textOrNull(JsonNode n) {
    return n.isValueNode() && !n.isNull() ? n.asText() : null;
  }

  /**
   * Collapse the MLB pitch-result vocabulary to the canonical {@code pitches} enum (matching V003's
   * {@code transform_raw_to_pitches} multiIf). Prefers {@code details.isInPlay}, then the
   * single-letter {@code call.code}, then a substring match on the call description as a fallback
   * for any code the table doesn't list.
   */
  private static String mapDescription(JsonNode details) {
    if (details.path("isInPlay").asBoolean()) {
      return "in_play";
    }
    String code = details.path("call").path("code").asText("");
    switch (code) {
      case "C":
        return "called_strike";
      case "S":
      case "W": // swinging strike (blocked)
      case "M": // missed bunt
      case "Q": // swinging pitchout
        return "swinging_strike";
      case "F":
      case "T": // foul tip
      case "O": // foul bunt
      case "R": // foul pitchout
      case "L": // foul bunt (alt)
        return "foul";
      case "B":
      case "*B": // ball in dirt
      case "I": // intentional ball
      case "P": // pitchout
      case "V": // automatic ball (pitch clock)
        return "ball";
      case "H":
        return "hit_by_pitch";
      default:
        // fall through to the text fallback
    }
    String desc =
        details
            .path("call")
            .path("description")
            .asText(details.path("description").asText(""))
            .toLowerCase(Locale.ROOT);
    if (desc.contains("hit by pitch")) {
      return "hit_by_pitch";
    }
    if (desc.contains("called strike")) {
      return "called_strike";
    }
    if (desc.contains("swinging") || desc.contains("missed bunt")) {
      return "swinging_strike";
    }
    if (desc.contains("foul")) {
      return "foul";
    }
    if (desc.contains("ball") || desc.contains("pitchout")) {
      return "ball";
    }
    return "unknown";
  }
}
