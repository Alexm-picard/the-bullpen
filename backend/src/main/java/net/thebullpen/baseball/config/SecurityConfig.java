package net.thebullpen.baseball.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authentication boundary for the Spring app — closes Risk Register G10 by splitting two URL
 * surfaces (decision [29] + leaf 3a.4):
 *
 * <ul>
 *   <li>{@code /v1/admin/**} — HTTP Basic, role {@code ADMIN}. Backs the registry write paths
 *       (register, promote, archive) and any future operator tooling.
 *   <li>everything else — public. Prediction APIs (decision [56] portfolio framing: no per-user
 *       auth), the public Ops read view at {@code /v1/ops/**}, the Actuator probes Cloudflare /
 *       Uptime Robot hit, and the static frontend assets.
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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            authz ->
                authz.requestMatchers("/v1/admin/**").hasRole("ADMIN").anyRequest().permitAll())
        .httpBasic(Customizer.withDefaults())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .build();
  }

  @Bean
  public UserDetailsService adminUser(@Value("${bullpen.admin.basicauth:}") String creds) {
    if (creds == null || creds.isBlank()) {
      throw new IllegalStateException(
          "bullpen.admin.basicauth is unset — set THEBULLPEN_ADMIN_BASIC_AUTH=<user>:<password>"
              + " in the runtime environment (or override @DynamicPropertySource in tests)");
    }
    String[] parts = creds.split(":", 2);
    if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
      throw new IllegalStateException(
          "bullpen.admin.basicauth must be in the form 'user:password' — got "
              + parts.length
              + " parts");
    }
    return new InMemoryUserDetailsManager(
        User.withUsername(parts[0]).password("{noop}" + parts[1]).roles("ADMIN").build());
  }
}
