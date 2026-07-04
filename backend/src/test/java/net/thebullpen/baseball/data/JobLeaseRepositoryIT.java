package net.thebullpen.baseball.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
 * IT for {@link JobLeaseRepository} (D-37). Proves the renewable single-owner heartbeat lease over
 * the real {@code job_leases} table (V019, applied by Flyway into a per-test temp SQLite): one
 * owner holds the lease while fresh, renews it, and another owner takes over only after the lease
 * goes stale - with exactly one holder at every point, including after failover.
 *
 * <p>Staleness is forced deterministically by back-dating {@code heartbeat_at} via the injected
 * {@link JdbcTemplate} (no {@code Thread.sleep}). CH-free by design (no Testcontainers, no docker
 * gate) so it runs on every CI pass. Mirrors {@code JobLockRepositoryIT}'s wiring:
 * {@code @SpringBootTest} + {@code registry-it} profile + a {@code @DynamicPropertySource}
 * temp-file SQLite URL.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class JobLeaseRepositoryIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-joblease-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-joblease-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private JobLeaseRepository jobLease;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM job_leases");
  }

  @Test
  void holder_renews_while_fresh_and_others_are_refused() {
    // A acquires the unheld lease.
    assertThat(jobLease.tryAcquireOrRenew("lp", "A", 30)).isTrue();
    // B is refused while A's lease is fresh.
    assertThat(jobLease.tryAcquireOrRenew("lp", "B", 30)).isFalse();
    // A renews its own fresh lease.
    assertThat(jobLease.tryAcquireOrRenew("lp", "A", 30)).isTrue();
    // B is still refused.
    assertThat(jobLease.tryAcquireOrRenew("lp", "B", 30)).isFalse();
  }

  @Test
  void a_stale_lease_fails_over_to_a_new_owner_with_exactly_one_holder() {
    // A holds a fresh lease; B is refused.
    assertThat(jobLease.tryAcquireOrRenew("lp", "A", 30)).isTrue();
    assertThat(jobLease.tryAcquireOrRenew("lp", "B", 30)).isFalse();

    // Simulate A crashing / pausing: back-date its heartbeat past the 30s staleness window.
    forceStale("lp", 31);

    // B takes over the stale lease.
    assertThat(jobLease.tryAcquireOrRenew("lp", "B", 30)).isTrue();
    // A is now refused: B holds a fresh lease, so there is exactly one holder after failover.
    assertThat(jobLease.tryAcquireOrRenew("lp", "A", 30)).isFalse();
    assertThat(currentOwner("lp")).isEqualTo("B");
  }

  @Test
  void concurrent_first_acquire_has_exactly_one_winner() throws Exception {
    int workers = 10;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    for (int i = 0; i < workers; i++) {
      String owner = "w" + i;
      pool.submit(
          () -> {
            try {
              start.await();
              if (jobLease.tryAcquireOrRenew("lp", owner, 30)) {
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
        .as("exactly one of %d concurrent first-acquirers must win the lease", workers)
        .isEqualTo(1);
  }

  private void forceStale(String jobName, int seconds) {
    int updated =
        jdbc.update(
            "UPDATE job_leases SET heartbeat_at = datetime('now', ?) WHERE job_name = ?",
            "-" + seconds + " seconds",
            jobName);
    assertThat(updated).as("forceStale must back-date an existing lease row").isEqualTo(1);
  }

  private String currentOwner(String jobName) {
    return jdbc.queryForObject(
        "SELECT owner FROM job_leases WHERE job_name = ?", String.class, jobName);
  }
}
