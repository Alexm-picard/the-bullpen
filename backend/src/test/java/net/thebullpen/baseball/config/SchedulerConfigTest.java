package net.thebullpen.baseball.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The regression this pins: Spring's default scheduler is single-threaded, so the 5s live-game poll
 * queued behind slow crons. The pool must be genuinely multi-threaded.
 */
class SchedulerConfigTest {

  @Test
  void schedulerPoolIsLargerThanOneThread() {
    ThreadPoolTaskScheduler scheduler = new SchedulerConfig().taskScheduler(4);
    try {
      assertThat(scheduler.getPoolSize()).isGreaterThan(1);
      assertThat(scheduler.getThreadNamePrefix()).isEqualTo("bullpen-sched-");
    } finally {
      scheduler.destroy();
    }
  }

  @Test
  void poolSizeIsConfigurable() {
    ThreadPoolTaskScheduler scheduler = new SchedulerConfig().taskScheduler(7);
    try {
      assertThat(scheduler.getPoolSize()).isEqualTo(7);
    } finally {
      scheduler.destroy();
    }
  }
}
