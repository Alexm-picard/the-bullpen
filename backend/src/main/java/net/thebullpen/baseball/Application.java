package net.thebullpen.baseball;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point. {@link EnableScheduling} is needed for the worker-profile
 * {@code @Scheduled} jobs (3a.5 {@code ReconciliationJob}, future 3c drift jobs). The api profile
 * doesn't have any scheduled methods today; the annotation is a no-op for it.
 */
@SpringBootApplication
@EnableScheduling
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
