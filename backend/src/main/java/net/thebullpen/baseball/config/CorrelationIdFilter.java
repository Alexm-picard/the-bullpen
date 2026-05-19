package net.thebullpen.baseball.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Propagates a correlation id through MDC for structured JSON logs. */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Correlation-Id";
  private static final String MDC_KEY = "correlation_id";

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
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
