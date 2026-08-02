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
import net.thebullpen.baseball.config.InferenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bounded-queue async batcher for prediction_log writes (Phase 1.7).
 *
 * <p>Contract per decision [30]: dropping under overload is the deal — never make the predict
 * endpoint wait on the logger. The flusher drains the queue in {@link #FLUSH_BATCH_SIZE} batches on
 * a 1s cadence; if the queue fills, {@link #enqueue} returns immediately and ticks the drop
 * counter.
 *
 * <p>H3 hardening (issue #90). Three failure modes the original design left open:
 *
 * <ul>
 *   <li><b>Poison rows.</b> A failed batch used to re-enqueue indefinitely. One row ClickHouse
 *       permanently rejects therefore failed every batch it landed in, forever - throughput
 *       collapsed to zero while the queue stayed full and every NEW event dropped, all of it
 *       reading as "transient CH failures". Now each event carries a write-attempt count; an event
 *       whose batch has failed {@link #MAX_WRITE_ATTEMPTS} times is DEAD-LETTERED: dropped with its
 *       own counter ({@code thebullpen_prediction_log_dead_lettered_total}) and a log line carrying
 *       enough identity to reconstruct which rows were lost. Deliberate trade-off: attempts are
 *       counted per BATCH failure, so the up-to-499 innocent rows sharing a poison row's final
 *       batch die with it - isolating the poison row would need per-row retry writes, and [30] says
 *       logging is best-effort, not exactly-once.
 *   <li><b>Outage hammering.</b> A failed flush used to retry on the next 1s tick regardless. Now
 *       consecutive failures back off exponentially (2s, 4s, ... capped at {@link
 *       #BACKOFF_CAP_SECONDS}s), which both spares a struggling ClickHouse and slows the
 *       attempt-count burn so a multi-minute outage does not dead-letter the queue. The queue keeps
 *       absorbing during backoff; overload during an outage still drops at the queue boundary,
 *       exactly as [30] sanctions. Shutdown bypasses backoff (its own failure cap bounds it).
 *   <li><b>The 500 rows/s ceiling.</b> One drainTo per tick capped steady-state throughput at
 *       FLUSH_BATCH_SIZE per second; sustained input above that backlogged into drops even with a
 *       healthy writer. The scheduled path now loops batches until the queue is empty (or a failure
 *       starts backoff) - the batch size bounds INSERT size, not throughput.
 * </ul>
 *
 * <p>If the optional {@link PredictionLogWriter} bean is absent (no ClickHouse DataSource in this
 * env), the logger silently no-ops — the API still serves; logging is best-effort.
 */
@Component
@Profile({"api", "worker"})
public class AsyncPredictionLogger {

  private static final Logger log = LoggerFactory.getLogger(AsyncPredictionLogger.class);

  private static final int FLUSH_BATCH_SIZE = 500;
  private static final long FLUSH_INTERVAL_SECONDS = 1L;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
  // Cap on consecutive failed flushes during shutdown so a persistently-unreachable ClickHouse
  // can't hang @PreDestroy (each failure re-enqueues its batch via flushOnce).
  private static final int MAX_SHUTDOWN_FLUSH_FAILURES = 3;
  // A batch write is attempted this many times per event before the event is dead-lettered. With
  // backoff, five attempts tolerate an outage of roughly 2+4+8+16+32s > 1 minute before loss
  // begins; a longer outage degrades to honest, counted loss rather than a wedged queue.
  static final int MAX_WRITE_ATTEMPTS = 5;
  static final long BACKOFF_CAP_SECONDS = 60L;

  /** A queued event plus how many failed batch writes it has been part of. */
  private record Queued(PredictionLogEvent event, int attempts) {}

  private final BlockingQueue<Queued> queue;
  private final Optional<PredictionLogWriter> writer;
  private final Counter enqueuedCounter;
  private final Counter droppedCounter;
  private final Counter writeFailedCounter;
  private final Counter deadLetteredCounter;
  private ScheduledExecutorService flusher;

  // Mutated on the single-threaded flusher (and by shutdown after the flusher terminates), but
  // read from tests and shutdown - AtomicInteger for the increment (a volatile ++ is a
  // non-atomic read-modify-write, and SpotBugs rightly refuses it), volatile for the plain
  // timestamp write.
  private final java.util.concurrent.atomic.AtomicInteger consecutiveFlushFailures =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile long nextAttemptNanos;

  public AsyncPredictionLogger(
      Optional<PredictionLogWriter> writer,
      MeterRegistry meterRegistry,
      // 3b.5: default bumped 10K → 20K to absorb shadow-mode 2× volume (every dispatch now
      // produces a CHAMPION row + a SHADOW row when a challenger is registered + routed in shadow
      // mode). Capacity lives at bullpen.inference.log.queue-capacity.
      InferenceProperties props) {
    this.writer = writer;
    this.queue = new ArrayBlockingQueue<>(props.log().queueCapacity());
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
    this.deadLetteredCounter =
        Counter.builder("thebullpen_prediction_log_dead_lettered_total")
            .description(
                "events dropped after their batch failed MAX_WRITE_ATTEMPTS writes"
                    + " (poison-row protection) - distinct from queue-overload drops")
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
    flusher.scheduleAtFixedRate(
        this::flushQuietly, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    log.info(
        "AsyncPredictionLogger started capacity={} flush_interval_s={} max_write_attempts={}",
        queue.remainingCapacity() + queue.size(),
        FLUSH_INTERVAL_SECONDS,
        MAX_WRITE_ATTEMPTS);
  }

  public void enqueue(PredictionLogEvent event) {
    if (queue.offer(new Queued(event, 0))) {
      enqueuedCounter.increment();
    } else {
      droppedCounter.increment();
    }
  }

  /** Visible for tests — drain + flush ONE batch synchronously. */
  int flushOnce() {
    if (writer.isEmpty()) {
      // No DataSource — just drop the queue contents and tick the counter so the test
      // can observe back-pressure behaviour.
      int drained = queue.size();
      queue.clear();
      if (drained > 0) droppedCounter.increment(drained);
      return drained;
    }
    List<Queued> batch = new ArrayList<>(FLUSH_BATCH_SIZE);
    queue.drainTo(batch, FLUSH_BATCH_SIZE);
    if (batch.isEmpty()) return 0;
    try {
      writer.get().writeBatch(batch.stream().map(Queued::event).toList());
      consecutiveFlushFailures.set(0);
      return batch.size();
    } catch (Exception e) {
      // A transient failure must NOT lose the batch: re-offer with an incremented attempt count
      // (it goes to the back of the queue, retried after backoff). Three distinct loss accounts:
      // dead-lettered (attempt cap reached - the poison-row exit), dropped (no longer fits -
      // the genuine overload case decision [30] sanctions), and neither for a plain requeue.
      writeFailedCounter.increment();
      int failures = consecutiveFlushFailures.incrementAndGet();
      nextAttemptNanos = System.nanoTime() + backoffNanos(failures);
      int requeued = 0;
      int deadLettered = 0;
      for (Queued q : batch) {
        int attempts = q.attempts() + 1;
        if (attempts >= MAX_WRITE_ATTEMPTS) {
          deadLettered++;
          deadLetteredCounter.increment();
          PredictionLogEvent ev = q.event();
          log.error(
              "prediction_log event dead-lettered after {} failed writes: requestId={} model={}/{}"
                  + " role={} requestAt={}",
              attempts,
              ev.requestId(),
              ev.modelName(),
              ev.modelVersion(),
              ev.role(),
              ev.requestAt());
        } else if (queue.offer(new Queued(q.event(), attempts))) {
          requeued++;
        } else {
          droppedCounter.increment();
        }
      }
      log.warn(
          "prediction_log batch flush failed size={} requeued={} dead_lettered={}"
              + " consecutive_failures={} next_attempt_in_s={} cause={}",
          batch.size(),
          requeued,
          deadLettered,
          failures,
          backoffNanos(failures) / 1_000_000_000L,
          e.toString());
      return -1;
    }
  }

  /**
   * The scheduled path: drain batch after batch until the queue is empty or a write fails. The loop
   * removes the old one-batch-per-tick throughput ceiling (500 rows/s); the batch size bounds
   * INSERT size, not throughput. A failure ends the cycle - backoff owns the retry.
   */
  int flushCycle() {
    int total = 0;
    while (true) {
      int flushed = flushOnce();
      if (flushed <= 0) {
        return flushed < 0 ? -1 : total;
      }
      total += flushed;
    }
  }

  /** Pure backoff schedule, package-private so the test pins the math without a clock. */
  static long backoffNanos(int consecutiveFailures) {
    long seconds = Math.min(1L << Math.min(consecutiveFailures, 30), BACKOFF_CAP_SECONDS);
    return TimeUnit.SECONDS.toNanos(seconds);
  }

  /** Whether the scheduled path should attempt a flush now, or sit out the backoff window. */
  boolean shouldAttempt(long nowNanos) {
    return consecutiveFlushFailures.get() == 0 || nowNanos - nextAttemptNanos >= 0;
  }

  private void flushQuietly() {
    try {
      if (!shouldAttempt(System.nanoTime())) {
        return;
      }
      flushCycle();
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
    // Final drain so we don't lose anything that landed while the flusher was sleeping. Calls
    // flushOnce directly - shutdown does NOT honour backoff (its own failure cap bounds it). A
    // single failed flush no longer abandons the rest of the queue: flushOnce returns -1 on a
    // write failure (and re-enqueues the batch), so we loop on queue depth, not the return
    // value, and the next batch may write fine.
    int failedFlushes = 0;
    while (queueDepth() > 0 && failedFlushes < MAX_SHUTDOWN_FLUSH_FAILURES) {
      int flushed = flushOnce();
      if (flushed == 0) {
        break; // queue drained (or no writer)
      }
      if (flushed < 0) {
        failedFlushes++;
      }
    }
    if (queueDepth() > 0) {
      log.warn("prediction_log shutdown left {} events unflushed", queueDepth());
    }
  }
}
