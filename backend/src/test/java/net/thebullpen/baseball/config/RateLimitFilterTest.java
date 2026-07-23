package net.thebullpen.baseball.config;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standalone-MockMvc coverage for {@link RateLimitFilter} — wires the filter in front of a stub
 * controller so the test exercises the limiter alone, with no Spring context, ONNX model, or
 * ClickHouse to bring up. All requests share MockMvc's default 127.0.0.1 remote address (a trusted
 * proxy hop under the default config), so they land in the same per-IP bucket unless a test says
 * otherwise.
 */
class RateLimitFilterTest {

  private static final List<String> LOOPBACK_PROXIES = List.of("127.0.0.0/8", "::1");

  @RestController
  static class StubController {
    @PostMapping("/v1/predict/ping")
    String predict() {
      return "ok";
    }

    @PostMapping("/v1/simulate/ping")
    String simulate() {
      return "ok";
    }

    @GetMapping("/v1/players/search")
    String search() {
      return "ok";
    }

    @GetMapping("/v1/ops/routing")
    String ops() {
      return "ok";
    }

    @GetMapping("/v1/games/today")
    String games() {
      return "ok";
    }

    @GetMapping("/v1/matchups/today")
    String matchups() {
      return "ok";
    }

    @GetMapping("/v1/players/42")
    String playerProfile() {
      return "ok";
    }

    @PostMapping("/v1/admin/routing/ping")
    String admin() {
      return "ok";
    }

    // A genuinely unthrottled path (matches none of the rate-limited prefixes).
    @GetMapping("/health")
    String health() {
      return "ok";
    }
  }

  private static MockMvc mvcWith(RateLimitFilter filter) {
    return MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
  }

  private static RateLimitFilter filter(
      boolean enabled, int predictPerMinute, int searchPerMinute, int adminPerMinute) {
    return filter(enabled, predictPerMinute, 15, searchPerMinute, adminPerMinute, 120);
  }

  private static RateLimitFilter filter(
      boolean enabled,
      int predictPerMinute,
      int simulatePerMinute,
      int searchPerMinute,
      int adminPerMinute) {
    return filter(
        enabled, predictPerMinute, simulatePerMinute, searchPerMinute, adminPerMinute, 120);
  }

  private static RateLimitFilter filter(
      boolean enabled,
      int predictPerMinute,
      int simulatePerMinute,
      int searchPerMinute,
      int adminPerMinute,
      int readPerMinute) {
    return new RateLimitFilter(
        new RateLimitProperties(
            enabled,
            predictPerMinute,
            simulatePerMinute,
            searchPerMinute,
            adminPerMinute,
            readPerMinute,
            LOOPBACK_PROXIES),
        new ObjectMapper());
  }

  private static RequestPostProcessor remoteAddr(String addr) {
    return request -> {
      request.setRemoteAddr(addr);
      return request;
    };
  }

