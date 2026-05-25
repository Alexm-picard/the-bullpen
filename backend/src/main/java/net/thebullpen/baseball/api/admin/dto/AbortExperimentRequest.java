package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /v1/admin/experiments/{id}/abort} body. */
public record AbortExperimentRequest(@NotBlank String reason) {}
