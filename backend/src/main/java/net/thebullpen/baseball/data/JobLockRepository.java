package net.thebullpen.baseball.data;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * At-most-once-per-ET-day lock over {@code job_locks} (V017) for the non-idempotent worker jobs
 * (D-36). A worker calls {@link #tryAcquire} at the top of a {@code @Scheduled} method: the first
 * instance to insert the {@code (job_name, fire_date)} row wins and runs; any other instance hits
 * the UNIQUE constraint and skips. Semantics are at-most-once per ET day - a crashed winner's day
 * is a missed day, same as today's single-instance failure mode.
 *
 * <p>Binds the plain unqualified {@link JdbcTemplate} (the {@code @Primary} registry SQLite
 * template from {@code RegistryDataSourceConfig}), exactly like {@code OpsEventsRepository} and
 * {@code AlertHistoryRepository}. Not the ClickHouse datasource - {@code job_locks} is a
 * registry-SQLite Flyway table.
 */
@Repository
public class JobLockRepository {

  private static final Logger log = LoggerFactory.getLogger(JobLockRepository.class);

  private final JdbcTemplate jdbc;

  public JobLockRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Try to claim {@code (jobName, fireDate)}. {@code true} = this instance won and should run;
   * {@code false} = another instance already owns this ET fire date, so skip. The dual-catch
   * mirrors {@code RetrainingQueueRepository.insertQueued} (a UNIQUE violation surfaces as either
   * of these two sqlite-jdbc flavors), but returns a boolean instead of throwing.
   */
  public boolean tryAcquire(String jobName, LocalDate fireDate) {
    try {
      jdbc.update(
          "INSERT INTO job_locks (job_name, fire_date) VALUES (?, ?)",
          jobName,
          fireDate.toString());
      return true;
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.springframework.jdbc.UncategorizedSQLException e) {
      // Expected case: a UNIQUE(job_name, fire_date) violation - another instance already claimed
      // this ET day (sqlite-jdbc surfaces it as either flavor, hence the dual-catch). But this
      // catch is broad enough to also cover a genuine registry-SQLite fault (e.g. SQLITE_BUSY past
      // the busy_timeout, a locked/corrupt DB), which likewise yields a skipped run under the
      // at-most-once semantics - so WARN with the exception rather than let a real fault masquerade
      // silently as a lost race (a swallowed fault would let an integrity job like
      // ReconciliationJob
      // go dark). The caller still skips this run either way.
      log.warn(
          "job_locks: could not acquire ({}, {}); skipping this run"
              + " (another instance holds it, or a registry SQL fault)",
          jobName,
          fireDate,
          e);
      return false;
    }
  }
}
