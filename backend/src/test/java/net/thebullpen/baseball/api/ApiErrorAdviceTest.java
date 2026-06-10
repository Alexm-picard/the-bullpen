package net.thebullpen.baseball.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.thebullpen.baseball.inference.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Status-code discipline for {@link ApiErrorAdvice} (C2). A {@link ModelUnavailableException} (the
 * model exists but cannot be served right now) maps to a structured 503; a plain {@link
 * IllegalStateException} (a contract / programming bug) stays a 500. Standalone MockMvc over
 * throwaway controllers - no Spring context, no model load - so the mapping is asserted in
 * isolation.
 */
class ApiErrorAdviceTest {

  private final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new ThrowingController())
          .setControllerAdvice(new ApiErrorAdvice())
          .build();

  @Test
  void model_unavailable_maps_to_503_not_500() throws Exception {
    mvc.perform(get("/test/model-unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("model_unavailable"));
  }

  @Test
  void a_plain_illegal_state_stays_500() throws Exception {
    // The 503 mapping is scoped to the ModelUnavailableException SUBTYPE only; a generic
    // IllegalStateException (a real bug) must NOT be downgraded to a retryable 503.
    mvc.perform(get("/test/illegal-state"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("internal_error"));
  }

  @RestController
  static class ThrowingController {
    @GetMapping("/test/model-unavailable")
    String modelUnavailable() {
      throw new ModelUnavailableException("snapshot won't load");
    }

    @GetMapping("/test/illegal-state")
    String illegalState() {
      throw new IllegalStateException("a genuine bug");
    }
  }
}
