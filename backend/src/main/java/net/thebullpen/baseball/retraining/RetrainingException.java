package net.thebullpen.baseball.retraining;

/**
 * Sealed exception for retraining-queue failures. Same pattern as {@code
 * net.thebullpen.baseball.registry.RegistryException} — admin controller pattern-matches each
 * subclass to a specific HTTP status.
 */
public sealed class RetrainingException extends RuntimeException
    permits RetrainingException.UnknownTrigger,
        RetrainingException.DuplicateTriggerId,
        RetrainingException.InvalidStateTransition {

  protected RetrainingException(String message) {
    super(message);
  }

  protected RetrainingException(String message, Throwable cause) {
    super(message, cause);
  }

  /** No row in {@code retraining_queue} for the given trigger_id. */
  public static final class UnknownTrigger extends RetrainingException {
    public UnknownTrigger(String triggerId) {
      super("retraining: no queue row with trigger_id " + triggerId);
    }
  }

  /**
   * Insertion violated the {@code trigger_id UNIQUE} constraint — same trigger was already
   * enqueued. Idempotent on the caller side: enqueue with the same trigger id twice should surface
   * this exception so callers can no-op.
   */
  public static final class DuplicateTriggerId extends RetrainingException {
    public DuplicateTriggerId(String triggerId, Throwable cause) {
      super("retraining: trigger_id " + triggerId + " already enqueued", cause);
    }
  }

  /**
   * Attempted complete / cancel on a row that's not in the right starting state. Mirrors the
   * QueueStatus.isTerminal() check — terminal rows are immutable.
   */
  public static final class InvalidStateTransition extends RetrainingException {
    public InvalidStateTransition(String triggerId, String current, String attempted) {
      super(
          "retraining: cannot "
              + attempted
              + " trigger "
              + triggerId
              + " — current status is "
              + current);
    }
  }
}
