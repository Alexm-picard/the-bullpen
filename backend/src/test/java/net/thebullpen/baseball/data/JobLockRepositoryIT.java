package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT for {@link JobLockRepository} (D-36). Proves the at-most-once-per-ET-day contract over the
 * real {@code job_locks} table (V017, applied by Flyway into a per-test temp SQLite): 10 concurrent
 * claimers of the same (job, fire_date) yield exactly one winner, and a different fire_date or
 * job_name claims independently.
 *
 * <p>CH-free by design (no Testcontainers, no docker gate) so it runs on every CI pass. Mirrors
 * {@code RetrainingQueueServiceIT}'s wiring: {@code @SpringBootTest} + {@code registry-it} profile
 * + a {@code @DynamicPropertySource} temp-file SQLite URL.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class JobLockRepositoryIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-joblock-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-joblock-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private JobLockRepository jobLocks;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM job_locks");
  }

  @Test
  void concurrent_acquire_of_same_job_and_date_has_exactly_one_winner() throws Exception {
    LocalDate fireDate = LocalDate.of(2026, 7, 4);
    int workers = 10;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    for (int i = 0; i < workers; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              if (jobLocks.tryAcquire("job_x", fireDate)) {
                winners.incrementAndGet();
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
    }
    start.countDown();
    pool.shutdown();
    boolean done = pool.awaitTermination(5, TimeUnit.SECONDS);
    assertThat(done).isTrue();
    assertThat(winners.get())
        .as("exactly one of %d concurrent claimers must win (job_x, %s)", workers, fireDate)
        .isEqualTo(1);
  }

  @Test
  void a_different_fire_date_or_job_name_claims_independently() {
    LocalDate day1 = LocalDate.of(2026, 7, 4);
    LocalDate day2 = LocalDate.of(2026, 7, 5);

    // First claim of (job_x, day1) wins; a second claim of the same pair loses.
    assertThat(jobLocks.tryAcquire("job_x", day1)).isTrue();
    assertThat(jobLocks.tryAcquire("job_x", day1)).isFalse();

    // A different fire_date for the same job is not blocked by the first claim.
    assertThat(jobLocks.tryAcquire("job_x", day2)).isTrue();

    // A different job_name for the same fire_date is not blocked either.
    assertThat(jobLocks.tryAcquire("job_y", day1)).isTrue();
  }
}
