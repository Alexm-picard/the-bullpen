package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /v1/admin/retrain} body — leaf 3d.2. {@code reason} required + logged at the
 * manual-trigger boundary (same audit-trail intent as PromoteRequest / SetChallengerRequest /
 * StartExperimentRequest).
 */
public record ManualRetrainRequest(@NotBlank String modelName, @NotBlank String reason) {}
