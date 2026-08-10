package net.thebullpen.baseball.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import net.thebullpen.baseball.api.dto.ApiError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Locks the container-error mapping so {@code /error} keeps returning the {@code ApiError} envelope
 * (which C-33's Schemathesis response-schema conformance depends on) as the code evolves. Uses a
 * real Spring {@link MockHttpServletRequest} - no Mockito.
 */
class ApiErrorControllerTest {

  private final ApiErrorController controller = new ApiErrorController();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private ResponseEntity<ApiError> dispatch(Object statusAttr) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    if (statusAttr != null) {
      request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusAttr);
    }
    return controller.handleError(request);
  }

  @Test
  void mapsKnownStatusesToApiErrorCodes() {
    assertMapped(dispatch(400), HttpStatus.BAD_REQUEST, "bad_request");
    assertMapped(dispatch(404), HttpStatus.NOT_FOUND, "not_found");
    assertMapped(dispatch(405), HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed");
    assertMapped(dispatch(415), HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type");
    assertMapped(dispatch(429), HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
    assertMapped(dispatch(503), HttpStatus.SERVICE_UNAVAILABLE, "service_unavailable");
  }

  @Test
  void fallsBackTo500OnMissingOrUnresolvableStatus() {
    assertMapped(dispatch(null), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
    assertMapped(dispatch(799), HttpStatus.INTERNAL_SERVER_ERROR, "internal_error");
  }

  @Test
  void correlationIdEmptyWhenAbsentAndEchoedWhenPresent() {
    assertThat(dispatch(400).getBody().error().correlationId()).isEmpty();
    MDC.put("correlation_id", "cid-123");
    assertThat(dispatch(400).getBody().error().correlationId()).isEqualTo("cid-123");
  }

  private static void assertMapped(
      ResponseEntity<ApiError> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    ApiError body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.error().code()).isEqualTo(code);
    assertThat(body.error().message()).isEqualTo(status.getReasonPhrase());
    assertThat(body.error().details()).isEmpty();
  }
}
