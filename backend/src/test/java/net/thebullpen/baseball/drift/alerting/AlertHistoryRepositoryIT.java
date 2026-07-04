package net.thebullpen.baseball.drift.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT for {@link AlertHistoryRepository} D-36 Part B defense-in-depth dedup. Runs the real repo
 * against a per-test temp-file SQLite (V018's {@code ux_ah_key_day} unique index applied by Flyway
 * on boot), CH-free so it runs every CI pass (no Testcontainers / docker gate). Asserts that N
 * workers racing {@code record(...)} for the same (alert_key, UTC day) collapse to exactly one row
 * and all observe the same id, while a different key inserts independently.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class AlertHistoryRepositoryIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-alerthist-it-" + UUID.randomUUID() + ".sqlite");
    // busy_timeout so the concurrent writers wait for the single-writer lock instead of failing
    // fast with SQLITE_BUSY (mirrors the production application.yml datasource URL).
    String url = "jdbc:sqlite:" + dbPath + "?foreign_keys=true&busy_timeout=5000";
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-alerthist-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private AlertHistoryRepository repo;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM alert_history");
  }

  @Test
  void concurrent_record_for_same_key_and_day_collapses_to_one_row_and_one_id() throws Exception {
    String key = "drift/model_a/calibration_error/all";
    int workers = 10;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(workers);
    AtomicReferenceArray<Long> ids = new AtomicReferenceArray<>(workers);
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      for (int i = 0; i < workers; i++) {
        int idx = i;
        pool.submit(
            () -> {
              try {
                start.await();
                ids.set(idx, repo.record(key, AlertSeverity.PAGE, 0.15, 0.10, "race " + idx));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(10, TimeUnit.SECONDS)).as("all workers finished").isTrue();
    } finally {
      pool.shutdownNow();
    }

    // Exactly one physical row survives the INSERT OR IGNORE + ux_ah_key_day unique index.
    assertThat(repo.countFor(key)).as("same (key, day) collapses to one row").isEqualTo(1L);

    // Every worker observed the same, valid id (the surviving row), whether it won the INSERT or
    // lost the race and re-selected the existing row.
    Long expectedId =
        jdbc.queryForObject("SELECT id FROM alert_history WHERE alert_key = ?", Long.class, key);
    assertThat(expectedId).isNotNull();
    for (int i = 0; i < workers; i++) {
      assertThat(ids.get(i))
          .as("worker %d must observe the surviving row id, not -1 or a phantom id", i)
          .isEqualTo(expectedId);
    }
  }

  @Test
  void record_for_a_different_key_inserts_independently_with_a_distinct_id() {
    String keyA = "drift/model_a/calibration_error/all";
    String keyB = "drift/model_b/psi_feature/launch_speed";

    long idA = repo.record(keyA, AlertSeverity.PAGE, 0.15, 0.10, "a");
    long idB = repo.record(keyB, AlertSeverity.NOTICE, 0.30, 0.25, "b");

    assertThat(idA).isPositive();
    assertThat(idB).isPositive();
    assertThat(idB).as("distinct alert keys get distinct rows").isNotEqualTo(idA);
    assertThat(repo.countFor(keyA)).isEqualTo(1L);
    assertThat(repo.countFor(keyB)).isEqualTo(1L);

    Long total = jdbc.queryForObject("SELECT COUNT(*) FROM alert_history", Long.class);
    assertThat(total).isEqualTo(2L);
  }

  @Test
  void repeated_record_for_same_key_same_day_is_idempotent_and_returns_the_same_id() {
    String key = "drift/model_a/calibration_error/all";

    long first = repo.record(key, AlertSeverity.PAGE, 0.15, 0.10, "first");
    long second = repo.record(key, AlertSeverity.PAGE, 0.99, 0.10, "second-ignored");

    assertThat(first).isPositive();
    assertThat(second).as("second same-day record returns the existing id").isEqualTo(first);
    assertThat(repo.countFor(key)).as("no duplicate row inserted").isEqualTo(1L);

    // The first row's payload is the one that persisted (INSERT OR IGNORE dropped the second).
    List<String> details =
        jdbc.queryForList(
            "SELECT details FROM alert_history WHERE alert_key = ?", String.class, key);
    assertThat(details).containsExactly("first");
  }
}
