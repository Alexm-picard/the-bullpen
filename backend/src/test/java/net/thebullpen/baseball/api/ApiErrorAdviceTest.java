package net.thebullpen.baseball.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletionException;
import net.thebullpen.baseball.inference.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.firewall.RequestRejectedException;
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

  @Test
  void routed_model_unavailable_wrapped_in_completion_exception_maps_to_503() throws Exception {
    // The A/B router runs the champion on a CompletableFuture; join() wraps the cause in a
    // CompletionException, and a controller supplier may re-wrap once more in a RuntimeException.
    // The cause-walk must still surface 503 (the stale-routing-row case C2 actually targets).
    mvc.perform(get("/test/wrapped-model-unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("model_unavailable"));
  }

  @Test
  void completion_exception_with_a_non_model_cause_stays_500() throws Exception {
    // A CompletionException whose cause is NOT a model-load failure is a genuine internal error and
    // must not be downgraded to a retryable 503.
    mvc.perform(get("/test/wrapped-bug"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error.code").value("internal_error"));
  }

  @Test
  void a_raw_illegal_argument_maps_to_400_without_leaking_its_message() throws Exception {
    // A raw IllegalArgumentException (internal precondition, e.g. a missing calibrator) is a client
    // 400, but its message can carry internal detail, so the envelope must be generic - the leaked
    // string must NOT appear in the response. The detail is logged under the correlation id
    // instead.
    mvc.perform(get("/test/illegal-argument"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("invalid_input"))
        .andExpect(jsonPath("$.error.message").value("the request contained an invalid value"));
  }

  @Test
  void firewall_rejected_request_maps_to_400_not_500() throws Exception {
    // A StrictHttpFirewall rejection (e.g. a non-ASCII header value) surfaces as a
    // RequestRejectedException during MVC dispatch; it is a client error, so 400 - not the generic
    // 500 that was the recurring Schemathesis rare-500 on POST /v1/predict/batted-ball.
    mvc.perform(get("/test/request-rejected"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("bad_request"));
  }

  @RestController
  static class ThrowingController {
    @GetMapping("/test/model-unavailable")
    String modelUnavailable() {
      throw new ModelUnavailableException("snapshot won't load");
    }

    @GetMapping("/test/request-rejected")
    String requestRejected() {
      throw new RequestRejectedException("header value not allowed");
    }

    @GetMapping("/test/illegal-state")
    String illegalState() {
      throw new IllegalStateException("a genuine bug");
    }

    @GetMapping("/test/illegal-argument")
    String illegalArgument() {
      throw new IllegalArgumentException("no calibrators for park SECRET_INTERNAL_DETAIL");
    }

    @GetMapping("/test/wrapped-model-unavailable")
    String wrappedModelUnavailable() {
      throw new CompletionException(
          new RuntimeException(new ModelUnavailableException("routed champion won't load")));
    }

    @GetMapping("/test/wrapped-bug")
    String wrappedBug() {
      throw new CompletionException(new IllegalStateException("a genuine async bug"));
    }
  }
}
