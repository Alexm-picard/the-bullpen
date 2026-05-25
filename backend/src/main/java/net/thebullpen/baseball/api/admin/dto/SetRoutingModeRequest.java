package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import net.thebullpen.baseball.inference.routing.RoutingMode;

/**
 * {@code POST /v1/admin/routing/{name}/mode} body — flips a model's routing mode (SHADOW ↔ AB).
 * Mirrors {@link PromoteRequest}'s case-insensitive enum parsing so the API surface stays stable
 * across rename refactors of the enum itself.
 */
public record SetRoutingModeRequest(@NotBlank String mode, @NotBlank String reason) {

  public RoutingMode parseMode() {
    return RoutingMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
  }
}
