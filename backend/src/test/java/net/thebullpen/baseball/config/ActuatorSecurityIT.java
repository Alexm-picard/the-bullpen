package net.thebullpen.baseball.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M5 - the metrics surface ({@code /actuator/prometheus}, {@code /actuator/metrics}) leaks queue
 * depths, per-model prediction counts, and JVM internals, so it must require ADMIN auth while the
 * liveness/readiness probes ({@code /actuator/health}, {@code /actuator/info}) stay public for the
 * Cloudflare / Uptime Robot checks.
 *
 * <p>Drives the real Spring Security filter chain via MockMvc. Non-Docker (no ClickHouse), runs in
 * the normal test lane.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"api", "registry-controller-it"})
class ActuatorSecurityIT {

  private static final String ADMIN_USER = "it-admin";
  private static final String ADMIN_PASS = "it-password";

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-actuator-sec-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    registry.add("bullpen.admin.basicauth", () -> ADMIN_USER + ":" + ADMIN_PASS);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-actuator-sec-it-snap-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private MockMvc mvc;

  @Test
  void health_isPublic() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void prometheus_requiresAuth() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
  }

  @Test
  void metrics_requiresAuth() throws Exception {
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }

  @Test
  void metrics_servedWithAdminCredentials() throws Exception {
    mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, basicAuth()))
        .andExpect(status().isOk());
  }

  private static String basicAuth() {
    String token = ADMIN_USER + ":" + ADMIN_PASS;
    return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
  }
}
