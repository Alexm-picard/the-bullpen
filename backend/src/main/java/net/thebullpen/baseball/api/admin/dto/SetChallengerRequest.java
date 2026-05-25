package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /v1/admin/routing/{name}/challenger} body. The reason is logged at the service
 * boundary — same audit-trail intent as {@link PromoteRequest}.
 */
public record SetChallengerRequest(@Positive long challengerVersionId, @NotBlank String reason) {}
