package net.thebullpen.baseball.registry.dto;

import java.util.Objects;

/**
 * Required token for {@code RegistryService.registerWithBootstrap(...)} — the escape hatch that
 * archives every prior version of a model and re-bootstraps the feature pipeline.
 *
 * <p>The caller must explicitly construct this with the same {@code modelName} they're about to
 * re-bootstrap AND a free-form {@code reason} (logged at INFO + persisted in the new version's
 * {@code notes} field) so the post-hoc audit trail records why a breaking schema change was
 * justified.
 *
 * <p>This is intentionally a tiny record rather than a boolean flag — a typo'd {@code true} for a
 * "force" parameter has nuked prior versions before; requiring a typed token + matching modelName +
 * a written reason creates enough friction that the operation is deliberate.
 */
public record ResetFeatureSchemaConfirmation(String modelName, String reason) {

  public ResetFeatureSchemaConfirmation {
    Objects.requireNonNull(modelName, "modelName");
    Objects.requireNonNull(reason, "reason");
    if (modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    if (reason.isBlank()) {
      throw new IllegalArgumentException(
          "reason must not be blank — describe why this breaking schema change is justified");
    }
  }
}
