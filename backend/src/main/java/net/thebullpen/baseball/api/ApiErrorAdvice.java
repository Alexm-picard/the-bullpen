package net.thebullpen.baseball.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import net.thebullpen.baseball.api.dto.ApiError;
import net.thebullpen.baseball.api.dto.ApiError.FieldError;
import net.thebullpen.baseball.config.CorrelationIdFilter;
import net.thebullpen.baseball.inference.ModelUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException ex) {
    ApiError body =
        ApiError.of(
            "method_not_allowed",
            "request method '" + ex.getMethod() + "' is not supported",
            correlationId());
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
    ApiError body =
        ApiError.validation(
            "required parameter is missing",
            correlationId(),
            List.of(new FieldError(ex.getParameterName(), "must be present")));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex) {
    ApiError body = ApiError.of("not_found", "no endpoint at this path", correlationId());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
    ApiError body = ApiError.of("invalid_input", ex.getMessage(), correlationId());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * A path/query variable that can't bind to its declared type — e.g. a non-numeric {@code
   * versionId} on {@code /v1/ops/registry/{modelName}/{versionId}}. Spring throws this during
   * argument resolution; without a handler it falls through to the generic 500. A malformed path
   * variable is a client error, so map it to 400. (Found by the Schemathesis contract job, S1f.)
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String message =
        "path or query parameter '" + ex.getName() + "' has an invalid value for its type";
    ApiError body = ApiError.of("invalid_input", message, correlationId());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /**
   * Map {@link ResponseStatusException} (thrown by controllers for non-validation client errors
   * like "missing required field for this dispatch path" or "post head not loaded") through our
   * envelope instead of Spring's default ProblemDetail. Status code is preserved; {@code
   * error.code} is derived from the status reason. Added in 2b.3 for the {@code ?head=post}
   * dispatch path.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    String code = status.name().toLowerCase().replace(' ', '_');
    String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
    ApiError body = ApiError.of(code, message, correlationId());
    return ResponseEntity.status(status).body(body);
  }

  /**
   * A registered model exists but cannot be loaded or served right now (stale/archived snapshot,
   * missing artifact, ORT load failure at serve time). This is a transient condition, not a bad
   * request and not an opaque internal bug, so it maps to 503 (retryable) rather than 500. Only the
   * {@link ModelUnavailableException} subtype is singled out; a plain {@link IllegalStateException}
   * (a contract/programming bug) has no handler here and still falls through to {@link
   * #handleAnyOther} as a 500. The cause is logged (so a genuinely broken model is still visible)
   * but its message is not leaked to the client. (C2.)
   */
  @ExceptionHandler(ModelUnavailableException.class)
  public ResponseEntity<ApiError> handleModelUnavailable(ModelUnavailableException ex) {
    String cid = correlationId();
    log.warn("model unavailable correlation_id={} message={}", cid, ex.getMessage(), ex);
    ApiError body = ApiError.of("model_unavailable", "the model is temporarily unavailable", cid);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleAnyOther(Exception ex) {
    String cid = correlationId();
    // Context for the open rare-500 follow-up so the NEXT occurrence self-classifies:
    //   - high in_flight -> a load/concurrency race, not a bad request;
    //   - low uptime_ms (fresh boot) -> the WarmupReadiness window race (a request landing before
    //     warmup completes), the cheap CI-only candidate; readiness-gated boot-waits should remove
    // it.
    // If a 500 still logs with high uptime + low in_flight, it's a genuine input, captured here.
    log.error(
        "unhandled exception correlation_id={} thread={} in_flight={} uptime_ms={}",
        cid,
        Thread.currentThread().getName(),
        CorrelationIdFilter.inFlight(),
        java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime(),
        ex);
    ApiError body = ApiError.of("internal_error", "an unexpected error occurred", cid);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }

  private static String correlationId() {
    String cid = MDC.get("correlation_id");
    return cid == null ? "" : cid;
  }
}
