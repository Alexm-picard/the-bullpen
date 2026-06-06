package net.thebullpen.baseball.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates a correlation id through MDC for structured JSON logs. Runs first ({@link
 * Ordered#HIGHEST_PRECEDENCE}) so every downstream filter — notably {@link RateLimitFilter}, which
 * stamps the id onto its 429 body — sees the id in MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlation_id";

  /**
   * Best-effort count of requests currently in the filter chain. Diagnostic only: lets the
   * unhandled-exception logger ({@code ApiErrorAdvice.handleAnyOther}) distinguish a
   * load-correlated (concurrency) failure from a lone-request (input) one - the open follow-up on
   * the rare contract-gate 500 (the inference path + rate-limit bucket are already ruled out).
   */
  private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

  public static int inFlight() {
    return IN_FLIGHT.get();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String cid = request.getHeader(HEADER);
    if (cid == null || cid.isBlank()) {
      cid = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, cid);
    response.setHeader(HEADER, cid);
    IN_FLIGHT.incrementAndGet();
    try {
      chain.doFilter(request, response);
    } finally {
      IN_FLIGHT.decrementAndGet();
      MDC.remove(MDC_KEY);
    }
  }
}
