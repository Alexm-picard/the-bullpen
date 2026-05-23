package net.thebullpen.baseball.api.dto;

import java.util.List;

/**
 * Canonical error envelope. Every non-2xx response from the api profile uses this shape so the
 * frontend can render one error component for all failures.
 *
 * <p>{@code details} carries field-level validation messages when {@code code=validation_failed};
 * empty otherwise.
 */
public record ApiError(Body error) {

  /** Inner body — kept as a separate record so callers see {@code error.code} etc. */
  public record Body(String code, String message, String correlationId, List<FieldError> details) {}

  public record FieldError(String field, String message) {}

  public static ApiError of(String code, String message, String correlationId) {
    return new ApiError(new Body(code, message, correlationId, List.of()));
  }

  public static ApiError validation(
      String message, String correlationId, List<FieldError> details) {
    return new ApiError(new Body("validation_failed", message, correlationId, details));
  }
}
