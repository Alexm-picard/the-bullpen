package net.thebullpen.baseball.config;

import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standalone-MockMvc coverage for {@link RateLimitFilter} — wires the filter in front of a stub
 * controller so the test exercises the limiter alone, with no Spring context, ONNX model, or
 * ClickHouse to bring up. All requests share MockMvc's default 127.0.0.1 remote address, so they
 * land in the same per-IP bucket.
 */
class RateLimitFilterTest {

  @RestController
  static class StubController {
    @PostMapping("/v1/predict/ping")
    String predict() {
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

    @PostMapping("/v1/admin/routing/ping")
    String admin() {
      return "ok";
    }
  }

  private static MockMvc mvcWith(RateLimitFilter filter) {
    return MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
  }

  @Test
  void predict429sWithApiErrorEnvelopeOnceBudgetExhausted() throws Exception {
    MockMvc mvc = mvcWith(new RateLimitFilter(true, 3, 120, 20, new ObjectMapper()));
    for (int i = 1; i <= 3; i++) {
      mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    }
    mvc.perform(post("/v1/predict/ping"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.error.code", equalTo("rate_limited")));
  }

  @Test
  void predictAndSearchHaveIndependentBudgets() throws Exception {
    MockMvc mvc = mvcWith(new RateLimitFilter(true, 1, 2, 20, new ObjectMapper()));
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    mvc.perform(post("/v1/predict/ping")).andExpect(status().isTooManyRequests());
    // Search has its own bucket and is unaffected by the drained predict bucket.
    mvc.perform(get("/v1/players/search")).andExpect(status().isOk());
    mvc.perform(get("/v1/players/search")).andExpect(status().isOk());
    mvc.perform(get("/v1/players/search")).andExpect(status().isTooManyRequests());
  }

  @Test
  void unthrottledPathsNeverLimited() throws Exception {
    MockMvc mvc = mvcWith(new RateLimitFilter(true, 1, 1, 20, new ObjectMapper()));
    for (int i = 0; i < 5; i++) {
      mvc.perform(get("/v1/ops/routing")).andExpect(status().isOk());
    }
  }

  @Test
  void adminPathsThrottledOnTheirOwnBucket() throws Exception {
    // Admin gets a tight bucket (2/min here) to blunt Basic-auth brute-forcing, independent of the
    // generous predict/search budgets. (Pre-fix, /v1/admin/** was unthrottled entirely.)
    MockMvc mvc = mvcWith(new RateLimitFilter(true, 60, 120, 2, new ObjectMapper()));
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
    MockMvc mvc = mvcWith(new RateLimitFilter(false, 1, 1, 20, new ObjectMapper()));
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/v1/predict/ping")).andExpect(status().isOk());
    }
  }
}
