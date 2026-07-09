package net.thebullpen.baseball.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import net.thebullpen.baseball.api.dto.PostPredictionsPage;
import net.thebullpen.baseball.data.LivePitchesRepository;
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

  private final LivePitchesRepository repo;

  public GameController(LivePitchesRepository repo) {
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
    return repo.findPostPredictions(id, page, size);
  }
}
