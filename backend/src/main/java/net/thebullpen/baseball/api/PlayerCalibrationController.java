package net.thebullpen.baseball.api;

import java.util.List;
import java.util.Set;
import net.thebullpen.baseball.data.CalibrationRepository;
import net.thebullpen.baseball.data.PlayerRepository;
import net.thebullpen.baseball.domain.CalibrationBin;
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
 * Per-player reliability-diagram endpoint (leaf 4b.3).
 *
 * <p>{@code GET /v1/players/{id}/calibration?model=pitch_outcome_pre|post|batted_ball} returns up
 * to {@link CalibrationRepository#BIN_COUNT} bins. 404 if the player itself doesn't exist; 400 if
 * the {@code model} param is missing or not in the allow-list.
 *
 * <p>Lives as its own controller (vs hanging off {@link PlayerController}) so the reliability
 * domain has a single home — Phase 4e.5 adds an aggregate per-model calibration endpoint that
 * shares this repository.
 */
@RestController
@RequestMapping("/v1/players")
@Profile("api")
@ConditionalOnProperty(name = "bullpen.clickhouse.enabled", havingValue = "true")
public class PlayerCalibrationController {

  /**
   * Allow-list of legal {@code model} values — the two pitch heads from 2a/2b and the batted-ball
   * head from 1.3/2c. Keeps the SQL injection surface to zero and rejects typos at the boundary.
   */
  private static final Set<String> ALLOWED_MODELS =
      Set.of("pitch_outcome_pre", "pitch_outcome_post", "batted_ball", "_toy_batted_ball");

  private final PlayerRepository players;
  private final CalibrationRepository calibration;

  public PlayerCalibrationController(PlayerRepository players, CalibrationRepository calibration) {
    this.players = players;
    this.calibration = calibration;
  }

  @GetMapping("/{id}/calibration")
  public List<CalibrationBin> get(
      @PathVariable("id") long id, @RequestParam("model") String model) {
    if (model == null || model.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model must not be blank");
    }
    if (!ALLOWED_MODELS.contains(model)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unknown model: " + model + " (allowed: " + ALLOWED_MODELS + ")");
    }
    if (players.findById(id).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "player not found: " + id);
    }
    return calibration.computePlayerBins(model, id);
  }
}
