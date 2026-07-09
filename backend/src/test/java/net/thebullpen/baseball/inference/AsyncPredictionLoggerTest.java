package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.thebullpen.baseball.config.InferenceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncPredictionLoggerTest {

  /** Inference props varying only the log queue capacity this suite exercises. */
  private static InferenceProperties props(int queueCapacity) {
    return new InferenceProperties(
        null,
        500L,
        new InferenceProperties.Pitch(InferenceProperties.PITCH_ARTIFACTS_DEFAULT, false),
        new InferenceProperties.PitchPost(
            "../training/artifacts/pitch_outcome_post/v1",
            "../contracts/feature_pipeline_post.json"),
        new InferenceProperties.Toy("../training/artifacts/_toy/v0"),
        new InferenceProperties.Log(queueCapacity));
  }

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setup() {
    registry = new SimpleMeterRegistry();
  }

  @AfterEach
  void teardown() throws InterruptedException {
    registry.close();
  }

  private static PredictionLogEvent sampleEvent(int seq) {
    return new PredictionLogEvent(
        UUID.randomUUID(),
        Instant.now(),
        "_toy_batted_ball",
        "v0",
        PredictionLogEvent.Role.CHAMPION,
        "hash",
        "{\"seq\":" + seq + "}",
        "{\"prob_hr\":0.5}",
        3.14f,
        "cid-" + seq);
  }

  @Test
  void enqueue_thenFlush_drainsEverything() throws InterruptedException {
    CapturingWriter writer = new CapturingWriter();
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(writer), registry, props(1024));
    try {
      logger.start();
      for (int i = 0; i < 50; i++) logger.enqueue(sampleEvent(i));
      // start() schedules flushes on a 1s cadence; flush manually for determinism
      logger.flushOnce();
      assertThat(writer.captured()).hasSize(50);
      assertThat(registry.counter("thebullpen_prediction_log_enqueued_total").count())
          .isEqualTo(50);
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(0);
    } finally {
      logger.stop();
    }
  }

  @Test
  void overflowedQueue_incrementsDroppedCounter() throws InterruptedException {
    CapturingWriter writer = new CapturingWriter();
    int capacity = 8;
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(writer), registry, props(capacity));
    try {
      logger.start();
      // do NOT flush — fill the queue past capacity to force drops
      for (int i = 0; i < capacity + 5; i++) logger.enqueue(sampleEvent(i));
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(5);
      assertThat(logger.queueDepth()).isEqualTo(capacity);
    } finally {
      logger.stop();
    }
  }

  @Test
  void writerFailure_reEnqueuesBatch_noLossWithHeadroom() throws InterruptedException {
    PredictionLogWriter throwing =
        new PredictionLogWriter(null) {
          @Override
          public void writeBatch(List<PredictionLogEvent> batch) {
            throw new RuntimeException("simulated ClickHouse outage");
          }
        };
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(throwing), registry, props(32));
    try {
      logger.start();
      for (int i = 0; i < 5; i++) logger.enqueue(sampleEvent(i));
      logger.flushOnce();
      assertThat(registry.counter("thebullpen_prediction_log_write_failures_total").count())
          .isEqualTo(1);
      // DEF-M1: a transient write failure re-enqueues the batch (the queue has headroom), so
      // nothing is dropped and the events survive to be retried.
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(0);
      assertThat(logger.queueDepth()).isEqualTo(5);
    } finally {
      logger.stop();
    }
  }

  @Test
  void writerFailure_thenRecovery_deliversEverything() throws InterruptedException {
    FailOnceWriter writer = new FailOnceWriter();
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(writer), registry, props(32));
    try {
      logger.start();
      for (int i = 0; i < 5; i++) logger.enqueue(sampleEvent(i));
      logger.flushOnce(); // transient failure -> re-enqueues 5
      logger.flushOnce(); // recovers -> writes 5
      assertThat(writer.captured()).hasSize(5);
      assertThat(registry.counter("thebullpen_prediction_log_write_failures_total").count())
          .isEqualTo(1);
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(0);
    } finally {
      logger.stop();
    }
  }

  @Test
  void shutdownDrain_survivesATransientFailure() throws InterruptedException {
    // DEF-M2: a single failed flush during @PreDestroy must not abandon the rest of the queue.
    FailOnceWriter writer = new FailOnceWriter();
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(writer), registry, props(32));
    logger.start();
    for (int i = 0; i < 5; i++) logger.enqueue(sampleEvent(i));
    logger.stop(); // drain: first flush fails (re-enqueues), second succeeds -> all 5 written

    assertThat(writer.captured()).hasSize(5);
    assertThat(logger.queueDepth()).isEqualTo(0);
  }

  @Test
  void noWriterPresent_dropsSilentlyAndDoesNotCrash() throws InterruptedException {
    AsyncPredictionLogger logger = new AsyncPredictionLogger(Optional.empty(), registry, props(32));
    try {
      logger.start();
      for (int i = 0; i < 3; i++) logger.enqueue(sampleEvent(i));
      logger.flushOnce();
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(3);
    } finally {
      logger.stop();
    }
  }

  // --- 3b.5 additions: V2 modelVersionId roundtrip + shadow-mode 2× load --------------

  @Test
  void v1_legacy_constructor_carries_null_modelVersionId() {
    PredictionLogEvent ev = sampleEvent(0);
    assertThat(ev.modelVersionId())
        .as("legacy 1.7-era constructor must default modelVersionId to null")
        .isNull();
  }

  @Test
  void v2_constructor_carries_explicit_modelVersionId() {
    PredictionLogEvent ev =
        new PredictionLogEvent(
            UUID.randomUUID(),
            Instant.now(),
            "_toy_batted_ball",
            "v0",
            42L,
            PredictionLogEvent.Role.CHAMPION,
            "hash",
            "{}",
            "{}",
            1.0f,
            "cid");
    assertThat(ev.modelVersionId()).isEqualTo(42L);
  }

  @Test
  void shadow_mode_2x_volume_does_not_drop_at_20k_default_capacity() throws InterruptedException {
    // The 3b.3 batted-ball router produces 2 log rows per request when shadow mode is active
    // (CHAMPION + SHADOW). At 1000 req/s sustained for 5 seconds = 10K requests = 20K log rows.
    // With default capacity bumped to 20K + 1-sec flush cadence, drops should stay at 0 as long
    // as the writer can drain in time. This test simulates by flushing-as-we-go.
    CapturingWriter writer = new CapturingWriter();
    AsyncPredictionLogger logger =
        new AsyncPredictionLogger(Optional.of(writer), registry, props(20_000));
    try {
      logger.start();
      int requests = 1000;
      for (int i = 0; i < requests; i++) {
        // shadow-mode dispatch produces 2 rows per request.
        logger.enqueue(sampleEvent(i)); // CHAMPION row
        logger.enqueue(shadowEvent(i)); // SHADOW row
        // flush in small batches like the real 1-sec scheduler would.
        if (i % 250 == 0) {
          logger.flushOnce();
        }
      }
      // final drain
      while (logger.flushOnce() > 0) {
        // keep draining
      }
      assertThat(writer.captured()).hasSize(requests * 2);
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count())
          .as("shadow-mode 2x volume should not drop under sustained 1K req/s with 20K capacity")
          .isEqualTo(0);
      // verify roles are correct: half CHAMPION + half SHADOW
      long champions =
          writer.captured().stream()
              .filter(e -> e.role() == PredictionLogEvent.Role.CHAMPION)
              .count();
      long shadows =
          writer.captured().stream()
              .filter(e -> e.role() == PredictionLogEvent.Role.SHADOW)
              .count();
      assertThat(champions).isEqualTo(requests);
      assertThat(shadows).isEqualTo(requests);
    } finally {
      logger.stop();
    }
  }

  private static PredictionLogEvent shadowEvent(int seq) {
    return new PredictionLogEvent(
        UUID.randomUUID(),
        Instant.now(),
        "_toy_batted_ball",
        "v1",
        2L, // synthetic shadow version id
        PredictionLogEvent.Role.SHADOW,
        "hash",
        "{\"seq\":" + seq + "}",
        "{\"prob_hr\":0.51}",
        3.14f,
        "cid-shadow-" + seq);
  }

  /** In-memory writer that captures events written for assertion. Thread-safe. */
  private static final class CapturingWriter extends PredictionLogWriter {
    private final List<PredictionLogEvent> captured = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    CapturingWriter() {
      super(null);
    }

    @Override
    public synchronized void writeBatch(List<PredictionLogEvent> batch) {
      captured.addAll(batch);
      calls.incrementAndGet();
    }

    synchronized List<PredictionLogEvent> captured() {
      return new ArrayList<>(captured);
    }
  }

  /**
   * Fails the first writeBatch (a transient outage), succeeds after - exercises re-enqueue + drain.
   */
  private static final class FailOnceWriter extends PredictionLogWriter {
    private final List<PredictionLogEvent> captured = new ArrayList<>();
    private boolean failedOnce = false;

    FailOnceWriter() {
      super(null);
    }

    @Override
    public synchronized void writeBatch(List<PredictionLogEvent> batch) {
      if (!failedOnce) {
        failedOnce = true;
        throw new RuntimeException("transient ClickHouse outage");
      }
      captured.addAll(batch);
    }

    synchronized List<PredictionLogEvent> captured() {
      return new ArrayList<>(captured);
    }
  }
}
