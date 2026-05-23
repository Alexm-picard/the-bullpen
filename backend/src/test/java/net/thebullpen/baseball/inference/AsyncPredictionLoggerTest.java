package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AsyncPredictionLoggerTest {

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
    AsyncPredictionLogger logger = new AsyncPredictionLogger(Optional.of(writer), registry, 1024);
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
        new AsyncPredictionLogger(Optional.of(writer), registry, capacity);
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
  void writerFailure_incrementsFailureCounter_andDoesNotPropagate() throws InterruptedException {
    PredictionLogWriter throwing =
        new PredictionLogWriter(null) {
          @Override
          public void writeBatch(List<PredictionLogEvent> batch) {
            throw new RuntimeException("simulated ClickHouse outage");
          }
        };
    AsyncPredictionLogger logger = new AsyncPredictionLogger(Optional.of(throwing), registry, 32);
    try {
      logger.start();
      for (int i = 0; i < 5; i++) logger.enqueue(sampleEvent(i));
      logger.flushOnce();
      assertThat(registry.counter("thebullpen_prediction_log_write_failures_total").count())
          .isEqualTo(1);
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(5);
    } finally {
      logger.stop();
    }
  }

  @Test
  void noWriterPresent_dropsSilentlyAndDoesNotCrash() throws InterruptedException {
    AsyncPredictionLogger logger = new AsyncPredictionLogger(Optional.empty(), registry, 32);
    try {
      logger.start();
      for (int i = 0; i < 3; i++) logger.enqueue(sampleEvent(i));
      logger.flushOnce();
      assertThat(registry.counter("thebullpen_prediction_log_dropped_total").count()).isEqualTo(3);
    } finally {
      logger.stop();
    }
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
}
