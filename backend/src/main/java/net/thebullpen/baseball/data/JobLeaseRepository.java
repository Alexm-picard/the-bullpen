package net.thebullpen.baseball.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Renewable single-owner heartbeat lease over {@code job_leases} (V019, D-37). Turns a
 * worker-profile {@code @Scheduled} task into a safe singleton across instances: exactly one owner
 * holds {@code job_name} at a time, renews it every tick, and another instance takes over only
 * after the lease goes stale (~{@code staleSeconds} of missed renewals = a crashed/paused holder).
 * The live poller ({@code ingest/LivePollingService}) is the first caller - a single poller is a
 * feature, not a bottleneck (the 500ms MLB-API politeness gap means one poller is correct).
 *
 * <p>Distinct from {@link JobLockRepository} (V017): that is an at-most-once-per-ET-day marker;
 * this is a renewable lease with automatic failover.
 *
 * <p>Binds the plain unqualified {@link JdbcTemplate} (the {@code @Primary} registry SQLite
 * template from {@code RegistryDataSourceConfig}), exactly like {@code JobLockRepository} and
 * {@code OpsEventsRepository}. Not the ClickHouse datasource - {@code job_leases} is a
 * registry-SQLite Flyway table.
 */
@Repository
public class JobLeaseRepository {

  private static final Logger log = LoggerFactory.getLogger(JobLeaseRepository.class);

  private final JdbcTemplate jdbc;

  public JobLeaseRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Acquire the lease if unheld/stale, or renew it if we already hold it. Returns true iff this
   * owner holds the lease after the call. Atomic single-statement upsert-with-guard: the DO UPDATE
   * only fires when we already own it OR it's older than staleSeconds, so exactly one owner wins
   * under concurrent workers (SQLite serializes writes). changed==1 =&gt; inserted or
   * renewed/took-over (we hold it); changed==0 =&gt; another owner holds a fresh lease.
   *
   * <p>The changed==1 / changed==0 return of sqlite-jdbc 3.49.1.0 was verified empirically: a
   * conflicting INSERT whose DO UPDATE WHERE evaluates false reports 0 affected rows, while an
   * insert / renew / stale-takeover reports 1. (SQLite {@code sqlite3_changes()} counts the DO
   * UPDATE as 0 rows when its WHERE is not satisfied.)
   */
  public boolean tryAcquireOrRenew(String jobName, String owner, long staleSeconds) {
    try {
      int changed =
          jdbc.update(
              "INSERT INTO job_leases (job_name, owner, heartbeat_at) VALUES (?, ?, CURRENT_TIMESTAMP)"
                  + " ON CONFLICT(job_name) DO UPDATE SET owner = excluded.owner,"
                  + " heartbeat_at = excluded.heartbeat_at"
                  + " WHERE job_leases.owner = excluded.owner"
                  + " OR job_leases.heartbeat_at < datetime('now', ?)",
              jobName,
              owner,
              "-" + staleSeconds + " seconds");
      return changed == 1;
    } catch (DataAccessException e) {
      // The upsert-with-guard resolves a conflict gracefully (changed==0, no exception), so a throw
      // here is always an unexpected registry-SQLite fault (SQLITE_BUSY past busy_timeout, a
      // locked/corrupt DB). WARN with the exception and report the lease as NOT held so the caller
      // stays dormant this tick (safe) rather than letting a raw error escape the @Scheduled method
      // unnamed - mirrors JobLockRepository's fail-visible posture.
      log.warn(
          "job_leases: tryAcquireOrRenew({}, {}) failed; treating as not held (dormant tick)",
          jobName,
          owner,
          e);
      return false;
    }
  }
}
