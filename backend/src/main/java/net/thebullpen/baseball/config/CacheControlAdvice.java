package net.thebullpen.baseball.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * PR 2 - short-TTL {@code Cache-Control} on the identical-across-users public GET reads, so
 * Cloudflare (and the browser) can edge-cache the polled dashboards + game views instead of hitting
 * ClickHouse on every poll. TTLs are matched to each surface's poll cadence:
 *
 * <ul>
 *   <li>{@code /v1/games/**} &rarr; {@code public, max-age=8} (the live game view polls every ~12s)
 *   <li>{@code /v1/ops/**} (incl. {@code /v1/ops/registry}) &rarr; {@code public, max-age=20} (~30s
 *       poll)
 * </ul>
 *
 * <p>{@code /v1/predict/**} and {@code /v1/admin/**} get {@code no-store}: a prediction must never
 * be served stale - and the {@code /parks} HR heatmap is a POST {@code
 * /v1/predict/batted-ball/all-parks} (there is no separate GET parks/factors endpoint; that factor
 * table is a frontend fixture), so it is correctly covered here as no-store rather than cached.
 * Admin writes/reads must never be cached.
 *
 * <p>This is a {@link ResponseBodyAdvice}, NOT a {@code HandlerInterceptor.postHandle}: for a
 * {@code &#64;ResponseBody} handler the body is written - and the response COMMITTED once it
 * exceeds the container's output buffer (~8 KB) - inside the {@code HandlerAdapter}, BEFORE {@code
 * postHandle} runs, so a header set in {@code postHandle} is silently dropped on exactly the large
 * polled reads this targets. {@link #beforeBodyWrite} runs after the handler returns but before the
 * converter writes, on the not-yet-committed response, so the header always lands. A thrown error
 * is written by the {@code &#64;ExceptionHandler} path with a non-200 status, and a not-ready 204
 * is likewise non-200 - both excluded by the {@code status == 200} guard whether or not the advice
 * runs for them - so an error or a 204 is never made cacheable. The matching Cloudflare cache rules
 * are added box-side once this deploys.
 */
@ControllerAdvice
@Profile("api")
public class CacheControlAdvice implements ResponseBodyAdvice<Object> {

  static final String OPS_PREFIX = "/v1/ops/";
  static final String GAMES_PREFIX = "/v1/games/";
  static final String PREDICT_PREFIX = "/v1/predict/";
  static final String ADMIN_PREFIX = "/v1/admin/";

  static final String NO_STORE = "no-store";
  static final int OPS_MAX_AGE_SECONDS = 20;
  static final int GAMES_MAX_AGE_SECONDS = 8;

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true; // path-prefix branching in beforeBodyWrite decides what (if anything) to set
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    String path = request.getURI().getPath();
    HttpHeaders headers = response.getHeaders();
    if (path.startsWith(PREDICT_PREFIX) || path.startsWith(ADMIN_PREFIX)) {
      headers.setCacheControl(NO_STORE);
      return body;
    }
    // Cache only a successful (200) GET read - never a mutation, a redirect, or an error response.
    if (!HttpMethod.GET.equals(request.getMethod()) || statusOf(response) != 200) {
      return body;
    }
    if (path.startsWith(OPS_PREFIX)) {
      headers.setCacheControl("public, max-age=" + OPS_MAX_AGE_SECONDS);
    } else if (path.startsWith(GAMES_PREFIX)) {
      headers.setCacheControl("public, max-age=" + GAMES_MAX_AGE_SECONDS);
    }
    return body;
  }

  /**
   * The status already set on the servlet response (ResponseEntity applies it before body write).
   */
  private static int statusOf(ServerHttpResponse response) {
    return response instanceof ServletServerHttpResponse servlet
        ? servlet.getServletResponse().getStatus()
        : 200;
  }
}