  @Test
  void predict429sWithApiErrorEnvelopeOnceBudgetExhausted() throws Exception {
    MockMvc mvc = mvcWith(filter(true, 3, 120, 20));
    for (int i = 1; i <= 3; i++) {
      mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    }
    mvc.perform(post("/v1/predict/ping"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
  }

  @Test
  void predictAndSearchHaveIndependentBudgets() throws Exception {
    MockMvc mvc = mvcWith(filter(true, 1, 2, 20));
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isTooManyRequests());
    // Search has its own bucket and is unaffected by the drained predict bucket.
    mvc.perform(get("/v1/players/search")).andExpect(status().isOk());
    mvc.perform(get("/v1/players/search")).andExpect(status().isOk());
    mvc.perform(get("/v1/players/search")).andExpect(status().isTooManyRequests());
  }

  @Test
  void unthrottledPathsNeverLimited() throws Exception {
    // A path matching none of the rate-limited prefixes (Actuator, static assets, etc.) is skipped
    // by shouldNotFilter and never throttled, even with every bucket at 1/min.
    MockMvc mvc = mvcWith(filter(true, 1, 1, 20));
    for (int i = 0; i < 5; i++) {
      mvc.perform(get("/health")).andExpect(status().isOk());
    }
  }

  @Test
  void publicReadsThrottledOnTheirOwnSharedBucket() throws Exception {
    // /v1/ops/** and /v1/games/** are the anonymous ClickHouse-backed reads; they share one `read`
    // bucket (1/min here). Draining it via an ops read also limits a games read (same bucket), and
    // leaves the generous predict bucket untouched. (Pre-fix, both prefixes were unthrottled.)
    MockMvc mvc = mvcWith(filter(true, 60, 15, 120, 20, 1)); // read=1
    mvc.perform(get("/v1/ops/routing")).andExpect(status().isOk());
    mvc.perform(get("/v1/games/today"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
    // The drained read bucket leaves the predict bucket untouched.
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
  }

  @Test
  void matchupsAndPlayerProfileShareTheReadBucketButSearchStaysSeparate() throws Exception {
    // /v1/matchups/** and the /v1/players/** NON-search reads (profile / roster / batted-balls)
    // join
    // the shared read bucket (2/min here). /v1/players/search keeps its OWN, separate `search`
    // bucket
    // - draining the read bucket must not touch it. (Pre-fix, matchups + the player profile were
    // unthrottled entirely.)
    MockMvc mvc = mvcWith(filter(true, 60, 15, 1, 20, 2)); // read=2, search=1
    mvc.perform(get("/v1/matchups/today")).andExpect(status().isOk()); // read token 1
    mvc.perform(get("/v1/players/42")).andExpect(status().isOk()); // read token 2 (SAME bucket)
    mvc.perform(get("/v1/players/42")) // read bucket now drained
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
    // /v1/players/search is on the separate `search` bucket (checked by the exact-match branch
    // before
    // the read branch), so the drained read bucket leaves its own budget intact.
    mvc.perform(get("/v1/players/search")).andExpect(status().isOk());
    mvc.perform(get("/v1/players/search")).andExpect(status().isTooManyRequests());
  }

  @Test
  void simulatePathsThrottledOnTheirOwnTighterBucket() throws Exception {
    // Simulate is ~12-40x the compute of a predict, so it gets its own, tighter bucket (1/min here)
    // rather than sharing the predict class - draining it must not touch predict, and vice versa.
    MockMvc mvc = mvcWith(filter(true, 60, 1, 120, 20)); // predict=60, simulate=1
    mvc.perform(post("/v1/simulate/ping")).andExpect(status().isOk());
    mvc.perform(post("/v1/simulate/ping"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
    // The drained simulate bucket leaves the generous predict bucket untouched.
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
  }

  @Test
  void adminPathsThrottledOnTheirOwnBucket() throws Exception {
    // Admin gets a tight bucket (2/min here) to blunt Basic-auth brute-forcing, independent of the
    // generous predict/search budgets. (Pre-fix, /v1/admin/** was unthrottled entirely.)
    MockMvc mvc = mvcWith(filter(true, 60, 120, 2));
    mvc.perform(post("/v1/admin/routing/ping")).andExpect(status().isOk());
    mvc.perform(post("/v1/admin/routing/ping")).andExpect(status().isOk());
    mvc.perform(post("/v1/admin/routing/ping"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
    // The drained admin bucket leaves the predict bucket untouched.
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
  }

  @Test
  void disabledFilterPassesEverythingThrough() throws Exception {
    MockMvc mvc = mvcWith(filter(false, 1, 1, 20));
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    }
  }

  @Test
  void forwardedHeadersHonoredOnlyFromTrustedProxy() throws Exception {
    // From the trusted loopback hop (the on-box cloudflared), CF-Connecting-IP is the bucket key:
    // two different client IPs get two fresh buckets even with a 1/min limit.
    MockMvc mvc = mvcWith(filter(true, 1, 120, 20));
    mvc.perform(post("/v1/predict/ping").header("CF-Connecting-IP", "198.51.100.1"))
        .andExpect(status().isOk());
    mvc.perform(post("/v1/predict/ping").header("CF-Connecting-IP", "198.51.100.2"))
        .andExpect(status().isOk());
    // Same client IP again: its bucket is drained.
    mvc.perform(post("/v1/predict/ping").header("CF-Connecting-IP", "198.51.100.1"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void ipv6LoopbackIsATrustedProxyHop() throws Exception {
    // cloudflared may connect over ::1; the default trusted list covers it, so the forwarded
    // header is still the bucket key.
    MockMvc mvc = mvcWith(filter(true, 1, 120, 20));
    mvc.perform(
            post("/v1/predict/ping")
                .with(remoteAddr("::1"))
                .header("CF-Connecting-IP", "198.51.100.7"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/v1/predict/ping")
                .with(remoteAddr("::1"))
                .header("CF-Connecting-IP", "198.51.100.7"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void spoofedForwardedHeadersIgnoredFromUntrustedRemote() throws Exception {
    // Off-tunnel caller rotating CF-Connecting-IP per request: the forged header must NOT mint a
    // fresh bucket each time — the bucket keys on the peer address, so the 1/min budget exhausts.
    MockMvc mvc = mvcWith(filter(true, 1, 120, 20));
    mvc.perform(
            post("/v1/predict/ping")
                .with(remoteAddr("203.0.113.9"))
                .header("CF-Connecting-IP", "198.51.100.1"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/v1/predict/ping")
                .with(remoteAddr("203.0.113.9"))
                .header("CF-Connecting-IP", "198.51.100.2"))
        .andExpect(status().isTooManyRequests());
    // X-Forwarded-For gets the same treatment.
    mvc.perform(
            post("/v1/predict/ping")
                .with(remoteAddr("203.0.113.9"))
                .header("X-Forwarded-For", "198.51.100.3"))
        .andExpect(status().isTooManyRequests());
  }
}
