package net.thebullpen.baseball.inference;

import ai.onnxruntime.OrtException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-flight gate for one native ORT session (task #87 / H2). {@code OrtSession.close()} during an
 * active {@code run()} releases native memory out from under the run - a JVM-crash-class hazard,
 * not an exception - and the Caffeine removalListener that closes evicted bundles runs on an
 * arbitrary executor thread with no knowledge of in-flight requests. Every native-touching call on
 * the ONNX wrapper classes enters this gate; {@link #closeWhenIdle} RETIRES the guard (new entries
 * refuse with {@link ModelUnavailableException}, which every caller already treats as a 503-able
 * condition and {@code ModelLoader}'s retired-recheck turns into a fresh load), waits for in-flight
 * calls to drain, then runs the close action exactly once.
 *
 * <p>The drain wait is BOUNDED: a native run stuck past the timeout (p99 for a single forward pass
 * is ~14ms, so seconds of in-flight time is already pathological) gets the close forced under it
 * with a loud log rather than pinning the closing thread forever. That reopens the close-under-use
 * hazard for exactly the stuck call, deliberately: the alternative is an unbounded wait on a thread
 * we may not own (the Caffeine executor, or Spring shutdown).
 *
 * <p>Plain monitor synchronization, not a read-write lock: entries are a counter increment under an
 * uncontended monitor (nanoseconds against a native call that costs milliseconds), and the close
 * path is rare (eviction, promotion churn, shutdown).
 */
final class SessionGuard {

  private static final Logger log = LoggerFactory.getLogger(SessionGuard.class);
  private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(5);

  /** A native-touching body: what runs inside the gate. */
  @FunctionalInterface
  interface OrtCallable<T> {
    T call() throws OrtException;
  }

  /** The actual session close, run at most once, after the drain (or its timeout). */
  @FunctionalInterface
  interface OrtCloseAction {
    void close() throws OrtException;
  }

  private final String what;
  private final Duration drainTimeout;

  private int inFlight;
  private boolean retired;
  private boolean closeActionRan;

  SessionGuard(String what) {
    this(what, DEFAULT_DRAIN_TIMEOUT);
  }

  /** Visible-for-tests constructor: the timeout-forced close path needs a short drain bound. */
  SessionGuard(String what, Duration drainTimeout) {
    this.what = what;
    this.drainTimeout = drainTimeout;
  }

  <T> T withSession(OrtCallable<T> body) throws OrtException {
    synchronized (this) {
      if (retired) {
        throw new ModelUnavailableException(
            what
                + " is retired (evicted from the model cache or shutting down) - the current"
                + " bundle reference is stale; a fresh load serves the next request");
      }
      inFlight++;
    }
    try {
      return body.call();
    } finally {
      synchronized (this) {
        inFlight--;
        if (retired && inFlight == 0) {
          notifyAll();
        }
      }
    }
  }

  /**
   * Retire the guard and close the session once idle. Idempotent: the first caller runs {@code
   * action}; every later caller returns immediately (the Caffeine listener and the {@code
   * ModelLoader.close()} shutdown sweep can both reach a bundle - exactly one of them closes it).
   */
  void closeWhenIdle(OrtCloseAction action) throws OrtException {
    synchronized (this) {
      retired = true;
      if (closeActionRan) {
        return;
      }
      long deadlineNanos = System.nanoTime() + drainTimeout.toNanos();
      // Also exits when a CONCURRENT closer claims the close while this one drains - without the
      // closeActionRan term a losing closer would sit out its full timeout and then log a
      // "forcing the native close" ERROR for a close it never performs.
      while (inFlight > 0 && !closeActionRan) {
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0) {
          log.error(
              "SessionGuard: {} still has {} in-flight call(s) after {}ms drain - forcing the"
                  + " native close under them (task #87 bounded-drain policy)",
              what,
              inFlight,
              drainTimeout.toMillis());
          break;
        }
        try {
          wait(remainingMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.error(
              "SessionGuard: interrupted draining {} ({} in-flight) - forcing the native close",
              what,
              inFlight);
          break;
        }
      }
      // RE-checked after the drain, not only at entry: wait() releases the monitor, so a second
      // concurrent closer can pass the entry check while the first drains - whichever thread
      // reaches here first claims the close under the monitor and the other returns.
      if (closeActionRan) {
        return;
      }
      closeActionRan = true;
      // Wake any concurrent closer still in its drain wait so it observes the claim immediately
      // instead of timing out.
      notifyAll();
    }
    action.close();
  }

  synchronized boolean isRetired() {
    return retired;
  }
}
