package net.thebullpen.baseball.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import net.thebullpen.baseball.api.dto.ApiError;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A4 - per-IP rate limiting for the compute-bearing and brute-force-prone surfaces: the
 * unauthenticated prediction endpoints ({@code /v1/predict/**}) and player autocomplete ({@code
 * /v1/players/search}), plus the Basic-auth admin paths ({@code /v1/admin/**}), which get a tighter
 * bucket to blunt credential brute-forcing against HTTP Basic. Everything else (Actuator, static
 * assets, the public Ops reads) is unthrottled.
 *
 * <p>Mechanism: a lazy continuous-refill token bucket per (route-class, client-IP), held in a
 * Caffeine cache (already a project dependency) that evicts idle keys after 10 minutes. This is an
 * in-memory, single-node limiter — appropriate for the self-hosted one-box deployment; Cloudflare
 * sits in front for L3/L4 abuse. We deliberately did NOT pull in Bucket4j: its distributed/JCache
 * backends buy nothing here, and Caffeine already covers the in-process case, so this keeps the
 * dependency surface minimal (decision [134]).
 *
 * <p>On rejection the response is a {@code 429} carrying the canonical {@link ApiError} envelope
 * (code {@code rate_limited}) plus {@code Retry-After}. Because a servlet filter is outside MVC,
 * the {@code @RestControllerAdvice} doesn't see it, so the body is written here directly — reusing
 * the same envelope and {@code correlation_id} (read from MDC, which {@link CorrelationIdFilter}
 * sets first by virtue of its higher precedence) so a 429 looks like every other error to the
 * frontend.
 *
 * <p>Disabled (passes everything through) when {@code bullpen.ratelimit.enabled=false} — set in the
 * gradle {@code test} task and the load/contract CI jobs, which generate intentional high volume.
 */
@Component
@Profile("api")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

  private static final String PREDICT_PREFIX = "/v1/predict/";
  private static final String SIMULATE_PREFIX = "/v1/simulate/";
  private static final String SEARCH_PATH = "/v1/players/search";
  private static final String ADMIN_PREFIX = "/v1/admin/";

  private final boolean enabled;
  private final int predictPerMinute;
  private final int simulatePerMinute;
  private final int searchPerMinute;
  private final int adminPerMinute;
  private final List<IpAddressMatcher> trustedProxies;
  private final ObjectMapper objectMapper;
  private final Cache<String, TokenBucket> buckets =
      Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(10)).build();

  public RateLimitFilter(
      @Value("${bullpen.ratelimit.enabled:true}") boolean enabled,
      @Value("${bullpen.ratelimit.predict-per-minute:60}") int predictPerMinute,
      // Simulate is ~12-40x the compute of a single predict (12 per-state ONNX calls + a Markov/MC
      // solve), so it gets its own, tighter bucket rather than sharing the predict class (F1.6).
      @Value("${bullpen.ratelimit.simulate-per-minute:15}") int simulatePerMinute,
      @Value("${bullpen.ratelimit.search-per-minute:120}") int searchPerMinute,
      @Value("${bullpen.ratelimit.admin-per-minute:20}") int adminPerMinute,
      @Value("${bullpen.ratelimit.trusted-proxies:127.0.0.0/8,::1}") List<String> trustedProxies,
      ObjectMapper objectMapper) {
    this.enabled = enabled;
    this.predictPerMinute = predictPerMinute;
    this.simulatePerMinute = simulatePerMinute;
    this.searchPerMinute = searchPerMinute;
    this.adminPerMinute = adminPerMinute;
    // trim so an override like "127.0.0.0/8, ::1" (space after comma) cannot throw at startup.
    this.trustedProxies =
        trustedProxies.stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(IpAddressMatcher::new)
            .toList();
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!enabled) {
      return true;
    }
    String path = request.getRequestURI();
    return !(path.startsWith(PREDICT_PREFIX)
        || path.startsWith(SIMULATE_PREFIX)
        || path.equals(SEARCH_PATH)
        || path.startsWith(ADMIN_PREFIX));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    final String routeClass;
    final int limit;
    if (path.startsWith(ADMIN_PREFIX)) {
      routeClass = "admin";
      limit = adminPerMinute;
    } else if (path.equals(SEARCH_PATH)) {
      routeClass = "search";
      limit = searchPerMinute;
    } else if (path.startsWith(SIMULATE_PREFIX)) {
      routeClass = "simulate";
      limit = simulatePerMinute;
    } else {
      routeClass = "predict";
      limit = predictPerMinute;
    }
    String key = routeClass + "|" + clientIp(request);
    TokenBucket bucket = buckets.get(key, k -> new TokenBucket(limit));
    if (bucket != null && bucket.tryConsume()) {
      chain.doFilter(request, response);
    } else {
      reject(response);
    }
  }

  private void reject(HttpServletResponse response) throws IOException {
    String cid = MDC.get("correlation_id");
    ApiError body =
        ApiError.of(
            "rate_limited", "rate limit exceeded — slow down and retry", cid == null ? "" : cid);
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setHeader(HttpHeaders.RETRY_AFTER, "1");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(body));
  }

  /**
   * Real client IP behind Cloudflare Tunnel: CF-Connecting-IP, then X-Forwarded-For, then remote.
   *
   * <p>The forwarded headers are honored only when the request actually arrived from a trusted
   * proxy hop ({@code bullpen.ratelimit.trusted-proxies}; default loopback, where the on-box
   * cloudflared connects from). Anything hitting the port directly could otherwise rotate
   * CF-Connecting-IP per request and mint itself a fresh bucket every time, defeating the per-IP
   * limit entirely - for an off-tunnel caller the peer address is the only trustworthy identity.
   */
  private String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (!isTrustedProxy(remote)) {
      return remote;
    }
    String cf = request.getHeader("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) {
      return cf.trim();
    }
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      int comma = xff.indexOf(',');
      return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }
    return remote;
  }

  private boolean isTrustedProxy(String remoteAddr) {
    if (remoteAddr == null) {
      return false;
    }
    for (IpAddressMatcher matcher : trustedProxies) {
      if (matcher.matches(remoteAddr)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Lazy continuous-refill token bucket: {@code capacity == perMinute}, refilling to full over 60s.
   * {@code synchronized} because one bucket can be hit by concurrent requests from the same IP.
   */
  static final class TokenBucket {
    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;

    TokenBucket(int perMinute) {
      this.capacity = perMinute;
      this.refillPerNano = perMinute / 60_000_000_000.0;
      this.tokens = perMinute;
      this.lastNanos = System.nanoTime();
    }

    synchronized boolean tryConsume() {
      long now = System.nanoTime();
      tokens = Math.min(capacity, tokens + (now - lastNanos) * refillPerNano);
      lastNanos = now;
      if (tokens >= 1.0) {
        tokens -= 1.0;
        return true;
      }
      return false;
    }
  }
}
