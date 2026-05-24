package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * MockMvc tests for {@code POST /v1/predict/pitch} (Phase 2a.8).
 *
 * <p>Self-disables when the production ONNX artifact is absent, same pattern as the toy controller
 * test. Without the artifact the {@link net.thebullpen.baseball.inference.PitchInferenceService}
 * bean would throw at {@code @PostConstruct} and refuse to start the context.
 */
@SpringBootTest
@ActiveProfiles("api")
@EnabledIf(
    expression =
        "#{T(java.nio.file.Files).exists(T(java.nio.file.Path).of(systemProperties['user.dir']).getParent().resolve('training/artifacts/pitch_outcome_pre/v1/model.onnx'))}")
class PredictPitchControllerTest {

  @Autowired private WebApplicationContext webContext;

  private MockMvc mockMvc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void announce() {
    Path candidate =
        Path.of(System.getProperty("user.dir"))
            .getParent()
            .resolve("training/artifacts/pitch_outcome_pre/v1/model.onnx");
    if (!Files.exists(candidate)) {
      System.err.println(
          "[PredictPitchControllerTest] ONNX artifact missing at "
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

  private static Map<String, Object> validRequest() {
    Map<String, Object> body = new HashMap<>();
    body.put("countBalls", 1);
    body.put("countStrikes", 1);
    body.put("outs", 1);
    body.put("inning", 4);
    body.put("baseState", 0);
    body.put("scoreDiff", 0);
    body.put("dow", 3);
    body.put("pitcherThrows", "R");
    body.put("batterStand", "L");
    body.put("parkId", "NYY");
    body.put("pitcherId", 545361L);
    body.put("batterId", 605141L);
    // Tier 3 optional — supply realistic-ish defaults
    body.put("pitcherPitchesLast28d", 280.0);
    body.put("pitcherPitchesInGame", 42.0);
    body.put("daysSinceLastAppearance", 4.0);
    body.put("pitcherStrikeRate28d", 0.65);
    body.put("pitcherSwstrikeRate28d", 0.11);
    body.put("pitcherInplayRate28d", 0.18);
    body.put("pitcherStrikeRateStd", 0.05);
    body.put("batterStrikeRate28d", 0.62);
    body.put("batterInplayRate28d", 0.20);
    body.put("batterBallRate28d", 0.36);
    body.put("batterInplayRateStd", 0.04);
    return body;
  }

  @Test
  void happyPath_returnsCalibratedDistribution() throws Exception {
    String body = MAPPER.writeValueAsString(validRequest());
    // JSONPath returns nested numerics as Double; per-bound Hamcrest matchers trip on
    // BigDecimal coercion. Stick with .isNumber() + Map-level structural shape, then
    // parse the body once and assert the distribution sums to ~1.0 + each class in [0,1].
    String responseJson =
        mvc()
            .perform(
                post("/v1/predict/pitch").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.modelName").value("pitch_outcome_pre"))
            .andExpect(jsonPath("$.modelVersion").value("v1"))
            .andExpect(jsonPath("$.probabilities.ball").isNumber())
            .andExpect(jsonPath("$.probabilities.called_strike").isNumber())
            .andExpect(jsonPath("$.probabilities.swinging_strike").isNumber())
            .andExpect(jsonPath("$.probabilities.foul").isNumber())
            .andExpect(jsonPath("$.probabilities.in_play").isNumber())
            .andExpect(jsonPath("$.winner").isString())
            .andExpect(jsonPath("$.latencyMicros").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(responseJson);
    com.fasterxml.jackson.databind.JsonNode probs = root.get("probabilities");
    double sum = 0.0;
    for (String cls :
        new String[] {"ball", "called_strike", "swinging_strike", "foul", "in_play"}) {
      double p = probs.get(cls).asDouble();
      org.junit.jupiter.api.Assertions.assertTrue(
          p >= 0.0 && p <= 1.0, cls + " out of [0,1]: " + p);
      sum += p;
    }
    org.junit.jupiter.api.Assertions.assertEquals(1.0, sum, 1e-5, "calibrated probs must sum to 1");
  }

  @Test
  void rejectsSwitchHitterStand_with400() throws Exception {
    Map<String, Object> body = validRequest();
    body.put("batterStand", "S");
    mvc()
        .perform(
            post("/v1/predict/pitch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("validation_failed"))
        .andExpect(jsonPath("$.error.details[*].field").value(notNullValue()));
  }

  @Test
  void rejectsCountBallsOutOfRange_with400() throws Exception {
    Map<String, Object> body = validRequest();
    body.put("countBalls", 9); // > 3
    mvc()
        .perform(
            post("/v1/predict/pitch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(equalTo("validation_failed")));
  }

  @Test
  void rejectsMissingPitcherId_with400() throws Exception {
    Map<String, Object> body = validRequest();
    body.remove("pitcherId");
    mvc()
        .perform(
            post("/v1/predict/pitch")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("validation_failed"));
  }

  @Test
  void rejectsTextContentType_with415() throws Exception {
    mvc()
        .perform(post("/v1/predict/pitch").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
  }
}
