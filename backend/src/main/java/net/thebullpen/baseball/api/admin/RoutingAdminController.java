package net.thebullpen.baseball.api.admin;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import net.thebullpen.baseball.api.admin.dto.SetChallengerRequest;
import net.thebullpen.baseball.api.admin.dto.SetRoutingModeRequest;
import net.thebullpen.baseball.api.admin.dto.SetTrafficPctRequest;
import net.thebullpen.baseball.inference.routing.RoutingConfig;
import net.thebullpen.baseball.inference.routing.RoutingException;
import net.thebullpen.baseball.inference.routing.RoutingMode;
import net.thebullpen.baseball.inference.routing.RoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin HTTP surface for {@code model_routing} — gated by HTTP Basic via {@code SecurityConfig}'s
 * {@code /v1/admin/**} matcher (decision [29] / leaf 3a.4).
 *
 * <p>Four operations:
 *
 * <ul>
 *   <li>{@code GET /v1/admin/routing} — list every routing row (admin convenience for "what's
 *       deployed where").
 *   <li>{@code GET /v1/admin/routing/{modelName}} — one row.
 *   <li>{@code POST /v1/admin/routing/{modelName}/challenger} — set the challenger (must be at
 *       SHADOW stage). Setting clears {@code traffic_pct} to 0; flipping the slider is an explicit
 *       second step (deliberate friction so an admin can't accidentally cut over in one click).
 *   <li>{@code DELETE /v1/admin/routing/{modelName}/challenger} — clear the challenger slot.
 *   <li>{@code POST /v1/admin/routing/{modelName}/traffic-pct} — move the slider [0, 100]. Rejected
 *       with 400 when mode=SHADOW and pct > 0.
 *   <li>{@code POST /v1/admin/routing/{modelName}/mode} — flip SHADOW ↔ AB. SHADOW resets pct to 0
 *       automatically.
 * </ul>
 *
 * <p>Exception mapping (exhaustive over sealed {@link RoutingException}):
 *
 * <ul>
 *   <li>{@code UnknownModel} → 404
 *   <li>{@code ChallengerNotInShadow} / {@code ChallengerSameAsChampion} / {@code
 *       InvalidTrafficPct} / {@code ShadowModeWithTraffic} → 400
 * </ul>
 */
@Tag(
    name = "Admin: Routing",
    description =
        "ADMIN-authed A/B routing writes: set/clear challenger, move the traffic slider, and flip"
            + " SHADOW <-> AB mode. Requires HTTP Basic (SecurityConfig /v1/admin/**).")
@RestController
@RequestMapping("/v1/admin/routing")
@Profile("api")
public class RoutingAdminController {

  private static final Logger log = LoggerFactory.getLogger(RoutingAdminController.class);

  private final RoutingService routing;

  public RoutingAdminController(RoutingService routing) {
    this.routing = routing;
  }

  @GetMapping
  public List<RoutingConfig> listAll() {
    return routing.listAll();
  }

  @GetMapping("/{modelName}")
  public RoutingConfig get(@PathVariable String modelName) {
    try {
      return routing.getRouting(modelName);
    } catch (RoutingException.UnknownModel e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @PostMapping("/{modelName}/challenger")
  public RoutingConfig setChallenger(
      @PathVariable String modelName, @Valid @RequestBody SetChallengerRequest req) {
    try {
      RoutingConfig updated = routing.setChallenger(modelName, req.challengerVersionId());
      log.info(
          "admin: routing {} challenger set to {} (reason: {})",
          modelName,
          req.challengerVersionId(),
          req.reason());
      return updated;
    } catch (RoutingException.UnknownModel e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (RoutingException.ChallengerNotInShadow | RoutingException.ChallengerSameAsChampion e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @DeleteMapping("/{modelName}/challenger")
  public RoutingConfig clearChallenger(@PathVariable String modelName) {
    try {
      RoutingConfig updated = routing.clearChallenger(modelName);
      log.info("admin: routing {} challenger cleared", modelName);
      return updated;
    } catch (RoutingException.UnknownModel e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }

  @PostMapping("/{modelName}/traffic-pct")
  public RoutingConfig setTrafficPct(
      @PathVariable String modelName, @Valid @RequestBody SetTrafficPctRequest req) {
    try {
      RoutingConfig updated = routing.setTrafficPct(modelName, req.pct());
      log.info(
          "admin: routing {} traffic_pct set to {} (reason: {})",
          modelName,
          req.pct(),
          req.reason());
      return updated;
    } catch (RoutingException.UnknownModel e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    } catch (RoutingException.InvalidTrafficPct | RoutingException.ShadowModeWithTraffic e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
  }

  @PostMapping("/{modelName}/mode")
  public RoutingConfig setMode(
      @PathVariable String modelName, @Valid @RequestBody SetRoutingModeRequest req) {
    RoutingMode mode;
    try {
      mode = req.parseMode();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "unknown mode='" + req.mode() + "' — expected one of " + List.of(RoutingMode.values()),
          e);
    }
    try {
      RoutingConfig updated = routing.setMode(modelName, mode);
      log.info("admin: routing {} mode set to {} (reason: {})", modelName, mode, req.reason());
      return updated;
    } catch (RoutingException.UnknownModel e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }
  }
}
