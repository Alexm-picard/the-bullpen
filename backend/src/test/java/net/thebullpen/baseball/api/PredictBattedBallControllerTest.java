package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MockMvc tests for POST /v1/predict/batted-ball (Phase 1.5).
 *
 * <p>Self-disables when the toy ONNX artifact is absent so a fresh clone has green builds. The
 * ApplicationContext otherwise refuses to start (the ToyBattedBallInference bean throws in
 * {@code @PostConstruct}).
 */
@SpringBootTest
@ActiveProfiles("api")
@EnabledIf(
    expression =
        "#{T(java.nio.file.Files).exists(T(java.nio.file.Path).of(systemProperties['user.dir']).getParent().resolve('training/artifacts/_toy/v0/model.onnx'))}")
class PredictBattedBallControllerTest {

  @Autowired private WebApplicationContext webContext;

  private MockMvc mockMvc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void announce() {
    Path candidate =
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("training/artifacts/_toy/v0/model.onnx");
    if (!Files.exists(candidate)) {
      System.err.println(
          "[PredictBattedBallControllerTest] ONNX artifact missing at "
              + candidate
              + " — test class disabled by @EnabledIf");
    }
  }

  private MockMvc mvc() {
    if (mockMvc == null) {
      mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }
    return mockMvc;
  }

  @Test
  void happyPath_returnsProbabilityInRange() throws Exception {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 105.0,
                "launchAngleDeg", 28.0,
                "releaseSpeedMph", 94.0,
                "parkId", "NYY",
                "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.probHr").value(both(greaterThanOrEqualTo(0.0)).and(lessThanOrEqualTo(1.0))))
        .andExpect(jsonPath("$.modelName").value("_toy_batted_ball"))
        .andExpect(jsonPath("$.modelVersion").value("v0"))
        .andExpect(jsonPath("$.latencyMicros").value(greaterThanOrEqualTo(0)));
  }

  @Test
  void rejectsSwitchHitter_withValidationError() throws Exception {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 95.0,
                "launchAngleDeg", 12.0,
                "releaseSpeedMph", 90.0,
                "parkId", "NYY",
                "stand", "S"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("validation_failed"))
        .andExpect(jsonPath("$.error.details[0].field").value("stand"));
  }

  @Test
  void rejectsMissingField_with400() throws Exception {
    // launchSpeedMph omitted
    String body =
        MAPPER.writeValueAsString(
            Map.of("launchAngleDeg", 28.0, "releaseSpeedMph", 94.0, "parkId", "NYY", "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("validation_failed"));
  }

  @Test
  void rejectsTextContentType_with415() throws Exception {
    mvc()
        .perform(post("/v1/predict/batted-ball").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
  }

  @Test
  void garbageGameIdHeader_returns400_not500() throws Exception {
    // Schemathesis sent a non-numeric X-Bullpen-Game-Id (binds to Long) and got a 500.
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 95.0,
                "launchAngleDeg", 12.0,
                "releaseSpeedMph", 90.0,
                "parkId", "NYY",
                "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball")
                .header("X-Bullpen-Game-Id", "not-a-long")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void parkIdAsArray_returns400_not500() throws Exception {
    String body =
        "{\"launchSpeedMph\":95.0,\"launchAngleDeg\":12.0,\"releaseSpeedMph\":90.0,"
            + "\"parkId\":[{}],\"stand\":\"R\"}";
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownParkId_doesNotProduce5xx() throws Exception {
    // A schema-valid but unknown park id passes Bean Validation, then reaches inference - if the
    // feature pipeline NPEs on an unrecognized park this 500s instead of predicting gracefully.
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 95.0,
                "launchAngleDeg", 12.0,
                "releaseSpeedMph", 90.0,
                "parkId", "ZZ_UNKNOWN_PARK",
                "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(org.hamcrest.Matchers.lessThan(500)));
  }

  @Test
  void rejectsLaunchSpeedOutOfRange_with400() throws Exception {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 500.0, // beyond 130 mph ceiling
                "launchAngleDeg", 28.0,
                "releaseSpeedMph", 94.0,
                "parkId", "NYY",
                "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.details[*].field").value(notNullValue()))
        .andExpect(jsonPath("$.error.code").value(equalTo("validation_failed")));
  }
}
