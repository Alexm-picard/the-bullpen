package net.thebullpen.baseball.drift.alerting;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * In-SQLite dedup + audit trail for fired drift alerts (V014). The hot operation is {@link
 * #firedWithin}: returns true if {@code alertKey} fired more recently than {@code now - window}.
 * The index {@code idx_ah_key_time} makes this an O(1) seek.
 */
@Repository
public class AlertHistoryRepository {

  private final JdbcTemplate jdbc;

  public AlertHistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * True iff a row for {@code alertKey} was written with {@code fired_at >= now - window}. Used by
   * {@link DriftAlertEvaluator} to suppress repeat fires within the dedup window (24h per leaf
   * body).
   */
  public boolean firedWithin(String alertKey, Duration window) {
    Instant cutoff = Instant.now().minus(window);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_history WHERE alert_key = ? AND fired_at >= ?",
            Integer.class,
            alertKey,
            Timestamp.from(cutoff));
    return count != null && count > 0;
  }

  /**
   * Record one alert fire. Returns the effective row id for this alert on the current (UTC) day.
   *
   * <p>D-36 Part B defense-in-depth: the INSERT uses {@code ON CONFLICT(alert_key, date(fired_at))
   * DO NOTHING} against the {@code ux_ah_key_day} unique index (V018) - scoped to that index only,
   * so a real CHECK / NOT-NULL violation still fails loud rather than being silently dropped. The
   * 24h {@link #firedWithin} check remains the PRIMARY business dedup; this index only trips if two
   * worker instances both pass {@code firedWithin} and race the INSERT. When the row already exists
   * the INSERT is a no-op and this returns the EXISTING row's id (same alert, same day) rather than
   * a new one - so callers that read the return value always get a valid id for the day's alert.
   */
  public long record(
      String alertKey,
      AlertSeverity severity,
      Double metricValue,
      Double metricThreshold,
      String details) {
    jdbc.update(
        "INSERT INTO alert_history"
            + " (alert_key, severity, metric_value, metric_threshold, details)"
            + " VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT(alert_key, date(fired_at)) DO NOTHING",
        alertKey,
        severity.name(),
        metricValue,
        metricThreshold,
        details);
    try {
      // Re-select the day's row id (whether we just inserted it or lost the ON CONFLICT race). The
      // max-id row for this key IS today's: ids are monotonic, record fires "now", firedWithin
      // gates
      // any prior fire to another day, and ux_ah_key_day caps it at one per UTC day. So ORDER BY id
      // DESC LIMIT 1 returns it with no date('now') midnight-boundary race against fired_at.
      Long id =
          jdbc.queryForObject(
              "SELECT id FROM alert_history WHERE alert_key = ? ORDER BY id DESC LIMIT 1",
              Long.class,
              alertKey);
      return id == null ? -1L : id;
    } catch (EmptyResultDataAccessException e) {
      return -1L;
    }
  }

  /** Latest fire for the given key — empty if never fired. Used by tests + the admin view. */
  public java.util.Optional<Instant> latestFire(String alertKey) {
    try {
      Timestamp ts =
          jdbc.queryForObject(
              "SELECT fired_at FROM alert_history WHERE alert_key = ?"
                  + " ORDER BY fired_at DESC, id DESC LIMIT 1",
              Timestamp.class,
              alertKey);
      return ts == null ? java.util.Optional.empty() : java.util.Optional.of(ts.toInstant());
    } catch (EmptyResultDataAccessException e) {
      return java.util.Optional.empty();
    }
  }

  /** All-time count for tests. */
  public long countFor(String alertKey) {
    Long n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM alert_history WHERE alert_key = ?", Long.class, alertKey);
    return n == null ? 0L : n;
  }
}
