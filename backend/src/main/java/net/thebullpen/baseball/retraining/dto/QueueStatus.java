package net.thebullpen.baseball.retraining.dto;

import java.util.Locale;
import java.util.Set;

/**
 * Mirror of the V013 {@code retraining_queue.status} CHECK. Five states:
 *
 * <pre>
 *   queued ──→ running ──┬──→ succeeded
 *                        ├──→ failed
 *                        └──→ cancelled  (admin cancel during running; Python job aborts)
 *   queued ──→ cancelled (admin cancel before claim)
 * </pre>
 *
 * <p>Terminal: SUCCEEDED, FAILED, CANCELLED — the row never moves once it lands in one of these.
 * The 3d.4 stale-claim reaper resurrects {@code running} rows older than 4h back to {@code queued}
 * for retry; that's not a state transition, that's a write under the same row.
 */
public enum QueueStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public String dbValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static QueueStatus fromDbValue(String s) {
    return QueueStatus.valueOf(s.toUpperCase(Locale.ROOT));
  }

  /** Terminal statuses — no further transitions. */
  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  private static final Set<QueueStatus> TERMINAL = Set.of(SUCCEEDED, FAILED, CANCELLED);
}
