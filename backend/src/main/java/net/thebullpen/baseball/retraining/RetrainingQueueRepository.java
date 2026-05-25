package net.thebullpen.baseball.retraining;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.retraining.dto.QueueStatus;
import net.thebullpen.baseball.retraining.dto.RetrainingTrigger;
import net.thebullpen.baseball.retraining.dto.TriggerType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate access to {@code retraining_queue} (V013). The hot operation is {@link
 * #claimNextQueued}: atomic "select the oldest queued row, flip it to running, return only if we
 * won." Backs {@link RetrainingQueueService}'s concurrent-safe claim semantics. The atomic
 * UPDATE-with-WHERE pattern serves where Postgres would use {@code SELECT ... FOR UPDATE SKIP
 * LOCKED}; SQLite's single-writer guarantee makes the race-free property fall out for free.
 */
@Repository
public class RetrainingQueueRepository {

  private static final String SELECT_ALL =
      "SELECT id, trigger_id, model_name, trigger_type, trigger_metadata, status,"
          + " enqueued_at, started_at, finished_at, produced_version_id, error_message"
          + " FROM retraining_queue";

  private final JdbcTemplate jdbc;

  public RetrainingQueueRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // --- writes -----------------------------------------------------------

  /**
   * Insert a new queued trigger. Throws {@link RetrainingException.DuplicateTriggerId} on the
   * UNIQUE(trigger_id) violation — callers treat that as idempotent no-op.
   */
  public RetrainingTrigger insertQueued(
      String triggerId, String modelName, TriggerType type, String metadataJson) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    try {
      jdbc.update(
          conn -> {
            PreparedStatement ps =
                conn.prepareStatement(
                    "INSERT INTO retraining_queue (trigger_id, model_name, trigger_type,"
                        + " trigger_metadata, status) VALUES (?, ?, ?, ?, 'queued')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, triggerId);
            ps.setString(2, modelName);
            ps.setString(3, type.dbValue());
            ps.setString(4, metadataJson);
            return ps;
          },
          keyHolder);
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.springframework.jdbc.UncategorizedSQLException e) {
      // sqlite-jdbc surfaces UNIQUE violations as either DataIntegrityViolationException (Spring's
      // typed code-mapped flavor) or UncategorizedSQLException (the SQLite-fallback flavor); we
      // map both to the typed RetrainingException so the service can pattern-match.
      throw new RetrainingException.DuplicateTriggerId(triggerId, e);
    }
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("INSERT into retraining_queue returned no generated key");
    }
    return findById(key.longValue())
        .orElseThrow(
            () ->
                new IllegalStateException("retraining_queue row vanished after insert: id=" + key));
  }

  /**
   * Atomic "claim the oldest queued row." UPDATE-with-WHERE so two concurrent claimers can't both
   * win — the second's UPDATE will affect 0 rows because the WHERE clause checks status. Returns
   * the freshly-running row if we won, empty if no queued rows existed.
   */
  public Optional<RetrainingTrigger> claimNextQueued() {
    Optional<RetrainingTrigger> candidate =
        jdbc
            .query(
                SELECT_ALL + " WHERE status = 'queued' ORDER BY enqueued_at ASC, id ASC LIMIT 1",
                MAPPER)
            .stream()
            .findFirst();
    if (candidate.isEmpty()) {
      return Optional.empty();
    }
    long id = candidate.get().id();
    int updated =
        jdbc.update(
            "UPDATE retraining_queue SET status = 'running', started_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'queued'",
            id);
    if (updated == 0) {
      // Another worker beat us. Caller can retry; for simplicity we return empty here.
      return Optional.empty();
    }
    return findById(id);
  }

  /**
   * Mark a running trigger terminal — success or failure. {@code producedVersionId} is set on
   * success only; {@code errorMessage} on failure only. Returns rows-updated (0 if id missing or
   * already terminal).
   */
  public int markComplete(
      String triggerId, boolean succeeded, Long producedVersionId, String errorMessage) {
    String terminal = succeeded ? "succeeded" : "failed";
    return jdbc.update(
        "UPDATE retraining_queue SET status = ?, finished_at = CURRENT_TIMESTAMP,"
            + " produced_version_id = ?, error_message = ? WHERE trigger_id = ?"
            + " AND status = 'running'",
        terminal,
        producedVersionId,
        errorMessage,
        triggerId);
  }

  /**
   * Cancel a queued or running trigger. Returns rows-updated; 0 means the row was already terminal
   * (service should surface InvalidStateTransition).
   */
  public int cancel(String triggerId) {
    return jdbc.update(
        "UPDATE retraining_queue SET status = 'cancelled', finished_at = CURRENT_TIMESTAMP"
            + " WHERE trigger_id = ? AND status IN ('queued', 'running')",
        triggerId);
  }

  /**
   * Reaper: flip {@code running} rows older than {@code staleAfter} back to {@code queued} so a
   * crashed worker doesn't strand the trigger. Caller invokes this from the 3d.4 stale-claim job.
   * Returns the rows touched.
   */
  public int reapStaleClaims(java.time.Duration staleAfter) {
    Instant cutoff = Instant.now().minus(staleAfter);
    return jdbc.update(
        "UPDATE retraining_queue SET status = 'queued', started_at = NULL"
            + " WHERE status = 'running' AND started_at <= ?",
        Timestamp.from(cutoff));
  }

  // --- reads ------------------------------------------------------------

  public Optional<RetrainingTrigger> findById(long id) {
    try {
      RetrainingTrigger row = jdbc.queryForObject(SELECT_ALL + " WHERE id = ?", MAPPER, id);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  public Optional<RetrainingTrigger> findByTriggerId(String triggerId) {
    try {
      RetrainingTrigger row =
          jdbc.queryForObject(SELECT_ALL + " WHERE trigger_id = ?", MAPPER, triggerId);
      return Optional.ofNullable(row);
    } catch (EmptyResultDataAccessException e) {
      return Optional.empty();
    }
  }

  /** Every trigger for one model, newest-first. Admin views + debugging. */
  public List<RetrainingTrigger> findByModel(String modelName) {
    return jdbc.query(
        SELECT_ALL + " WHERE model_name = ? ORDER BY enqueued_at DESC, id DESC", MAPPER, modelName);
  }

  /** All currently-queued rows across all models. */
  public List<RetrainingTrigger> findAllQueued() {
    return jdbc.query(
        SELECT_ALL + " WHERE status = 'queued' ORDER BY enqueued_at ASC, id ASC", MAPPER);
  }

  // --- mapping ----------------------------------------------------------

  private static final RowMapper<RetrainingTrigger> MAPPER =
      (ResultSet rs, int n) -> {
        long rawVersion = rs.getLong("produced_version_id");
        Long producedVersionId = rs.wasNull() ? null : rawVersion;
        return new RetrainingTrigger(
            rs.getLong("id"),
            rs.getString("trigger_id"),
            rs.getString("model_name"),
            TriggerType.fromDbValue(rs.getString("trigger_type")),
            rs.getString("trigger_metadata"),
            QueueStatus.fromDbValue(rs.getString("status")),
            toInstant(rs.getTimestamp("enqueued_at")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")),
            producedVersionId,
            rs.getString("error_message"));
      };

  private static Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant().truncatedTo(ChronoUnit.MILLIS);
  }
}
