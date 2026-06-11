package net.thebullpen.baseball.registry;

import net.thebullpen.baseball.registry.dto.Stage;

/**
 * Base exception for registry-domain failures — sealed so the three subclasses are exhaustive in a
 * pattern-match (the 3a.4 promotion controller maps each to a specific HTTP status).
 */
public sealed class RegistryException extends RuntimeException
    permits RegistryException.ArtifactMissing,
        RegistryException.DuplicateVersion,
        RegistryException.IllegalTransition,
        RegistryException.FeatureSchemaMismatch,
        RegistryException.ResetConfirmationMissing,
        RegistryException.PromotionCriteriaMissing,
        RegistryException.BaselineMissing,
        RegistryException.ModelLoadFailed {

  protected RegistryException(String message) {
    super(message);
  }

  protected RegistryException(String message, Throwable cause) {
    super(message, cause);
  }

  /** A required artifact file is not on disk at registration time. */
  public static final class ArtifactMissing extends RegistryException {
    public ArtifactMissing(String path) {
      super("registry: artifact file does not exist on disk: " + path);
    }

    public ArtifactMissing(String path, Throwable cause) {
      super("registry: artifact file does not exist on disk: " + path, cause);
    }
  }

  /** A {@code (model_name, version)} pair is already in the registry. */
  public static final class DuplicateVersion extends RegistryException {
    public DuplicateVersion(String modelName, String version) {
      super("registry: duplicate model version: " + modelName + "/" + version);
    }
  }

  /** A {@link Stage} transition is not in the allowed-transitions set (see Stage javadoc). */
  public static final class IllegalTransition extends RegistryException {
    public IllegalTransition(Stage from, Stage to) {
      super(
          "registry: illegal stage transition "
              + from
              + " -> "
              + to
              + " (allowed targets from "
              + from
              + ": "
              + from.allowedTargets()
              + ")");
    }
  }

  /**
   * The candidate's feature pipeline hash does not match the model's production pipeline hash —
   * rule 7 + decision [67]. Closing Risk Register G3 at the registration boundary.
   */
  public static final class FeatureSchemaMismatch extends RegistryException {
    private final String modelName;
    private final String productionHash;
    private final String candidateHash;

    public FeatureSchemaMismatch(String modelName, String productionHash, String candidateHash) {
      super(
          "registry: feature schema hash mismatch for model "
              + modelName
              + " — production="
              + productionHash
              + " candidate="
              + candidateHash
              + " (use registerWithBootstrap with explicit reset confirmation if this is a"
              + " deliberate breaking change)");
      this.modelName = modelName;
      this.productionHash = productionHash;
      this.candidateHash = candidateHash;
    }

    public String modelName() {
      return modelName;
    }

    public String productionHash() {
      return productionHash;
    }

    public String candidateHash() {
      return candidateHash;
    }
  }

  /**
   * {@code registerWithBootstrap} was called without an explicit reset confirmation token — guards
   * the escape hatch so a typo in calling code can't archive every prior version of a model.
   */
  public static final class ResetConfirmationMissing extends RegistryException {
    public ResetConfirmationMissing(String modelName) {
      super(
          "registry: registerWithBootstrap("
              + modelName
              + ") called without a ResetFeatureSchemaConfirmation token — refusing to archive"
              + " prior versions");
    }
  }

  /**
   * A SHADOW → CHAMPION (or CANDIDATE → CHAMPION when a prior version exists) promotion was
   * attempted without a passing {@code experiment_results} row for the challenger. Closes rule 5 /
   * decision [72]: no champion promotion without pre-declared promotion criteria + a recorded
   * passing experiment.
   *
   * <p>Bootstrap exemption: when a model has exactly one ever-registered version (the one being
   * promoted), the gate is skipped — there's no prior baseline to evaluate against, so requiring an
   * experiment row would be impossible. Leaf 3a.4 "Known edge cases".
   */
  public static final class PromotionCriteriaMissing extends RegistryException {
    private final String modelName;
    private final long challengerVersionId;

    public PromotionCriteriaMissing(String modelName, long challengerVersionId, String version) {
      this(
          modelName,
          challengerVersionId,
          version,
          "no passing experiment_results row found"
              + " (rule 5 + decision [72]: pre-declared promotion criteria + recorded passing"
              + " experiment required before promotion)");
    }

    /** B2 variant: the gate found rows but none acceptable - {@code detail} says why. */
    public PromotionCriteriaMissing(
        String modelName, long challengerVersionId, String version, String detail) {
      super(
          "registry: cannot promote "
              + modelName
              + "/"
              + version
              + " (id="
              + challengerVersionId
              + ") to CHAMPION — "
              + detail);
      this.modelName = modelName;
      this.challengerVersionId = challengerVersionId;
    }

    public String modelName() {
      return modelName;
    }

    public long challengerVersionId() {
      return challengerVersionId;
    }
  }

  /**
   * B4 / rule 9: the primary head's partner LR baseline has never been registered (no non-archived
   * version exists), so the primary cannot reach CHAMPION. "No primary without a baseline"
   * (decisions [37] [46]) previously lived only in the Python dry-run gate. Maps to 409 like the
   * other promotion-governance failures.
   */
  public static final class BaselineMissing extends RegistryException {
    public BaselineMissing(String modelName, String version, String baselineModelName) {
      super(
          "registry: cannot promote "
              + modelName
              + "/"
              + version
              + " to CHAMPION — partner baseline '"
              + baselineModelName
              + "' has no registered (non-archived) version (rule 9: no primary without its LR"
              + " baseline; register the baseline first)");
    }
  }

  /**
   * INC-2 (decision [151]): the model's snapshot won't load + run a forward pass through the
   * serving loader. Catches an incomplete copy-set (missing {@code model.onnx.data} / {@code
   * calibrator.json}), a wrong-format calibrator, or any ONNX/pipeline wiring failure - the things
   * that otherwise only surface as a 500 at serving (the 2026-06-07 promotion incident). Maps to
   * 422.
   */
  public static final class ModelLoadFailed extends RegistryException {
    public ModelLoadFailed(String modelName, String version, long id, Throwable cause) {
      super(
          "registry: model "
              + modelName
              + "/"
              + version
              + " (id="
              + id
              + ") failed to load + predict via the serving path: "
              + (cause.getMessage() == null
                  ? cause.getClass().getSimpleName()
                  : cause.getMessage()),
          cause);
    }
  }
}
