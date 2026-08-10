package net.thebullpen.baseball.api;

import org.springframework.http.HttpStatus;

/**
 * A 503 (or other status) with a stable, machine-readable {@code code} that survives into the
 * {@link net.thebullpen.baseball.api.dto.ApiError} envelope. Unlike {@link
 * org.springframework.web.server.ResponseStatusException}, whose code is derived from the HTTP
 * status name ("service_unavailable" for every 503), this exception lets two semantically different
 * conditions produce distinct codes so callers can distinguish them programmatically (#401).
 */
public class CodedServiceException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  public CodedServiceException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
