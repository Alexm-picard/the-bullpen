package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Pins the CORS allowed-headers against the custom headers the frontend sends. The genus: parks.ts
 * grew X-Bullpen-Game-Id + X-Bullpen-Park-Id (#424), the CORS config didn't know, and the browser's
 * preflight silently rejected every game-page batted-ball request while curl and /parks worked
 * fine.
 *
 * <p>If you add a custom header on the frontend, add it to BOTH the CorsConfig allowed-headers AND
 * this test's FRONTEND_CUSTOM_HEADERS list. The test failing is the signal that you forgot one
 * side.
 *
 * <p>Frontend sources: {@code frontend/src/api/parks.ts} (X-Bullpen-Game-Id, X-Bullpen-Park-Id).
 */
@SpringBootTest
@ActiveProfiles("api")
class CorsAllowedHeadersTest {

  private static final List<String> FRONTEND_CUSTOM_HEADERS =
      List.of("X-Bullpen-Game-Id", "X-Bullpen-Park-Id");

  @Autowired private WebMvcConfigurer corsConfigurer;

  @Test
  void corsAllowedHeadersIncludeAllFrontendCustomHeaders() {
    CorsRegistry registry = new CorsRegistry();
    corsConfigurer.addCorsMappings(registry);
    List<String> allowedHeaders = extractAllowedHeaders(registry);

    for (String header : FRONTEND_CUSTOM_HEADERS) {
      assertThat(allowedHeaders)
          .as(
              "CORS allowed-headers must include %s (sent by the frontend)."
                  + " Add it to CorsConfig.allowedHeaders and re-run.",
              header)
          .anyMatch(h -> h.equalsIgnoreCase(header));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractAllowedHeaders(CorsRegistry registry) {
    try {
      var method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
      method.setAccessible(true);
      var configs =
          (java.util.Map<String, org.springframework.web.cors.CorsConfiguration>)
              method.invoke(registry);
      return configs.values().stream()
          .flatMap(config -> config.getAllowedHeaders().stream())
          .toList();
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("could not read CorsRegistry configurations", e);
    }
  }
}
