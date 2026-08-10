package net.thebullpen.baseball.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight {@code /health} endpoint distinct from Actuator's full health probe. Mounted on the
 * {@code api} profile only — the {@code worker} profile exposes Actuator on a separate port.
 */
@RestController
@Profile("api")
public class HealthController {

  // Map<String, String> (not Object) so the generated response schema is additionalProperties of
  // type string - all three values are strings, and an Object-valued map makes springdoc type them
  // as "object", which then fails Schemathesis response-schema conformance (C-33).
  @GetMapping("/health")
  public Map<String, String> health() {
    return Map.of(
        "status", "ok",
        "profile", "api",
        "ts", Instant.now().toString());
  }
}
