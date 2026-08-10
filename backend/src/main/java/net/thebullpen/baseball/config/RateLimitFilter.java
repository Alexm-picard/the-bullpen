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
 * bucket to blunt credential brute-forcing against HTTP Basic. The public ClickHouse-backed reads
 * ({@code /v1/ops/**}, {@code /v1/games/**}, {@code /v1/matchups/**}, and the {@code
 * /v1/players/**} profile/roster/batted-ball reads - {@code /v1/players/search} keeps its own
 * tighter {@code search} bucket) share one generous {@code read} bucket - an abuse backstop behind
 * the edge-cache layer (PR 2), since a cache-busting flood would otherwise hit CH on every request.
 * Everything else (Actuator, static assets) is unthrottled.
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
  private static final String OPS_PREFIX = "/v1/ops/";
  private static final String GAMES_PREFIX = "/v1/games/";
  private static final String MATCHUPS_PREFIX = "/v1/matchups/";
  // /v1/players/** covers the profile + roster + batted-balls reads; /v1/players/search keeps its
  // OWN tighter `search` bucket (its exact-match branch is checked first in doFilterInternal).
  private static final String PLAYERS_PREFIX = "/v1/players/";

  private final boolean enabled;
  private final int predictPerMinute;
  private final int simulatePerMinute;
  private final int searchPerMinute;
  private final int adminPerMinute;
  private final int readPerMinute;
  private final List<IpAddressMatcher> trustedProxies;
  private final ObjectMapper objectMapper;
  private final Cache<String, TokenBucket> buckets =
      Caffeine.newBuilder().maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(10)).build();

  public RateLimitFilter(RateLimitProperties props, ObjectMapper objectMapper) {
    this.enabled = props.enabled();
    this.predictPerMinute = props.predictPerMinute();
    // Simulate is ~12-40x the compute of a single predict (12 per-state ONNX calls + a Markov/MC
    // solve), so it gets its own, tighter bucket rather than sharing the predict class (F1.6).
    this.simulatePerMinute = props.simulatePerMinute();
    this.searchPerMinute = props.searchPerMinute();
    this.adminPerMinute = props.adminPerMinute();
    // The public ClickHouse-backed reads (/v1/ops/**, /v1/games/**, /v1/matchups/**, the
    // /v1/players/** non-search reads) share one generous bucket: the polled ones are
    // edge-cacheable
    // so the real defense is Cache-Control + Cloudflare (PR 2); this is the abuse backstop against
    // a
    // flood that busts the cache or hits the un-cached profile/roster reads.
    this.readPerMinute = props.readPerMinute();
    // trim so an override like "127.0.0.0/8, ::1" (space after comma) cannot throw at startup.
    this.trustedProxies =
        props.trustedProxies().stream()
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
        || path.startsWith(ADMIN_PREFIX)
        || path.startsWith(OPS_PREFIX)
        || path.startsWith(GAMES_PREFIX)
        || path.startsWith(MATCHUPS_PREFIX)
        || path.startsWith(PLAYERS_PREFIX));
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
    } else if (path.startsWith(OPS_PREFIX)
        || path.startsWith(GAMES_PREFIX)
        || path.startsWith(MATCHUPS_PREFIX)
        || path.startsWith(PLAYERS_PREFIX)) {
      // /v1/players/search is handled by the SEARCH branch above (checked first), so only the other
      // player reads (profile / roster / batted-balls) fall through to the shared read bucket here.
      routeClass = "read";
      limit = readPerMinute;
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
