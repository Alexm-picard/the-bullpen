package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
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
  private static final String METRICS_USER = "it-metrics";
  private static final String METRICS_PASS = "it-metrics-pw";

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
    registry.add("bullpen.metrics.basicauth", () -> METRICS_USER + ":" + METRICS_PASS);
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

  // The Prometheus SCRAPE endpoint is not wired in this @SpringBootTest slice (it 404s here, though
  // it serves in prod), so these assert AUTHORIZATION - the METRICS/ADMIN roles pass the filter
  // chain (never 401/403) - which is exactly what the split controls. A denied role would be 403;
  // an
  // unauthenticated caller is still 401 (prometheus_requiresAuth). Endpoint availability is
  // orthogonal.
  @Test
  void prometheus_authorizedForMetricsRole() throws Exception {
    mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, metricsAuth()))
        .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  void prometheus_authorizedForAdminRole() throws Exception {
    mvc.perform(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, basicAuth()))
        .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotIn(401, 403));
  }

  @Test
  void metricsRole_cannotReachTheJvmEnvActuatorSurface() throws Exception {
    // The metrics-only identity may scrape /actuator/prometheus but NOT the rest of /actuator/**
    // (metrics/env/heapdump/loggers) - that is the whole point of the split.
    mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, metricsAuth()))
        .andExpect(status().isForbidden());
  }

  @Test
  void metricsRole_cannotReachTheAdminWriteSurface() throws Exception {
    // Security authorization runs before dispatch, so a wrong-role request to /v1/admin/** is 403
    // regardless of whether the exact path/method resolves to a handler.
    mvc.perform(get("/v1/admin/registry").header(HttpHeaders.AUTHORIZATION, metricsAuth()))
        .andExpect(status().isForbidden());
  }

  private static String basicAuth() {
    return basic(ADMIN_USER, ADMIN_PASS);
  }

  private static String metricsAuth() {
    return basic(METRICS_USER, METRICS_PASS);
  }

  private static String basic(String user, String pass) {
    String token = user + ":" + pass;
    return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
  }
}
