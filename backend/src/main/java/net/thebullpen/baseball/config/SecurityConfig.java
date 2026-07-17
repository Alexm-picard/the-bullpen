package net.thebullpen.baseball.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Authentication boundary for the Spring app — closes Risk Register G10 by splitting two URL
 * surfaces (decision [29] + leaf 3a.4):
 *
 * <ul>
 *   <li>{@code /v1/admin/**} — HTTP Basic, role {@code ADMIN}. Backs the registry write paths
 *       (register, promote, archive) and any future operator tooling.
 *   <li>{@code /actuator/health/**} + {@code /actuator/info} — public. These are the probes
 *       Cloudflare / Uptime Robot hit; health uses {@code show-details: when-authorized} so no
 *       internals leak to an anonymous caller.
 *   <li>{@code /actuator/prometheus} - HTTP Basic, role {@code METRICS} or {@code ADMIN} (F3). The
 *       ONE actuator endpoint Prometheus scrapes; giving it a metrics-only role means a leaked
 *       scrape secret cannot reach the write / JVM surface below. Prometheus reads it via basic
 *       auth (see {@code infra/prometheus/prometheus.yml}); when {@code bullpen.metrics.basicauth}
 *       is unset the scrape falls back to the ADMIN credential ({@code mk-metrics-secrets.sh}).
 *   <li>{@code /actuator/**} (everything else: {@code metrics}, {@code env}, {@code loggers}, …) —
 *       HTTP Basic, role {@code ADMIN} (M5). These leak queue depths, per-model prediction counts,
 *       and JVM internals, so they must not be reachable unauthenticated from a public-fronted
 *       host. A Cloudflare edge deny on {@code /actuator/*} is the defense-in-depth layer (see
 *       {@code infra/cloudflared/config.yml.example}).
 *   <li>everything else — public. Prediction APIs (decision [56] portfolio framing: no per-user
 *       auth), the public Ops read view at {@code /v1/ops/**}, and the static frontend assets.
 * </ul>
 *
 * <p>CSRF is disabled because there are no cookies and no browser-form posts — the only writers are
 * the React admin pane (sends Authorization: Basic) and curl/scripts. A CSRF token would be pure
 * ceremony.
 *
 * <p>Credentials come from {@code bullpen.admin.basicauth} (env var {@code
 * THEBULLPEN_ADMIN_BASIC_AUTH} in prod, see {@code application.yml}) in the form {@code
 * "user:password"}. Tests override via {@code @DynamicPropertySource}; a missing value in prod
 * blows up at bean construction with a loud {@link IllegalStateException} rather than silently
 * accepting an empty password.
 *
 * <p>The {@code {noop}} prefix on the stored password is the Spring Security marker for plaintext
 * storage — fine here because the value lives in an env-var-backed secret store, never on disk, and
 * rotates via systemd EnvironmentFile reload. Switching to bcrypt would add a hashing step every
 * request for zero security benefit at this scale.
 *
 * <p>DELIBERATELY PUBLIC (M1 task 10): {@code /v3/api-docs} and {@code /swagger-ui} fall through to
 * the {@code anyRequest().permitAll()} rule ON PURPOSE - the OpenAPI spec and its explorer are part
 * of the portfolio's public API surface, not an oversight. The exposure is safe by construction:
 * every write path ({@code /v1/admin/**}) is ADMIN Basic-auth'd above, the non-health actuator
 * endpoints are ADMIN-gated, and the public predict/read endpoints the spec documents are
 * rate-limited per IP ({@code RateLimitFilter}). Reading the contract of an already public API
 * discloses nothing an attacker could not enumerate; hiding it would only cost the
 * reviewer/API-consumer experience. Batched for a decisions.md one-liner at the next /decide
 * sitting.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            authz ->
                authz
                    // Order matters (first match wins): keep the public liveness/readiness probes
                    // open, allow the Prometheus scrape endpoint for the METRICS role (or ADMIN),
                    // then gate every OTHER actuator endpoint (env, heapdump, loggers, metrics,
                    // ...)
                    // to ADMIN, then the admin write surface.
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/actuator/prometheus")
                    .hasAnyRole("METRICS", "ADMIN")
                    .requestMatchers("/actuator/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .permitAll())
        .httpBasic(Customizer.withDefaults())
        // Security headers. HSTS is emitted only on requests Spring sees as secure; behind the
        // Cloudflare Tunnel that means honoring X-Forwarded-Proto (enabled via
        // server.forward-headers-strategy in application.yml), so HSTS rides the forwarded https
        // scheme in prod and stays off plain-http localhost dev. nosniff + frame-DENY are Spring
        // Security defaults; Referrer-Policy is not a default, so it is set explicitly here.
        .headers(
            headers ->
                headers
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000L))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .build();
  }

  /**
   * Return 400 (not the default 500) when Spring Security's {@code StrictHttpFirewall} rejects a
   * malformed request — e.g. a header or path with control characters. A rejected request is a
   * client error, so a 4xx is correct; the default surfaces {@code RequestRejectedException} as a
   * 500 because it's thrown in the filter chain, outside MVC exception handling. (Found by the
   * Schemathesis contract job, S1f.)
   */
  @Bean
  public RequestRejectedHandler requestRejectedHandler() {
    return new HttpStatusRequestRejectedHandler();
  }

  @Bean
  public UserDetailsService userDetailsService(
      @Value("${bullpen.admin.basicauth:}") String adminCreds,
      @Value("${bullpen.metrics.basicauth:}") String metricsCreds) {
    List<UserDetails> users = new ArrayList<>();
    users.add(
        basicUser("bullpen.admin.basicauth", "THEBULLPEN_ADMIN_BASIC_AUTH", adminCreds, "ADMIN"));
    // Optional metrics-only identity: present -> Prometheus can scrape /actuator/prometheus without
    // the ADMIN credential; absent -> the scrape keeps using ADMIN (mk-metrics-secrets.sh falls
    // back), so this is safe to ship before the box provisions THEBULLPEN_METRICS_BASIC_AUTH.
    if (metricsCreds != null && !metricsCreds.isBlank()) {
      users.add(
          basicUser(
              "bullpen.metrics.basicauth",
              "THEBULLPEN_METRICS_BASIC_AUTH",
              metricsCreds,
              "METRICS"));
    }
    return new InMemoryUserDetailsManager(users);
  }

  /**
   * Parse a {@code "user:password"} credential into a {@code {noop}}-plaintext {@link UserDetails}
   * with the given role. Split on the FIRST colon only, so a password may itself contain colons.
   * Fails loud (rather than accepting an empty password) if the value is blank or malformed.
   */
  private static UserDetails basicUser(String prop, String envVar, String creds, String role) {
    if (creds == null || creds.isBlank()) {
      throw new IllegalStateException(
          prop
              + " is unset - set "
              + envVar
              + "=<user>:<password> in the runtime environment (or override @DynamicPropertySource"
              + " in tests)");
    }
    String[] parts = creds.split(":", 2);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw new IllegalStateException(
          prop + " must be in the form 'user:password' - got " + parts.length + " parts");
    }
    return User.withUsername(parts[0]).password("{noop}" + parts[1]).roles(role).build();
  }
}
