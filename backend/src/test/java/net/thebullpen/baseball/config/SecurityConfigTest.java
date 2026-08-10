package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Plain-unit checks on the {@link SecurityConfig} user wiring (F3 METRICS split). The load-bearing
 * case is the DEPLOY-SAFE path: with the optional metrics credential unset the app must yield an
 * ADMIN-only manager and NOT throw, so the split can ship before the box provisions {@code
 * THEBULLPEN_METRICS_BASIC_AUTH}. No Spring context - the {@code @Bean} factory is called directly
 * with literal creds.
 */
class SecurityConfigTest {

  private final SecurityConfig config = new SecurityConfig();

  @Test
  void metricsCredentialUnset_yieldsAdminOnly_andDoesNotThrow() {
    InMemoryUserDetailsManager users =
        (InMemoryUserDetailsManager) config.userDetailsService("admin:pw", "");
    assertThat(users.userExists("admin")).isTrue();
    assertThat(users.userExists("metrics")).isFalse();
  }

  @Test
  void metricsCredentialSet_yieldsBothUsers() {
    InMemoryUserDetailsManager users =
        (InMemoryUserDetailsManager) config.userDetailsService("admin:pw", "scraper:spw");
    assertThat(users.userExists("admin")).isTrue();
    assertThat(users.userExists("scraper")).isTrue();
  }

  @Test
  void blankAdminCredential_failsLoud() {
    assertThatThrownBy(() -> config.userDetailsService("", "scraper:spw"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void malformedAdminCredential_failsLoud() {
    assertThatThrownBy(() -> config.userDetailsService("nocolon", ""))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void malformedMetricsCredential_failsLoud() {
    // A metrics value that is present but not user:password is a config error, not a silent skip.
    assertThatThrownBy(() -> config.userDetailsService("admin:pw", "nocolon"))
        .isInstanceOf(IllegalStateException.class);
  }
}
