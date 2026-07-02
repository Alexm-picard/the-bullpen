package net.thebullpen.baseball.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler pool for the {@code @Scheduled} jobs. Without an explicit {@link
 * ThreadPoolTaskScheduler} bean, Spring's default scheduler runs on a SINGLE thread, and the worker
 * profile puts ~12 scheduled methods on it: the 5-second live-game poll ({@code
 * LivePollingService}) shared a thread with {@code MatchupRefreshJob}'s retrying MLB API calls
 * (backoff up to seconds per attempt) and the 2-4 AM drift/calibration crons - so one slow job
 * silently stalled live ingest ticks during exactly the games the poller exists to watch.
 *
 * <p>Pool of 4: the live tick can always run alongside a slow batch job plus headroom; the crons
 * are minute-scale and mostly non-overlapping, so a larger pool buys nothing. Jobs are NOT assumed
 * to be concurrency-safe with themselves - Spring never runs the same {@code @Scheduled} method
 * concurrently, and this pool does not change that guarantee; it only stops DIFFERENT jobs from
 * queuing behind each other.
 */
@Configuration
public class SchedulerConfig {

  @Bean
  public ThreadPoolTaskScheduler taskScheduler(
      @Value("${bullpen.scheduler.pool-size:4}") int poolSize) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(poolSize);
    scheduler.setThreadNamePrefix("bullpen-sched-");
    // Surface, do not swallow: a rejected/failed task logs through Spring's default error
    // handling; on shutdown let in-flight jobs finish their current tick.
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(10);
    return scheduler;
  }
}
