package net.thebullpen.baseball.api;

import java.util.List;
import net.thebullpen.baseball.api.dto.PlayerPredictionRow;
import net.thebullpen.baseball.api.dto.PlayerSearchResult;
import net.thebullpen.baseball.data.PlayerPredictionsRepository;
import net.thebullpen.baseball.data.PlayerRepository;
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
 * Player lookup HTTP surface (leaf 4b.1).
 *
 * <ul>
 *   <li>{@code GET /v1/players/search?q=&limit=} — autocomplete-shaped results. {@code q} is 1–50
 *       chars; {@code limit} ∈ [1, 50] with a default of 10.
 *   <li>{@code GET /v1/players/{id}} — single profile row, used by leaf 4b.2.
 * </ul>
 *
 * <p>Input bounds are enforced inline (not via class-level {@code @Validated}) so the same checks
 * fire under both {@code MockMvc.standaloneSetup} and the real Spring context — class-level
 * validation needs {@code MethodValidationPostProcessor} which standalone setup doesn't install.
 *
 * <p>{@link ConditionalOnBean} keeps the controller out when {@link PlayerRepository} isn't present
 * (no ClickHouse wired in dev). The frontend's {@code usePlayerSearch} hook surfaces the
 * 404-on-route as "search unavailable" rather than treating it as a per-request failure.
 */
@RestController
@RequestMapping("/v1/players")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PlayerController {

  private static final int Q_MAX = 50;
  private static final int LIMIT_MIN = 1;
  private static final int LIMIT_MAX = 50;
  private static final int PREDICTIONS_LIMIT_MAX = 200;
  private static final int ROSTER_LIMIT_MAX = 100;

  private final PlayerRepository repo;
  private final PlayerPredictionsRepository predictions;

  public PlayerController(PlayerRepository repo, PlayerPredictionsRepository predictions) {
    this.repo = repo;
    this.predictions = predictions;
  }

  @GetMapping("/search")
  public List<PlayerSearchResult> search(
      @RequestParam("q") String q, @RequestParam(name = "limit", defaultValue = "10") int limit) {
    if (q == null || q.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must not be blank");
    }
    if (q.length() > Q_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must be ≤ " + Q_MAX + " chars");
    }
    if (limit < LIMIT_MIN || limit > LIMIT_MAX) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit must be in [" + LIMIT_MIN + ", " + LIMIT_MAX + "]");
    }
    return repo.search(q.trim(), limit);
  }

  /**
   * Active roster filtered by {@code team} and/or {@code position}, for the Browse surface. Both
   * params optional (the UI picks one facet at a time); {@code limit} in [1, 100], default 50. A
   * literal {@code /roster} path so it never collides with {@code /{id}}.
   */
  @GetMapping("/roster")
  public List<PlayerSearchResult> roster(
      @RequestParam(name = "team", required = false) String team,
      @RequestParam(name = "position", required = false) String position,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    if (limit < LIMIT_MIN || limit > ROSTER_LIMIT_MAX) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit must be in [" + LIMIT_MIN + ", " + ROSTER_LIMIT_MAX + "]");
    }
    return repo.roster(team, position, limit);
  }

  @GetMapping("/{id}")
  public PlayerSearchResult get(@PathVariable("id") long id) {
    return repo.findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "player not found: " + id));
  }

  /**
   * Recent predictions involving this player (leaf 4b.2). {@code limit} ∈ [1, 200] default 50.
   * Returns 404 if the player itself doesn't exist (404-on-route vs empty list lets the UI tell "no
   * predictions yet" apart from "wrong id").
   */
  @GetMapping("/{id}/predictions")
  public List<PlayerPredictionRow> predictionsFor(
      @PathVariable("id") long id, @RequestParam(name = "limit", defaultValue = "50") int limit) {
    if (limit < 1 || limit > PREDICTIONS_LIMIT_MAX) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "limit must be in [1, " + PREDICTIONS_LIMIT_MAX + "]");
    }
    if (repo.findById(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "player not found: " + id);
    }
    return predictions.findRecentForPlayer(id, limit);
  }
}
