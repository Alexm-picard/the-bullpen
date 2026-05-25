package net.thebullpen.baseball.api.admin.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /v1/admin/routing/{name}/traffic-pct} body. {@code pct} validated [0, 100] both here
 * (early reject at HTTP boundary, returns 400) and in {@code RoutingService.setTrafficPct} (defense
 * in depth + the SQLite CHECK constraint).
 */
public record SetTrafficPctRequest(
    @DecimalMin("0.0") @DecimalMax("100.0") double pct, @NotBlank String reason) {}
