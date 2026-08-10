package net.thebullpen.baseball.inference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The task-#87 F1 regression pin: {@code PredictionOrchestrator.wrap}'s pass-through set IS the 503
 * contract on the synchronous legacy-fallback leg. ApiErrorAdvice's generic {@code Exception}
 * handler matches a bare {@code RuntimeException} directly and never consults its cause (only the
 * CompletionException handler chain-walks), so a {@link ModelUnavailableException} that reaches the
 * advice WRAPPED becomes a generic 500 instead of the 503 its dedicated handler exists to produce -
 * registry-guard proved the 500 empirically against the real advice. Wrapping it back is a
 * one-character-diff regression this test makes loud.
 */
class PredictionOrchestratorWrapTest {

  @Test
  void model_unavailable_passes_through_unwrapped() {
    ModelUnavailableException mue = new ModelUnavailableException("champion evicted mid-request");
    assertThat(PredictionOrchestrator.wrap(mue))
        .as("wrapped MUE = generic 500; bare MUE = the dedicated 503 handler")
        .isSameAs(mue);
  }

  @Test
  void response_status_exception_passes_through_unwrapped() {
    ResponseStatusException rse =
        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "no champion");
    assertThat(PredictionOrchestrator.wrap(rse)).isSameAs(rse);
  }

  @Test
  void anything_else_gets_the_runtime_wrapper_with_the_cause_preserved() {
    IOException io = new IOException("disk");
    RuntimeException wrapped = PredictionOrchestrator.wrap(io);
    assertThat(wrapped).isNotInstanceOf(ModelUnavailableException.class);
    assertThat(wrapped.getCause()).isSameAs(io);
  }
}
