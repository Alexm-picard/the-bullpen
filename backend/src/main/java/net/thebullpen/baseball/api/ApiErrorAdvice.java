package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import net.thebullpen.baseball.api.dto.ApiError;
import net.thebullpen.baseball.api.dto.ApiError.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps controller exceptions to the canonical {@link ApiError} envelope (Phase 1.5).
 *
 * <p>Internal errors deliberately do NOT leak the exception message — the frontend gets {@code
 * code=internal_error} with a generic message; the full stack lives in the structured log keyed by
 * correlation id.
 */
@RestControllerAdvice
public class ApiErrorAdvice {

  private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
    List<FieldError> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
    ApiError body =
        ApiError.validation("one or more fields failed validation", correlationId(), details);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
    Throwable cause = ex.getMostSpecificCause();
    String code =
        cause instanceof JsonProcessingException ? "malformed_json" : "unreadable_request";
    ApiError body = ApiError.of(code, "request body could not be parsed", correlationId());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex) {
    ApiError body =
        ApiError.of(
            "unsupported_media_type", "content-type must be application/json", correlationId());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
    // FeaturePipeline throws this on unknown park / null required numeric.
    ApiError body = ApiError.of("invalid_input", ex.getMessage(), correlationId());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleAnyOther(Exception ex) {
    String cid = correlationId();
    log.error("unhandled exception correlation_id={}", cid, ex);
    ApiError body = ApiError.of("internal_error", "an unexpected error occurred", cid);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  private static String correlationId() {
    String cid = MDC.get("correlation_id");
    return cid == null ? "" : cid;
  }
}
