package net.thebullpen.baseball.data;

import java.sql.ResultSet;
import java.util.List;
import net.thebullpen.baseball.api.dto.OpsEvent;
import net.thebullpen.baseball.api.dto.OpsEventType;
import net.thebullpen.baseball.api.dto.OpsEventsPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Read/append access to {@code ops_events} (V015), the registry-SQLite-backed ops-event log behind
 * {@code GET /v1/ops/events}. Mirrors {@code AlertHistoryRepository}'s shape (direct {@link
 * JdbcTemplate} over the primary SQLite datasource).
 *
 * <p>{@link #record} is best-effort by contract: callers (the registry admin paths, the deploy
 * script, the drift alerter) must never let an event-log failure break the operation that triggered
 * it, so they wrap this call in a try/catch — see {@code RegistryAdminController}.
 */
@Repository
public class OpsEventsRepository {

  private static final RowMapper<OpsEvent> MAPPER =
      (ResultSet rs, int n) ->
          new OpsEvent(
              rs.getLong("id"),
              // occurred_at is UTC (CURRENT_TIMESTAMP); read tz-explicitly so the box's ET JVM
              // does not shift it +4h (the registry/ops audit-timestamp skew).
              JdbcTimes.utcInstant(rs, "occurred_at"),
              OpsEventType.valueOf(rs.getString("type")),
              rs.getString("detail"));

  private final JdbcTemplate jdbc;

  public OpsEventsRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Append one event. {@code detail} is truncated defensively at 500 chars. */
  public void record(OpsEventType type, String detail) {
    String trimmed = detail == null ? "" : detail;
    if (trimmed.length() > 500) {
      trimmed = trimmed.substring(0, 500);
    }
    jdbc.update("INSERT INTO ops_events (type, detail) VALUES (?, ?)", type.name(), trimmed);
  }

  private static final String SELECT_RECENT =
      "SELECT id, occurred_at, type, detail FROM ops_events ORDER BY occurred_at DESC, id DESC"
          + " LIMIT ? OFFSET ?";

  /** Most-recent {@code limit} events, newest first (convenience over {@link #findRecentPage}). */
  public List<OpsEvent> findRecent(int limit) {
    int capped = Math.max(1, Math.min(limit, 200));
    return findRecentPage(0, capped).rows();
  }

  /**
   * A newest-first page of events. Over-fetches one row ({@code LIMIT size + 1}) to decide {@code
   * hasNext} without a count query, then trims the overflow row - the same offset-pagination idiom
   * as {@link LivePitchesRepository#findPostPredictions}. {@code page}/{@code size} bounds are
   * validated at the controller.
   */
  public OpsEventsPage findRecentPage(int page, int size) {
    int limit = size + 1;
    long offset = (long) page * size;
    List<OpsEvent> rows = jdbc.query(SELECT_RECENT, MAPPER, limit, offset);
    boolean hasNext = rows.size() > size;
    List<OpsEvent> pageRows = hasNext ? List.copyOf(rows.subList(0, size)) : rows;
    return new OpsEventsPage(pageRows, page, size, hasNext);
  }
}
