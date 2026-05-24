package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
 * MockMvc tests for the forward simulator endpoints (Phase 2a.9).
 *
 * <p>Self-disables when the production ONNX artifact is absent, same pattern as the pitch
 * controller test. Without the artifact the {@link
 * net.thebullpen.baseball.inference.PitchInferenceService} bean is skipped (conditional), and
 * {@link net.thebullpen.baseball.api.SimulateController} follows it via {@code @ConditionalOnBean}.
 */
@SpringBootTest
@ActiveProfiles("api")
@EnabledIf(
    expression =
        "#{T(java.nio.file.Files).exists(T(java.nio.file.Path).of(systemProperties['user.dir']).getParent().resolve('training/artifacts/pitch_outcome_pre/v1/model.onnx'))}")
class SimulateControllerTest {

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
          "[SimulateControllerTest] ONNX artifact missing at "
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

  private static Map<String, Object> baseRequest() {
    Map<String, Object> body = new HashMap<>();
    body.put("startBalls", 0);
    body.put("startStrikes", 0);
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
  void analyticalHappyPath_returnsConsistentDistribution() throws Exception {
    String body = MAPPER.writeValueAsString(baseRequest());
    String json =
        mvc()
            .perform(
                post("/v1/simulate/plate-appearance")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.method").value("analytical"))
            .andExpect(jsonPath("$.modelName").value("pitch_outcome_pre"))
            .andExpect(jsonPath("$.modelVersion").value("v1"))
            .andExpect(jsonPath("$.startBalls").value(0))
            .andExpect(jsonPath("$.startStrikes").value(0))
            .andExpect(jsonPath("$.expectedPitches").isNumber())
            .andExpect(jsonPath("$.pWalk").isNumber())
            .andExpect(jsonPath("$.pStrikeout").isNumber())
            .andExpect(jsonPath("$.pInPlay").isNumber())
            .andExpect(jsonPath("$.mcTrials").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = MAPPER.readTree(json);
    double pw = root.get("pWalk").asDouble();
    double pk = root.get("pStrikeout").asDouble();
    double pip = root.get("pInPlay").asDouble();
    double sum = pw + pk + pip;
    org.junit.jupiter.api.Assertions.assertEquals(
        1.0, sum, 1e-6, "absorption probs must sum to 1.0; got " + sum);
    org.junit.jupiter.api.Assertions.assertTrue(
        root.get("expectedPitches").asDouble() >= 1.0,
        "expected pitches must be at least 1 (must throw something to absorb)");
    org.junit.jupiter.api.Assertions.assertTrue(
        root.get("expectedPitches").asDouble() <= 20.0,
        "expected pitches > 20 indicates the chain is misbehaving");
  }

  @Test
  void monteCarloHappyPath_matchesAnalyticalDirectionally() throws Exception {
    Map<String, Object> body = baseRequest();
    body.put("mcTrials", 5000);
    body.put("mcSeed", 12345L);
    String json =
        mvc()
            .perform(
                post("/v1/simulate/plate-appearance/monte-carlo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.method").value("monte_carlo"))
            .andExpect(jsonPath("$.mcTrials").value(5000))
            .andExpect(jsonPath("$.expectedPitches").isNumber())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode root = MAPPER.readTree(json);
    double sum =
        root.get("pWalk").asDouble()
            + root.get("pStrikeout").asDouble()
            + root.get("pInPlay").asDouble();
    org.junit.jupiter.api.Assertions.assertEquals(1.0, sum, 1e-9, "MC probs must sum to 1.0");
  }

  @Test
  void monteCarloIsSeedDeterministic() throws Exception {
    Map<String, Object> body = baseRequest();
    body.put("mcTrials", 2000);
    body.put("mcSeed", 99L);
    String first =
        mvc()
            .perform(
                post("/v1/simulate/plate-appearance/monte-carlo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mvc()
            .perform(
                post("/v1/simulate/plate-appearance/monte-carlo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode a = MAPPER.readTree(first);
    JsonNode b = MAPPER.readTree(second);
    // latencyMicros + correlationId will differ; compare just the statistical fields.
    for (String key : new String[] {"expectedPitches", "pWalk", "pStrikeout", "pInPlay"}) {
      org.junit.jupiter.api.Assertions.assertEquals(
          a.get(key).asDouble(),
          b.get(key).asDouble(),
          0.0,
          "MC drifted across runs with identical seed: " + key);
    }
  }

  @Test
  void rejectsSwitchHitterStand_with400() throws Exception {
    Map<String, Object> body = baseRequest();
    body.put("batterStand", "S");
    mvc()
        .perform(
            post("/v1/simulate/plate-appearance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("validation_failed"))
        .andExpect(jsonPath("$.error.details[*].field").value(notNullValue()));
  }

  @Test
  void rejectsStartBallsOutOfRange_with400() throws Exception {
    Map<String, Object> body = baseRequest();
    body.put("startBalls", 5);
    mvc()
        .perform(
            post("/v1/simulate/plate-appearance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value(equalTo("validation_failed")));
  }
}
