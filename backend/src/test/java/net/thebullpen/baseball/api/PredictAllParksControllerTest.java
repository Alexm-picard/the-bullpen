package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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
 * Boots the full {@code api} context with the toy ONNX artifact present and POSTs to {@code
 * /v1/predict/batted-ball/all-parks}. Asserts the response contains 30 distinct park keys +
 * reasonable probabilities. Same {@link EnabledIf}-on-toy-artifact gate as {@code
 * PredictBattedBallControllerTest} so a fresh clone without trained models stays green.
 */
@SpringBootTest
@ActiveProfiles("api")
@EnabledIf(
    expression =
        "#{T(java.nio.file.Files).exists(T(java.nio.file.Path).of(systemProperties['user.dir']).getParent().resolve('training/artifacts/_toy/v0/model.onnx'))}")
class PredictAllParksControllerTest {

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
          "[PredictAllParksControllerTest] ONNX artifact missing at "
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
  void returns_30_parks_with_probabilities() throws Exception {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 110.0,
                "launchAngleDeg", 28.0,
                "releaseSpeedMph", 94.0,
                "parkId", "NYY", // ignored by this endpoint
                "stand", "R"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelName").value("_toy_batted_ball"))
        .andExpect(jsonPath("$.modelVersion").value("v0"))
        .andExpect(jsonPath("$.latencyMicros").value(greaterThanOrEqualTo(0)))
        .andExpect(jsonPath("$.probHrByPark.NYY").value(greaterThanOrEqualTo(0.0)))
        .andExpect(jsonPath("$.probHrByPark.COL").value(greaterThanOrEqualTo(0.0)))
        .andExpect(jsonPath("$.probHrByPark.length()").value(equalTo(30)));
  }

  @Test
  void rejects_switch_hitter_with_400() throws Exception {
    String body =
        MAPPER.writeValueAsString(
            Map.of(
                "launchSpeedMph", 110.0,
                "launchAngleDeg", 28.0,
                "releaseSpeedMph", 94.0,
                "parkId", "NYY",
                "stand", "S"));
    mvc()
        .perform(
            post("/v1/predict/batted-ball/all-parks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }
}
