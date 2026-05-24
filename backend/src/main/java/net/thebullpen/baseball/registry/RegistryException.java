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
        RegistryException.PromotionCriteriaMissing {

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
      super(
          "registry: cannot promote "
              + modelName
              + "/"
              + version
              + " (id="
              + challengerVersionId
              + ") to CHAMPION — no passing experiment_results row found"
              + " (rule 5 + decision [72]: pre-declared promotion criteria + recorded passing"
              + " experiment required before promotion)");
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
}
