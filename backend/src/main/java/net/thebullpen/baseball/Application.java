package net.thebullpen.baseball;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point. {@link EnableScheduling} is needed for the worker-profile
 * {@code @Scheduled} jobs (3a.5 {@code ReconciliationJob}, future 3c drift jobs). The api profile
 * doesn't have any scheduled methods today; the annotation is a no-op for it.
 *
 * <p>{@link ConfigurationPropertiesScan} auto-registers the {@code @ConfigurationProperties}
 * records under {@code config/} (e.g. {@code IngestProperties}) so the {@code bullpen.*} namespace
 * binds to typed, validated records instead of scattered {@code @Value} injections (Wave E / M-task
 * 26).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
