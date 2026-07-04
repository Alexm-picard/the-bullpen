package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.thebullpen.baseball.Application;
import net.thebullpen.baseball.data.JobLeaseRepository;
import net.thebullpen.baseball.data.JobLockRepository;
import net.thebullpen.baseball.drift.alerting.AlertHistoryRepository;
import net.thebullpen.baseball.drift.alerting.AlertSeverity;
import net.thebullpen.baseball.drift.jobs.PsiFeatureJob;
import net.thebullpen.baseball.retraining.RetrainingQueueService;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-39 (PR-D5) worker-pair two-instance IT: proves the {@code worker} profile is safe-to-duplicate.
 * Boots TWO real worker contexts on ONE shared temp SQLite (the registry / lock store) plus ONE
 * shared ClickHouse container (the analytical store), then asserts the five cross-instance
 * single-winner guarantees the scale-ready worker rests on:
 *
 * <ul>
 *   <li>(1) at most one instance wins a {@code job_locks} row per (job, ET fire date) - the D-36
 *       guard that fronts the six non-idempotent drift/reconciliation jobs;
 *   <li>(2) at most one instance holds the singleton {@code live_polling} lease - the D-37 lease
 *       that keeps a single MLB-API poller across a duplicated worker;
 *   <li>(3) two instances recording the same alert on the same UTC day collapse to one {@code
 *       alert_history} row - the D-36 Part B {@code ux_ah_key_day} defense-in-depth;
 *   <li>(4) the REAL {@link PsiFeatureJob} {@code @Scheduled} entrypoint, fired concurrently on
 *       both instances for the same fire date, executes its body on exactly one - the loser skips
 *       at the lock; and
 *   <li>(5) a single queued retrain is claimed by exactly one instance - the atomic {@code UPDATE
 *       ... WHERE status='QUEUED'} claim across two connection pools.
 * </ul>
 *
 * <p>These primitives are the shared registry-SQLite concurrency machinery a duplicated worker
 * relies on; the api half (stateless serving + routing convergence) is proven CH-free on every CI
 * pass by {@code ApiPairTwoInstanceIT}.
 *
 * <p>Docker-gated ({@code -Dbullpen.it.docker=true}) like the other ClickHouse ITs - the worker
 * context needs the CH datasource to construct its drift beans. The boot is kept hermetic: live
 * ingest is off by default (so {@code LivePollingService} is absent) and the players
 * {@code @ApplicationReadyEvent} backfill is disabled, so no instance reaches the MLB Stats API.
 * Contexts boot SEQUENTIALLY (A migrates the fresh SQLite + creates the CH schema, B validates) to
 * avoid a concurrent-writer {@code SQLITE_BUSY} on the single-writer file, mirroring the api-pair.
 */
@Testcontainers
@EnabledIfSystemProperty(
    named = "bullpen.it.docker",
    matches = "true",
    disabledReason =
        "Docker Desktop on macOS returns malformed /info responses to Testcontainers"
            + "; set -Dbullpen.it.docker=true to force-run in CI.")
class WorkerPairTwoInstanceIT {

  private static final ZoneId ET = ZoneId.of("America/New_York");

  @Container
  static final ClickHouseContainer CH =
      new ClickHouseContainer("clickhouse/clickhouse-server:24.12-alpine")
          .withUsername("default")
          .withPassword("test");

  private static ConfigurableApplicationContext ctxA;
  private static ConfigurableApplicationContext ctxB;
  private static Path dbFile;

