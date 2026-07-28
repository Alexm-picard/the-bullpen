package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Concurrency contract of the task-#87 in-flight gate, pinned with latches rather than sleeps
 * wherever an ordering is asserted (the one sleep below is a NEGATIVE check - "close has NOT
 * completed yet" - which cannot be expressed as a latch await).
 */
@Timeout(10)
class SessionGuardTest {

  @Test
  void close_waits_for_the_in_flight_call_to_drain() throws Exception {
    SessionGuard guard = new SessionGuard("test");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);

    Thread inFlight =
        new Thread(
            () -> {
              try {
                guard.withSession(
                    () -> {
                      entered.countDown();
                      awaitQuietly(release);
                      return null;
                    });
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    inFlight.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    Thread closer =
        new Thread(
            () -> {
              try {
                guard.closeWhenIdle(closed::countDown);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    closer.start();

    // Negative check: with the in-flight call still parked, the close action must not have run.
    Thread.sleep(150);
    assertThat(closed.getCount()).as("close ran while a call was in flight").isEqualTo(1);

    release.countDown();
    assertThat(closed.await(2, TimeUnit.SECONDS))
        .as("close should complete promptly once the in-flight call drains")
        .isTrue();
    inFlight.join(2000);
    closer.join(2000);
  }

  @Test
  void entering_a_retired_guard_refuses_typed() throws Exception {
    SessionGuard guard = new SessionGuard("test");
    guard.closeWhenIdle(() -> {});
    assertThatThrownBy(() -> guard.withSession(() -> null))
        .isInstanceOf(ModelUnavailableException.class)
        .hasMessageContaining("retired");
    assertThat(guard.isRetired()).isTrue();
  }

  @Test
  void close_is_idempotent_and_the_action_runs_exactly_once() throws Exception {
    SessionGuard guard = new SessionGuard("test");
    AtomicInteger closes = new AtomicInteger();
    guard.closeWhenIdle(closes::incrementAndGet);
    guard.closeWhenIdle(closes::incrementAndGet);
    assertThat(closes.get()).isEqualTo(1);
  }

  /**
   * Two closers racing while a call is in flight: both pass the entry check (the first releases the
   * monitor inside wait()), and exactly one may run the action - the post-drain re-check is what
   * this pins; without it both closers close the native session.
   */
  @Test
  void concurrent_closers_run_the_action_once() throws Exception {
    SessionGuard guard = new SessionGuard("test");
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();

    Thread inFlight =
        new Thread(
            () -> {
              try {
                guard.withSession(
                    () -> {
                      entered.countDown();
                      awaitQuietly(release);
                      return null;
                    });
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    inFlight.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    Runnable closerBody =
        () -> {
          try {
            guard.closeWhenIdle(closes::incrementAndGet);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    Thread closerA = new Thread(closerBody);
    Thread closerB = new Thread(closerBody);
    closerA.start();
    closerB.start();
    // Let both closers reach the drain before releasing the in-flight call.
    Thread.sleep(150);
    release.countDown();
    closerA.join(2000);
    closerB.join(2000);
    inFlight.join(2000);
    assertThat(closes.get()).isEqualTo(1);
  }

  @Test
  void drain_timeout_forces_the_close_under_a_stuck_call() throws Exception {
    SessionGuard guard = new SessionGuard("test", Duration.ofMillis(100));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();

    Thread stuck =
        new Thread(
            () -> {
              try {
                guard.withSession(
                    () -> {
                      entered.countDown();
                      awaitQuietly(release);
                      return null;
                    });
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    stuck.setDaemon(true);
    stuck.start();
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

    guard.closeWhenIdle(closes::incrementAndGet); // must return after ~100ms, not hang
    assertThat(closes.get()).isEqualTo(1);

    // Unstick and let the finally-side exit run against the already-closed guard (must not throw).
    release.countDown();
    stuck.join(2000);
    assertThat(stuck.isAlive()).isFalse();
  }

  @Test
  void concurrent_entries_are_allowed() throws Exception {
    SessionGuard guard = new SessionGuard("test");
    CountDownLatch bothInside = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);

    Runnable body =
        () -> {
          try {
            guard.withSession(
                () -> {
                  bothInside.countDown();
                  awaitQuietly(release);
                  return null;
                });
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        };
    Thread a = new Thread(body);
    Thread b = new Thread(body);
    a.start();
    b.start();
    assertThat(bothInside.await(2, TimeUnit.SECONDS))
        .as("the gate must not serialize concurrent runs")
        .isTrue();
    release.countDown();
    a.join(2000);
    b.join(2000);
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
