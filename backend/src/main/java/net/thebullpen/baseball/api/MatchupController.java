package net.thebullpen.baseball.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.thebullpen.baseball.api.dto.MatchupSummary;
import net.thebullpen.baseball.data.GameMatchupsRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matchups HTTP surface (Phase 3): {@code GET /v1/matchups/today} - the day's computed matchups
 * (lean + featured people + battle score), best battle first, with team context. The home Featured
 * panel reads row 0; the Tonight's board renders the rest. Empty until the morning job has run (the
 * frontend's empty state handles that). Same gating as {@link GameController}.
 */
@RestController
@RequestMapping("/v1/matchups")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class MatchupController {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  private final GameMatchupsRepository matchups;
  private final LivePitchesRepository slate;

  public MatchupController(GameMatchupsRepository matchups, LivePitchesRepository slate) {
    this.matchups = matchups;
    this.slate = slate;
  }

  @GetMapping("/today")
  public List<MatchupSummary> today() {
    LocalDate date = LocalDate.now(ET);
    Map<Long, ScheduledGame> byId =
        slate.findScheduledGames(date).stream()
            .collect(Collectors.toMap(ScheduledGame::gamePk, Function.identity(), (a, b) -> a));
    return matchups.findForDate(date).stream()
        .map(m -> toSummary(m, byId.get(m.gameId())))
        .toList();
  }

  private static MatchupSummary toSummary(GameMatchup m, ScheduledGame sg) {
    String home = sg == null ? "" : teamLabel(sg.homeAbbr(), sg.homeName());
    String away = sg == null ? "" : teamLabel(sg.awayAbbr(), sg.awayName());
    return new MatchupSummary(
        m.gameId(),
        m.gameDate(),
        sg == null ? null : sg.gameTimeUtc(),
        home,
        away,
        m.lean(),
        m.homePlayerId(),
        m.homePlayerName(),
        m.homeRole(),
        m.awayPlayerId(),
        m.awayPlayerName(),
        m.awayRole(),
        m.battleScore(),
        m.stage());
  }

  /**
   * Prefer the abbreviation (BOS); fall back to the full name when the schedule wasn't hydrated.
   */
  private static String teamLabel(String abbr, String name) {
    return (abbr != null && !abbr.isEmpty()) ? abbr : (name == null ? "" : name);
  }
}
