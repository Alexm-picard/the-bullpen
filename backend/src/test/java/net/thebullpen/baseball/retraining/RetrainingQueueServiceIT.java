package net.thebullpen.baseball.retraining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.thebullpen.baseball.retraining.dto.QueueStatus;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * IT for {@link RetrainingQueueService} — exercises enqueue / claim / complete / cancel +
 * concurrent claim (10 threads, exactly one winner) + duplicate-trigger idempotency + reaper.
 */
@SpringBootTest
@ActiveProfiles({"api", "registry-it"})
class RetrainingQueueServiceIT {

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    Path dbPath =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-retrain-it-" + UUID.randomUUID() + ".sqlite");
    String url = "jdbc:sqlite:" + dbPath;
    registry.add("spring.datasource.url", () -> url);
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.flyway.url", () -> url);
    Path snapshotBase =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "bullpen-retrain-it-snapshots-" + UUID.randomUUID());
    registry.add("bullpen.snapshot.local-base-path", snapshotBase::toString);
  }

  @Autowired private RetrainingQueueService service;
  @Autowired private JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM retraining_queue");
  }

  // --- enqueue ----------------------------------------------------------

  @Test
  void enqueue_with_caller_trigger_id_persists_it() {
    RetrainingTrigger row =
        service.enqueue("model_a", TriggerType.MANUAL, "manual-001", Map.of("by", "alex"));
    assertThat(row.triggerId()).isEqualTo("manual-001");
    assertThat(row.modelName()).isEqualTo("model_a");
    assertThat(row.triggerType()).isEqualTo(TriggerType.MANUAL);
    assertThat(row.status()).isEqualTo(QueueStatus.QUEUED);
    assertThat(row.enqueuedAt()).isNotNull();
    assertThat(row.startedAt()).isNull();
    assertThat(row.finishedAt()).isNull();
    assertThat(row.producedVersionId()).isNull();
  }

  @Test
  void enqueue_with_null_trigger_id_generates_uuid() {
    RetrainingTrigger row = service.enqueue("model_a", TriggerType.SCHEDULED, null, null);
    assertThat(row.triggerId()).isNotBlank();
    // UUID v4 format check (loose).
    assertThat(row.triggerId()).matches("[0-9a-f-]{36}");
  }

  @Test
  void enqueue_with_duplicate_trigger_id_throws_DuplicateTriggerId() {
    service.enqueue("model_a", TriggerType.MANUAL, "dup-key", Map.of());
    assertThatThrownBy(() -> service.enqueue("model_a", TriggerType.MANUAL, "dup-key", Map.of()))
        .isInstanceOf(RetrainingException.DuplicateTriggerId.class);
  }

  // --- claim ------------------------------------------------------------

  @Test
  void claim_returns_the_oldest_queued_row_and_flips_to_running() throws Exception {
    service.enqueue("model_a", TriggerType.SCHEDULED, "first", Map.of());
    Thread.sleep(10); // SQLite CURRENT_TIMESTAMP is 1s precision; tiny pause to bias enqueued_at.
    service.enqueue("model_a", TriggerType.SCHEDULED, "second", Map.of());

    Optional<RetrainingTrigger> claimed = service.claimNext();
    assertThat(claimed).isPresent();
    assertThat(claimed.orElseThrow().triggerId()).isEqualTo("first");
    assertThat(claimed.orElseThrow().status()).isEqualTo(QueueStatus.RUNNING);
    assertThat(claimed.orElseThrow().startedAt()).isNotNull();
  }

  @Test
  void claim_when_queue_is_empty_returns_empty_optional() {
    assertThat(service.claimNext()).isEmpty();
  }

  @Test
  void claim_does_not_return_already_running_rows() {
    service.enqueue("model_a", TriggerType.SCHEDULED, "only-one", Map.of());
    service.claimNext(); // running
    assertThat(service.claimNext()).isEmpty();
  }

  @Test
  void concurrent_claim_with_one_queued_row_has_exactly_one_winner() throws Exception {
    service.enqueue("model_a", TriggerType.SCHEDULED, "single", Map.of());
    int workers = 10;
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    for (int i = 0; i < workers; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              if (service.claimNext().isPresent()) {
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
        .as("exactly one of %d concurrent claimers must win the single queued row", workers)
        .isEqualTo(1);
  }

  // --- complete (success / failure) -------------------------------------

  @Test
  void completeSuccess_records_produced_version_and_flips_to_succeeded() {
    service.enqueue("model_a", TriggerType.MANUAL, "ok-1", Map.of());
    service.claimNext();
    RetrainingTrigger after = service.completeSuccess("ok-1", 42L);
    assertThat(after.status()).isEqualTo(QueueStatus.SUCCEEDED);
    assertThat(after.finishedAt()).isNotNull();
    assertThat(after.producedVersionId()).isEqualTo(42L);
    assertThat(after.errorMessage()).isNull();
  }

  @Test
  void completeFailure_records_error_message_and_flips_to_failed() {
    service.enqueue("model_a", TriggerType.MANUAL, "fail-1", Map.of());
    service.claimNext();
    RetrainingTrigger after = service.completeFailure("fail-1", "OOM during Bayesian sweep");
    assertThat(after.status()).isEqualTo(QueueStatus.FAILED);
    assertThat(after.finishedAt()).isNotNull();
    assertThat(after.producedVersionId()).isNull();
    assertThat(after.errorMessage()).isEqualTo("OOM during Bayesian sweep");
  }

  @Test
  void complete_on_queued_row_throws_InvalidStateTransition() {
    service.enqueue("model_a", TriggerType.MANUAL, "ne-1", Map.of());
    assertThatThrownBy(() -> service.completeSuccess("ne-1", 99L))
        .isInstanceOf(RetrainingException.InvalidStateTransition.class);
  }

  @Test
  void complete_on_terminal_row_throws_InvalidStateTransition() {
    service.enqueue("model_a", TriggerType.MANUAL, "term-1", Map.of());
    service.claimNext();
    service.completeSuccess("term-1", 7L);
    assertThatThrownBy(() -> service.completeFailure("term-1", "too late"))
        .isInstanceOf(RetrainingException.InvalidStateTransition.class);
  }

  @Test
  void complete_for_unknown_trigger_throws_UnknownTrigger() {
    assertThatThrownBy(() -> service.completeSuccess("ghost", 1L))
        .isInstanceOf(RetrainingException.UnknownTrigger.class);
  }

  // --- cancel -----------------------------------------------------------

  @Test
  void cancel_queued_trigger_flips_to_cancelled() {
    service.enqueue("model_a", TriggerType.MANUAL, "cnq-1", Map.of());
    RetrainingTrigger after = service.cancel("cnq-1");
    assertThat(after.status()).isEqualTo(QueueStatus.CANCELLED);
    assertThat(after.finishedAt()).isNotNull();
  }

  @Test
  void cancel_running_trigger_flips_to_cancelled() {
    service.enqueue("model_a", TriggerType.MANUAL, "cnr-1", Map.of());
    service.claimNext();
    RetrainingTrigger after = service.cancel("cnr-1");
    assertThat(after.status()).isEqualTo(QueueStatus.CANCELLED);
  }

  @Test
  void cancel_terminal_trigger_throws_InvalidStateTransition() {
    service.enqueue("model_a", TriggerType.MANUAL, "ct-1", Map.of());
    service.claimNext();
    service.completeSuccess("ct-1", 1L);
    assertThatThrownBy(() -> service.cancel("ct-1"))
        .isInstanceOf(RetrainingException.InvalidStateTransition.class);
  }

  // --- reaper -----------------------------------------------------------

  @Test
  void reaper_flips_stale_running_rows_back_to_queued() {
    service.enqueue("model_a", TriggerType.SCHEDULED, "stale-1", Map.of());
    service.claimNext();
    // Backdate started_at by 5 hours so the 4-hour reap window catches it.
    jdbc.update(
        "UPDATE retraining_queue SET started_at = ? WHERE trigger_id = ?",
        java.sql.Timestamp.from(Instant.now().minus(5, ChronoUnit.HOURS)),
        "stale-1");

    int reaped = service.reapStaleClaims(Duration.ofHours(4));
    assertThat(reaped).isEqualTo(1);
    RetrainingTrigger reread = service.getByTriggerId("stale-1");
    assertThat(reread.status()).isEqualTo(QueueStatus.QUEUED);
    assertThat(reread.startedAt()).isNull();
  }

  @Test
  void reaper_does_not_touch_recent_running_rows() {
    service.enqueue("model_a", TriggerType.SCHEDULED, "fresh-1", Map.of());
    service.claimNext();

    int reaped = service.reapStaleClaims(Duration.ofHours(4));
    assertThat(reaped).isEqualTo(0);
  }
}
