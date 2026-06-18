package net.thebullpen.baseball.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Pure parser for the MLB Stats API documents the worker consumes: the schedule ({@code
 * /api/v1/schedule}), the GUMBO live feed ({@code /api/v1.1/game/{pk}/feed/live}), and the player
 * roster ({@code /api/v1/sports/1/players}). No HTTP lives here - the transport is {@link
 * MlbStatsApiClient} - so this class is unit-tested against captured real fixtures (the MLB HTTP
 * boundary is the one place mocking is allowed, per the CLAUDE.md testing posture).
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
        JsonNode teams = g.path("teams");
        games.add(
            new ScheduledGame(
                g.path("gamePk").asLong(),
                GameStatus.fromMlbDetailedState(textOrNull(g.path("status").path("detailedState"))),
                // abbreviation is present only with &hydrate=team; null otherwise (read path
                // coalesces to the live abbreviation or the full name).
                textOrNull(teams.path("home").path("team").path("abbreviation")),
                textOrNull(teams.path("away").path("team").path("abbreviation")),
                textOrNull(teams.path("home").path("team").path("name")),
                textOrNull(teams.path("away").path("team").path("name")),
                parseScheduledStart(textOrNull(g.path("gameDate"))),
                // probablePitcher present only with &hydrate=probablePitcher; 0 / "" = not yet
                // announced (TBD, or a late scratch the ~1-2h-before refresh re-confirms).
                teams.path("home").path("probablePitcher").path("id").asLong(0),
                teams.path("home").path("probablePitcher").path("fullName").asText(""),
                teams.path("away").path("probablePitcher").path("id").asLong(0),
                teams.path("away").path("probablePitcher").path("fullName").asText("")));
      }
    }
    return games;
  }

  /**
   * Parse the schedule's ISO-8601 {@code gameDate} (e.g. {@code 2026-06-04T17:10:00Z}) to an {@link
   * Instant}, or {@code null} if absent / unparseable.
   */
  private static Instant parseScheduledStart(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(iso).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * Parse the bulk people/stats hydrate response ({@code /api/v1/people?personIds=..&hydrate=stats(
   * group=[hitting,pitching],type=[season],season=YYYY)}) into one {@link PlayerSeasonStat} per
   * (player, group, season-split). Pitching splits carry ERA; hitting splits carry a COMPUTED wOBA
   * (the API returns the components, not wOBA). Groups other than hitting/pitching are skipped.
   */
  public List<PlayerSeasonStat> parseSeasonStats(String json) throws IOException {
    JsonNode root = mapper.readTree(json);
    List<PlayerSeasonStat> out = new ArrayList<>();
    for (JsonNode person : root.path("people")) {
      long pid = person.path("id").asLong();
      for (JsonNode block : person.path("stats")) {
        String group = textOrNull(block.path("group").path("displayName"));
        for (JsonNode split : block.path("splits")) {
          int season = parseSeasonYear(textOrNull(split.path("season")));
          JsonNode stat = split.path("stat");
          if ("pitching".equals(group)) {
            out.add(
                new PlayerSeasonStat(
                    pid,
                    season,
                    "pitching",
                    parseDoubleOrNull(textOrNull(stat.path("era"))),
                    null,
                    intOrNull(stat.path("battersFaced"))));
          } else if ("hitting".equals(group)) {
            out.add(
                new PlayerSeasonStat(
                    pid,
                    season,
                    "hitting",
                    null,
                    wobaFromHittingLine(stat),
                    intOrNull(stat.path("plateAppearances"))));
          }
        }
      }
    }
    return out;
  }

  /**
   * wOBA from an MLB hitting season line. The MLB Stats API does not expose wOBA, so it is computed
   * from the counting components with FIXED modern linear weights - a documented approximation
   * (exact year-specific FanGraphs constants are unnecessary for the relative duel ranking, only a
   * consistent scale). Returns {@code null} when the line has no usable denominator (no PA).
   *
   * <p>wOBA = (0.69 uBB + 0.72 HBP + 0.89 1B + 1.27 2B + 1.62 3B + 2.10 HR) / (AB + BB - IBB + SF +
   * HBP), where uBB = BB - IBB and 1B = H - 2B - 3B - HR.
   */
  static Double wobaFromHittingLine(JsonNode stat) {
    int ab = stat.path("atBats").asInt(0);
    int bb = stat.path("baseOnBalls").asInt(0);
    int ibb = stat.path("intentionalWalks").asInt(0);
    int hbp = stat.path("hitByPitch").asInt(0);
    int hits = stat.path("hits").asInt(0);
    int dbl = stat.path("doubles").asInt(0);
    int tpl = stat.path("triples").asInt(0);
    int hr = stat.path("homeRuns").asInt(0);
    int sf = stat.path("sacFlies").asInt(0);
    double denom = (double) ab + bb - ibb + sf + hbp;
    if (denom <= 0) {
      return null;
    }
    int ubb = bb - ibb;
    int singles = hits - dbl - tpl - hr;
    double num = 0.69 * ubb + 0.72 * hbp + 0.89 * singles + 1.27 * dbl + 1.62 * tpl + 2.10 * hr;
    return num / denom;
  }

  private static int parseSeasonYear(String s) {
    if (s == null || s.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static Double parseDoubleOrNull(String s) {
    if (s == null || s.isBlank() || "-.--".equals(s) || "*.**".equals(s)) {
      return null;
    }
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer intOrNull(JsonNode n) {
    return (n == null || n.isMissingNode() || n.isNull()) ? null : n.asInt(0);
  }

  /**
   * Parse a game's boxscore ({@code /api/v1/game/{pk}/boxscore}) into the home/away batting orders
   * (id + display name). Both lists are empty until the lineup is posted (~1-2h before first
   * pitch). Names come from the team's {@code players} map keyed {@code "ID<id>"}.
   */
  public Lineup parseLineup(String json, long gamePk) throws IOException {
    JsonNode teams = mapper.readTree(json).path("teams");
    return new Lineup(gamePk, battingOrder(teams.path("home")), battingOrder(teams.path("away")));
  }

  private static List<Lineup.LineupBatter> battingOrder(JsonNode team) {
    List<Lineup.LineupBatter> out = new ArrayList<>();
    JsonNode players = team.path("players");
    for (JsonNode idNode : team.path("battingOrder")) {
      long id = idNode.asLong();
      String name = players.path("ID" + id).path("person").path("fullName").asText("");
      out.add(new Lineup.LineupBatter(id, name));
    }
    return out;
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

    LocalDate gameDate = parseGameDate(gameData);
    String parkId = textOrNull(home.path("abbreviation"));
    return new LiveGameFeed(
        gamePk,
        status,
        gameDate,
        home.path("id").asInt(),
        away.path("id").asInt(),
        parkId,
        textOrNull(away.path("abbreviation")),
        pitches,
        parseNextPitch(root, gamePk, gameDate, parkId));
  }

  /**
   * Parse the roster document ({@code /api/v1/sports/1/players?season=N}) into rows for the {@code
   * players} dimension (V014, DP3). Entries without a positive id or a non-blank fullName are
   * skipped (the table key is the id and the search UI is name-driven).
   *
   * <p>Width clamps match V014's FixedString columns: {@code primary_position} is FixedString(2),
   * so the one 3-char abbreviation in the wild ("TWP", two-way player) stores as "TW"; bats /
   * throws are single-letter FixedString(1) codes. Missing codes store as "" (FixedString
   * zero-pads; the read side trims).
   */
  /**
   * MLB team id -&gt; abbreviation, matching the frontend teamColors keys (AZ not ARI, ATH not OAK,
   * CWS not CHW, WSH not WSN). Ids are stable; an unmapped or absent currentTeam yields "" (free
   * agent / inactive).
   */
  private static final Map<Integer, String> TEAM_ABBR =
      Map.ofEntries(
          Map.entry(108, "LAA"),
          Map.entry(109, "AZ"),
          Map.entry(110, "BAL"),
          Map.entry(111, "BOS"),
          Map.entry(112, "CHC"),
          Map.entry(113, "CIN"),
          Map.entry(114, "CLE"),
          Map.entry(115, "COL"),
          Map.entry(116, "DET"),
          Map.entry(117, "HOU"),
          Map.entry(118, "KC"),
          Map.entry(119, "LAD"),
          Map.entry(120, "WSH"),
          Map.entry(121, "NYM"),
          Map.entry(133, "ATH"),
          Map.entry(134, "PIT"),
          Map.entry(135, "SD"),
          Map.entry(136, "SEA"),
          Map.entry(137, "SF"),
          Map.entry(138, "STL"),
          Map.entry(139, "TB"),
          Map.entry(140, "TEX"),
          Map.entry(141, "TOR"),
          Map.entry(142, "MIN"),
          Map.entry(143, "PHI"),
          Map.entry(144, "ATL"),
          Map.entry(145, "CWS"),
          Map.entry(146, "MIA"),
          Map.entry(147, "NYY"),
          Map.entry(158, "MIL"));

  public List<MlbPlayer> parsePlayers(String json) throws IOException {
    JsonNode root = mapper.readTree(json);
    List<MlbPlayer> players = new ArrayList<>();
    for (JsonNode p : root.path("people")) {
      long id = p.path("id").asLong(0);
      String name = textOrNull(p.path("fullName"));
      if (id <= 0 || name == null || name.isBlank()) {
        continue;
      }
      String team = TEAM_ABBR.getOrDefault(p.path("currentTeam").path("id").asInt(0), "");
      players.add(
          new MlbPlayer(
              id,
              name,
              clamp(p.path("primaryPosition").path("abbreviation"), 2),
              clamp(p.path("batSide").path("code"), 1),
              clamp(p.path("pitchHand").path("code"), 1),
              p.path("active").asBoolean(false),
              team));
    }
    return players;
  }

  private static String clamp(JsonNode n, int maxLen) {
    String s = textOrNull(n);
    if (s == null) {
      return "";
    }
    return s.length() <= maxLen ? s : s.substring(0, maxLen);
  }

  /**
   * Extract the pre-pitch context for the pitch about to be thrown from {@code currentPlay}
   * (decision [143] predict-next). Returns null when there is no pitch to predict - currentPlay
   * missing, or the at-bat is already complete (between at-bats, or the game is final).
   */
  private static LiveNextPitch parseNextPitch(
      JsonNode root, long gamePk, LocalDate gameDate, String parkId) {
    JsonNode cp = root.path("liveData").path("plays").path("currentPlay");
    if (cp.isMissingNode() || cp.path("about").path("isComplete").asBoolean(false)) {
      return null;
    }
    JsonNode about = cp.path("about");
    JsonNode matchup = cp.path("matchup");
    JsonNode count = cp.path("count");
    int pitchesSoFar = 0;
    for (JsonNode e : cp.path("playEvents")) {
      if (e.path("isPitch").asBoolean()) {
        pitchesSoFar++;
      }
    }
    return new LiveNextPitch(
        gamePk,
        about.path("atBatIndex").asInt(),
        pitchesSoFar + 1,
        about.path("inning").asInt(),
        about.path("isTopInning").asBoolean(),
        matchup.path("pitcher").path("id").asLong(),
        matchup.path("batter").path("id").asLong(),
        textOrNull(matchup.path("pitchHand").path("code")),
        textOrNull(matchup.path("batSide").path("code")),
        count.path("balls").asInt(),
        count.path("strikes").asInt(),
        count.path("outs").asInt(),
        isOccupied(matchup.path("postOnFirst")),
        isOccupied(matchup.path("postOnSecond")),
        isOccupied(matchup.path("postOnThird")),
        parkId,
        gameDate);
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
