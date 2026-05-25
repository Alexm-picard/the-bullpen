package net.thebullpen.baseball.inference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bounded-queue async batcher for prediction_log writes (Phase 1.7).
 *
 * <p>Contract per decision [30]: dropping under overload is the deal — never make the predict
 * endpoint wait on the logger. The flusher drains up to {@link #FLUSH_BATCH_SIZE} events on a 1s
 * cadence; if the queue fills, {@link #enqueue} returns immediately and ticks the drop counter.
 *
 * <p>If the optional {@link PredictionLogWriter} bean is absent (no ClickHouse DataSource in this
 * env), the logger silently no-ops — the API still serves; logging is best-effort.
 */
@Component
@Profile("api")
public class AsyncPredictionLogger {

  private static final Logger log = LoggerFactory.getLogger(AsyncPredictionLogger.class);

  private static final int FLUSH_BATCH_SIZE = 500;
  private static final long FLUSH_INTERVAL_SECONDS = 1L;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

  private final BlockingQueue<PredictionLogEvent> queue;
  private final Optional<PredictionLogWriter> writer;
  private final Counter enqueuedCounter;
  private final Counter droppedCounter;
  private final Counter writeFailedCounter;
  private ScheduledExecutorService flusher;

  public AsyncPredictionLogger(
      Optional<PredictionLogWriter> writer,
      MeterRegistry meterRegistry,
      // 3b.5: bumped 10K → 20K to absorb shadow-mode 2× volume (every dispatch now produces a
      // CHAMPION row + a SHADOW row when a challenger is registered + routed in shadow mode).
      @Value("${bullpen.inference.log.queue-capacity:20000}") int capacity) {
    this.writer = writer;
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.enqueuedCounter =
        Counter.builder("thebullpen_prediction_log_enqueued_total").register(meterRegistry);
    this.droppedCounter =
        Counter.builder("thebullpen_prediction_log_dropped_total")
            .description("predictions dropped because the in-memory log queue was full")
            .register(meterRegistry);
    this.writeFailedCounter =
        Counter.builder("thebullpen_prediction_log_write_failures_total")
            .description("ClickHouse batch INSERTs that threw")
            .register(meterRegistry);
    meterRegistry.gauge("thebullpen_prediction_log_queue_depth", queue, BlockingQueue::size);
    if (writer.isEmpty()) {
      log.warn(
          "PredictionLogWriter not present — async logger will drop every event (no-op mode). "
              + "Wire a clickhouseDataSource bean to enable persistence.");
    }
  }

  @PostConstruct
  public void start() {
    flusher =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = Thread.ofVirtual().unstarted(r);
              t.setName("prediction-log-flusher");
              return t;
            });
    var unused =
        flusher.scheduleAtFixedRate(
            this::flushQuietly, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    log.info(
        "AsyncPredictionLogger started capacity={} flush_interval_s={}",
        queue.remainingCapacity() + queue.size(),
        FLUSH_INTERVAL_SECONDS);
  }

  public void enqueue(PredictionLogEvent event) {
    if (queue.offer(event)) {
      enqueuedCounter.increment();
    } else {
      droppedCounter.increment();
    }
  }

  /** Visible for tests — drain + flush synchronously. */
  int flushOnce() {
    if (writer.isEmpty()) {
      // No DataSource — just drop the queue contents and tick the counter so the test
      // can observe back-pressure behaviour.
      int drained = queue.size();
      queue.clear();
      if (drained > 0) droppedCounter.increment(drained);
      return drained;
    }
    List<PredictionLogEvent> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
    queue.drainTo(batch, FLUSH_BATCH_SIZE);
    if (batch.isEmpty()) return 0;
    try {
      writer.get().writeBatch(batch);
      return batch.size();
    } catch (Exception e) {
      writeFailedCounter.increment();
      droppedCounter.increment(batch.size());
      log.warn("prediction_log batch flush failed size={} cause={}", batch.size(), e.toString());
      return -1;
    }
  }

  private void flushQuietly() {
    try {
      flushOnce();
    } catch (Throwable t) {
      log.error("unexpected flush error", t);
    }
  }

  public int queueDepth() {
    return queue.size();
  }

  @PreDestroy
  public void stop() throws InterruptedException {
    if (flusher == null) return;
    flusher.shutdown();
    if (!flusher.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      flusher.shutdownNow();
    }
    // Final drain so we don't lose anything that landed while the flusher was sleeping.
    while (flushOnce() > 0) {
      // keep going
    }
  }
}
