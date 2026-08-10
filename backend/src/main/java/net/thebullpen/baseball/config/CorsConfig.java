package net.thebullpen.baseball.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React frontend on Vercel (production) and Vite dev server (local). The frontend is
 * the only browser client; the api profile is the only HTTP face. Worker profile binds a different
 * port and is not internet-facing.
 */
@Configuration
@Profile("api")
public class CorsConfig {

  @Value(
      "${bullpen.cors.allowed-origins:http://localhost:5173,https://thebullpen.net,https://www.thebullpen.net}")
  private String[] allowedOrigins;

  @Bean
  public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        registry
            .addMapping("/**")
            .allowedOrigins(allowedOrigins)
            // DELETE added for the admin routing UI's clear-challenger
            // (DELETE /v1/admin/routing/{name}/challenger).
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            // Explicit allow-list: Content-Type (JSON bodies), Authorization (admin UI),
            // X-Bullpen-Game-Id + X-Bullpen-Park-Id (park attribution per #424/ADR-0016).
            // If you add a custom header on the frontend, add it here too or the browser's
            // CORS preflight will reject the request (see frontend/src/api/parks.ts).
            .allowedHeaders(
                "Content-Type", "Authorization", "X-Bullpen-Game-Id", "X-Bullpen-Park-Id")
            .exposedHeaders("X-Correlation-Id")
            .maxAge(3600);
      }
    };
  }
}