  @BeforeAll
  static void bootPair() {
    dbFile =
        Path.of(
            System.getProperty("java.io.tmpdir"), "bullpen-d39b-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbFile + "?foreign_keys=true&busy_timeout=5000";
    ctxA = boot(url, "A");
    ctxB = boot(url, "B");
  }

  private static ConfigurableApplicationContext boot(String sqliteUrl, String tag) {
    Path snapshot =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-d39b-snap-" + tag + "-" + UUID.randomUUID());
    // Command-line args are highest precedence - .properties() would be out-precedenced by
    // application-worker.yml for keys like server.port / spring.datasource.url.
    return new SpringApplicationBuilder(Application.class)
        .run(
            "--spring.profiles.active=worker",
            "--server.port=0", // random port per instance - no 8081 clash, no HTTP needed here
            "--bullpen.clickhouse.enabled=true",
            "--bullpen.clickhouse.url=" + CH.getJdbcUrl(),
            "--bullpen.clickhouse.user=" + CH.getUsername(),
            "--bullpen.clickhouse.password=" + CH.getPassword(),
            "--spring.datasource.url=" + sqliteUrl,
            "--spring.flyway.url=" + sqliteUrl,
            // No MLB-Stats-API startup backfill (PlayersRefreshJob @ApplicationReadyEvent). The
            // live
            // poller AND the matchup lineup refresh are both gated on bullpen.ingest.live.enabled
            // (off, unset here), so both beans are absent; every other worker @Scheduled job is
            // cron-based (2-4 AM ET / weekly), so nothing reaches the network at boot.
            "--bullpen.ingest.players.enabled=false",
            "--bullpen.snapshot.local-base-path=" + snapshot);
  }

  @AfterAll
  static void shutdown() throws Exception {
    if (ctxA != null) {
      ctxA.close();
    }
    if (ctxB != null) {
      ctxB.close();
    }
    if (dbFile != null) {
      Files.deleteIfExists(dbFile);
    }
  }

  @Test
  void exactlyOneWorkerAcquiresAJobLockForAFireDate() throws Exception {
    JobLockRepository lockA = ctxA.getBean(JobLockRepository.class);
    JobLockRepository lockB = ctxB.getBean(JobLockRepository.class);
    // Synthetic job name + fixed past date: isolated from the real psi_feature row test 4 writes.
    LocalDate fireDate = LocalDate.of(2026, 2, 2);
    int winners =
        countWinners(
            () -> lockA.tryAcquire("d39b_lock_race", fireDate),
            () -> lockB.tryAcquire("d39b_lock_race", fireDate));
    assertThat(winners)
        .as("exactly one worker instance may hold a (job, fire_date) lock")
        .isEqualTo(1);
    assertThat(lockRows("d39b_lock_race", fireDate)).isEqualTo(1);
  }

  @Test
  void exactlyOneWorkerHoldsTheLivePollLease() throws Exception {
    JobLeaseRepository leaseA = ctxA.getBean(JobLeaseRepository.class);
    JobLeaseRepository leaseB = ctxB.getBean(JobLeaseRepository.class);
    String ownerA = "A-" + UUID.randomUUID();
    String ownerB = "B-" + UUID.randomUUID();
    int held =
        countWinners(
            () -> leaseA.tryAcquireOrRenew("live_polling", ownerA, 30),
            () -> leaseB.tryAcquireOrRenew("live_polling", ownerB, 30));
    assertThat(held).as("only one worker may hold the singleton live-poll lease").isEqualTo(1);
    String owner =
        ctxA.getBean(JdbcTemplate.class)
            .queryForObject(
                "SELECT owner FROM job_leases WHERE job_name = 'live_polling'", String.class);
    assertThat(owner).isIn(ownerA, ownerB);
  }

  @Test
  void duplicateAlertAcrossWorkersCollapsesToOneRow() {
    AlertHistoryRepository ahA = ctxA.getBean(AlertHistoryRepository.class);
    AlertHistoryRepository ahB = ctxB.getBean(AlertHistoryRepository.class);
    String key = "d39b_dup_alert";
    long idA = ahA.record(key, AlertSeverity.NOTICE, 0.30, 0.25, "instance A");
    long idB = ahB.record(key, AlertSeverity.NOTICE, 0.31, 0.25, "instance B");
    // Both instances record the same alert for the same UTC day (sequentially here - the dedup is
    // deterministic, no race needed): ux_ah_key_day (V018) collapses them to one row and each
    // record() returns that one row's id.
    assertThat(idB).isEqualTo(idA);
    Integer rows =
        ctxA.getBean(JdbcTemplate.class)
            .queryForObject(
                "SELECT count(*) FROM alert_history WHERE alert_key = ?", Integer.class, key);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void oneGuardedDriftJobBodyRunsAcrossThePair() throws Exception {
    PsiFeatureJob jobA = ctxA.getBean(PsiFeatureJob.class);
    PsiFeatureJob jobB = ctxB.getBean(PsiFeatureJob.class);
    LocalDate fireDate = LocalDate.now(ET); // PsiFeatureJob.run() computes the same ET fire date
    raceRun(jobA::run, jobB::run);
    // The @Scheduled entrypoint acquires (psi_feature, today) before doing any work, so exactly one
    // instance's body executed - the other skipped at the lock. One lock row proves single-fire.
    assertThat(lockRows("psi_feature", fireDate))
        .as("only one worker instance runs the psi_feature body for a fire date")
        .isEqualTo(1);
  }

  @Test
  void exactlyOneWorkerClaimsTheQueuedRetrain() throws Exception {
    RetrainingQueueService svcA = ctxA.getBean(RetrainingQueueService.class);
    RetrainingQueueService svcB = ctxB.getBean(RetrainingQueueService.class);
    svcA.enqueue(
        "battedball_outcome", TriggerType.MANUAL, "d39b-claim-" + UUID.randomUUID(), Map.of());
    int winners =
        countWinners(() -> svcA.claimNext().isPresent(), () -> svcB.claimNext().isPresent());
    assertThat(winners)
        .as("only one worker instance may claim a single queued retrain")
        .isEqualTo(1);
  }

  // --- helpers ----------------------------------------------------------

  private static int lockRows(String job, LocalDate fireDate) {
    Integer n =
        ctxA.getBean(JdbcTemplate.class)
            .queryForObject(
                "SELECT count(*) FROM job_locks WHERE job_name = ? AND fire_date = ?",
                Integer.class,
                job,
                fireDate.toString());
    return n == null ? -1 : n;
  }

  /**
   * Race two boolean calls on virtual threads behind a start gate; return how many returned true.
   */
  private static int countWinners(Callable<Boolean> a, Callable<Boolean> b) throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Boolean> fa =
          pool.submit(
              () -> {
                gate.await();
                return a.call();
              });
      Future<Boolean> fb =
          pool.submit(
              () -> {
                gate.await();
                return b.call();
              });
      gate.countDown();
      int wins = 0;
      if (Boolean.TRUE.equals(fa.get(15, TimeUnit.SECONDS))) {
        wins++;
      }
      if (Boolean.TRUE.equals(fb.get(15, TimeUnit.SECONDS))) {
        wins++;
      }
      return wins;
    }
  }

  /** Race two void calls on virtual threads behind a start gate; both must finish. */
  private static void raceRun(ThrowingRunnable a, ThrowingRunnable b) throws Exception {
    CountDownLatch gate = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> fa =
          pool.submit(
              () -> {
                gate.await();
                a.run();
                return null;
              });
      Future<?> fb =
          pool.submit(
              () -> {
                gate.await();
                b.run();
                return null;
              });
      gate.countDown();
      fa.get(30, TimeUnit.SECONDS);
      fb.get(30, TimeUnit.SECONDS);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
