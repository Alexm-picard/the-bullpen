package net.thebullpen.baseball.registry;

import net.thebullpen.baseball.registry.dto.Stage;

/**
 * Base exception for registry-domain failures — sealed so the three subclasses are exhaustive in a
 * pattern-match (the 3a.4 promotion controller maps each to a specific HTTP status).
 */
public sealed class RegistryException extends RuntimeException
    permits RegistryException.ArtifactMissing,
        RegistryException.DuplicateVersion,
        RegistryException.IllegalTransition {

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
}
