package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import net.thebullpen.baseball.registry.dto.Stage;

/**
 * Request body for {@code POST /v1/admin/registry/{model_name}/promote/{version_id}}.
 *
 * <p>{@code targetStage} is a case-insensitive string matching one of {@link Stage} so the API
 * surface can stay stable across rename refactors of the enum itself. {@code reason} is required
 * (decision [29] + leaf 3a.4 audit-trail intent): every promotion is a deliberate operator action
 * and the reason lands in {@code notes} on the row's audit log later — for now we log it.
 */
public record PromoteRequest(@NotBlank String targetStage, @NotBlank String reason) {

  /** Parse {@code targetStage} into a {@link Stage}, throwing {@link IllegalArgumentException}. */
  public Stage parseTargetStage() {
    return Stage.valueOf(targetStage.trim().toUpperCase(Locale.ROOT));
  }
}
