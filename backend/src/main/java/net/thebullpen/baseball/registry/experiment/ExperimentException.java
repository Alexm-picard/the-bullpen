package net.thebullpen.baseball.registry.experiment;

/**
 * Sealed exception for experiment-domain failures. Same pattern as {@code
 * net.thebullpen.baseball.registry.RegistryException} from 3a — the admin controller
 * pattern-matches each subclass to a specific HTTP status.
 */
public sealed class ExperimentException extends RuntimeException
    permits ExperimentException.UnknownExperiment,
        ExperimentException.AlreadyRunning,
        ExperimentException.InvalidStateTransition,
        ExperimentException.InsufficientSampleSize,
        ExperimentException.OfflineGateInvalid {

  protected ExperimentException(String message) {
    super(message);
  }

  /** No row in {@code experiment_results} for the given id. */
  public static final class UnknownExperiment extends ExperimentException {
    public UnknownExperiment(long experimentId) {
      super("experiment: no experiment_results row with id " + experimentId);
    }
  }

  /**
   * Another experiment is already in {@code running} status for this model_name — leaf "Known edge
   * cases": one running experiment per model_name in v1 to keep the paired-data join unambiguous.
   */
  public static final class AlreadyRunning extends ExperimentException {
    public AlreadyRunning(String modelName, long existingExperimentId) {
      super(
          "experiment: model "
              + modelName
              + " already has a running experiment (id="
              + existingExperimentId
              + ") — complete or abort it first");
    }
  }

  /**
   * Trying to evaluate / complete / abort an experiment that's not in {@code running} status.
   * Terminal statuses (passed / failed / aborted) are immutable.
   */
  public static final class InvalidStateTransition extends ExperimentException {
    public InvalidStateTransition(long experimentId, String currentStatus, String attempted) {
      super(
          "experiment: cannot "
              + attempted
              + " experiment "
              + experimentId
              + " — current status "
              + currentStatus
              + " is terminal");
    }
  }

  /**
   * {@code complete} called before {@code sample_size_observed >= sample_size_target}. The admin
   * can either wait + retry or {@code abort} the experiment.
   */
  public static final class InsufficientSampleSize extends ExperimentException {
    public InsufficientSampleSize(long experimentId, long observed, long target) {
      super(
          "experiment: cannot complete experiment "
              + experimentId
              + " — observed sample size "
              + observed
              + " < target "
              + target
              + " (wait for more data or call abort)");
    }
  }

  /**
   * The committed offline-gate artifact failed validation at {@code import-offline}: not bundled,
   * not a self-consistent PASS, sample-size short, or the champion/challenger binding is wrong.
   * Maps to 422 - the evidence cannot become a passing experiment_results row. See ADR-0012 /
   * decision [166].
   */
  public static final class OfflineGateInvalid extends ExperimentException {
    public OfflineGateInvalid(String message) {
      super("offline-gate import: " + message);
    }
  }
}
