package net.thebullpen.baseball.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import net.thebullpen.baseball.api.dto.ApiError;
import org.slf4j.MDC;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Emits the canonical {@link ApiError} envelope for container-level errors that never reach an
 * {@code @RestControllerAdvice}: request paths Tomcat rejects before Spring MVC (malformed
 * percent-encoding, control chars), static 404s, and anything else the servlet container dispatches
 * to {@code /error}. Without this, those responses fall through to Spring Boot's default {@code
 * {timestamp,status,error,path}} JSON, which (a) breaks the ApiError-everywhere promise in the
 * {@link ApiError} javadoc and (b) fails the Schemathesis response-schema conformance check (C-33),
 * since the declared 4xx body is {@code ApiError} but the actual body was Boot's default shape.
 *
 * <p>MVC-level exceptions stay with {@code ApiErrorAdvice}; this only catches what the advice never
 * sees. Providing an {@link ErrorController} bean disables Boot's {@code BasicErrorController}.
 * {@code @Hidden} keeps {@code /error} out of the generated OpenAPI spec (it is infrastructure, not
 * a documented operation, and must not be fuzzed). Scoped to the {@code api} profile; the worker
 * keeps Boot's default handler.
 */
@Hidden
@RestController
@Profile("api")
public class ApiErrorController implements ErrorController {

  @RequestMapping("/error")
  public ResponseEntity<ApiError> handleError(HttpServletRequest request) {
    HttpStatus status = resolveStatus(request);
    String correlationId = MDC.get("correlation_id");
    ApiError body =
        ApiError.of(
            codeFor(status), status.getReasonPhrase(), correlationId == null ? "" : correlationId);
    return ResponseEntity.status(status).body(body);
  }

  private static HttpStatus resolveStatus(HttpServletRequest request) {
    Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    if (code instanceof Integer statusCode) {
      HttpStatus resolved = HttpStatus.resolve(statusCode);
      if (resolved != null) {
        return resolved;
      }
    }
    return HttpStatus.INTERNAL_SERVER_ERROR;
  }

  /** Status -> error.code, aligned with {@code ApiErrorAdvice}'s vocabulary. */
  private static String codeFor(HttpStatus status) {
    return switch (status) {
      case BAD_REQUEST -> "bad_request";
      case NOT_FOUND -> "not_found";
      case METHOD_NOT_ALLOWED -> "method_not_allowed";
      case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
      case TOO_MANY_REQUESTS -> "rate_limited";
      case SERVICE_UNAVAILABLE -> "service_unavailable";
      default ->
          status.is5xxServerError() ? "internal_error" : status.name().toLowerCase(Locale.ROOT);
    };
  }
}
