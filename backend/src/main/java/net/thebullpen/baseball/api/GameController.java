package net.thebullpen.baseball.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import net.thebullpen.baseball.api.dto.PostPredictionsPage;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.GameSummary;
import net.thebullpen.baseball.domain.LivePitchRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Live-game HTTP surface (leaf 4d.1). Three endpoints:
 *
 * <ul>
 *   <li>{@code GET /v1/games/today} — list today's games (ET, since the season runs in ET).
 *   <li>{@code GET /v1/games/{id}} — single game summary.
 *   <li>{@code GET /v1/games/{id}/pitches?since=<cursor>} — delta of pitches added after cursor.
 *   <li>{@code GET /v1/games/{id}/post-predictions?page=&size=} - paginated retrospective of the
 *       logged {@code pitch_outcome_post} champion predictions joined to each pitch's realized
 *       outcome (F2.1b, backs decision [177]'s panel).
 * </ul>
 *
 * <p>Backed by {@link LivePitchesRepository} reading {@code pitches_live} (V015). Until the
 * worker-side polling lands the table is empty in dev/CI — endpoints return empty lists rather than
 * failing, so the frontend's loading / empty states are exercised correctly.
 *
 * <p>Same {@code bullpen.clickhouse.enabled} gate as {@link PlayerController} — the controller
 * doesn't materialise when CH isn't wired.
 */
@RestController
@RequestMapping("/v1/games")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class GameController {

  private static final ZoneId ET = ZoneId.of("America/New_York");
  private static final int PITCHES_SINCE_MIN = 0;
  private static final int POST_PREDICTIONS_MIN_SIZE = 1;
  private static final int POST_PREDICTIONS_MAX_SIZE = 200;

  /** Balls per team behind each profile. Bounded so one page view is not a season-wide scan. */
  private static final int TEAM_CONTACT_LIMIT = 250;

  private final LivePitchesRepository repo;
  private final TeamContactAggregator aggregator;

  public GameController(LivePitchesRepository repo, TeamContactAggregator aggregator) {
    this.aggregator = aggregator;
    this.repo = repo;
  }

  @GetMapping("/today")
  public List<GameSummary> today() {
    return repo.findGamesForDate(LocalDate.now(ET));
  }

  @GetMapping("/{id}")
  public GameSummary get(@PathVariable("id") long id) {
    return repo.findGame(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "game not found: " + id));
  }

  @GetMapping("/{id}/pitches")
  public List<LivePitchRow> pitchesSince(
      @PathVariable("id") long id, @RequestParam(name = "since", defaultValue = "0") long since) {
    if (since < PITCHES_SINCE_MIN) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "since must be >= " + PITCHES_SINCE_MIN);
    }
    return repo.findPitchesSince(id, since);
  }

  /**
   * Season-to-date contact comparison for a game's two teams at this park - what the batted-ball
   * card shows BEFORE any ball has been put in play.
   *
   * <p>Its own endpoint rather than a field on the game summary, because it is expensive (a
   * season-wide scan plus N inferences per team) and the summary is read on every poll of every
   * game. The card calls this only when it has no batted ball to show.
   *
   * <p>Per decision [188] / ADR-0016 this writes NOTHING to {@code prediction_log}: hundreds of
   * evaluations collapse into two displayed numbers, so the individuals are intermediates. They are
   * observable instead through the {@code thebullpen_inference_aggregate_*} metrics.
   *
   * <p>Returns 404 when the game is unknown. A team with no profileable contact yields a null
   * profile rather than a zero - a team we cannot profile has no answer, and 0.0 would read as
   * "never homers" on a card whose job is being honest about what it knows.
   */
  @GetMapping("/{id}/team-contact")
  public TeamContactResponse teamContact(@PathVariable("id") long id) {
    GameSummary game =
        repo.findGame(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "game not found: " + id));
    LocalDate from = game.gameDate().withDayOfYear(1);
    return new TeamContactResponse(
        game.homeTeam(),
        aggregator.profile(game.homeTeam(), game.homeTeam(), from, TEAM_CONTACT_LIMIT),
        game.awayTeam(),
        aggregator.profile(game.awayTeam(), game.homeTeam(), from, TEAM_CONTACT_LIMIT),
        from);
  }

  /**
   * @param parkTeam the HOME team, which is the park id by project convention - both profiles are
   *     scored at the same park, since the comparison is about contact quality here.
   */
  public record TeamContactResponse(
      String homeTeam,
      TeamContactAggregator.TeamHrProfile home,
      String awayTeam,
      TeamContactAggregator.TeamHrProfile away,
      LocalDate since) {}

  @GetMapping("/{id}/post-predictions")
  public PostPredictionsPage postPredictions(
      @PathVariable("id") long id,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "50") int size) {
    if (page < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
    }
    if (size < POST_PREDICTIONS_MIN_SIZE || size > POST_PREDICTIONS_MAX_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "size must be between "
              + POST_PREDICTIONS_MIN_SIZE
              + " and "
              + POST_PREDICTIONS_MAX_SIZE);
    }
    var preds = repo.findPostPredictions(id, page, size);
    return new PostPredictionsPage(preds.rows(), page, size, preds.hasNext());
  }
}
