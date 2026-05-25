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

  /** Record one alert fire. Returns the inserted id. */
  public long record(
      String alertKey,
      AlertSeverity severity,
      Double metricValue,
      Double metricThreshold,
      String details) {
    jdbc.update(
        "INSERT INTO alert_history (alert_key, severity, metric_value, metric_threshold, details)"
            + " VALUES (?, ?, ?, ?, ?)",
        alertKey,
        severity.name(),
        metricValue,
        metricThreshold,
        details);
    try {
      Long id =
          jdbc.queryForObject(
              "SELECT id FROM alert_history WHERE alert_key = ? ORDER BY fired_at DESC, id DESC"
                  + " LIMIT 1",
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
