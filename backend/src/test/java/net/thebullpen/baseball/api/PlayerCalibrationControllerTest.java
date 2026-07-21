package net.thebullpen.baseball.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.data.CalibrationRepository;
import net.thebullpen.baseball.data.PlayerRepository;
import net.thebullpen.baseball.domain.CalibrationBin;
import net.thebullpen.baseball.domain.PlayerSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlayerCalibrationControllerTest {

  private PlayerRepository players;
  private CalibrationRepository calibration;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    players = mock(PlayerRepository.class);
    calibration = mock(CalibrationRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PlayerCalibrationController(players, calibration))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void returns_bins_for_known_player_and_model() throws Exception {
    when(players.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
    // actual is null - no truth-join behind this endpoint yet (the honest contract). predicted + n
    // are real; the controller passes the repo's bins through verbatim.
    when(calibration.computePlayerBins("pitch_outcome_pre", 660271L))
        .thenReturn(
            List.of(
                new CalibrationBin(0.0, 0.1, 0.05, null, 100L),
                new CalibrationBin(0.4, 0.5, 0.45, null, 250L)));

    mvc.perform(get("/v1/players/660271/calibration").param("model", "pitch_outcome_pre"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].binStart").value(0.0))
        .andExpect(jsonPath("$[0].n").value(100))
        .andExpect(jsonPath("$[0].actual").value(nullValue()))
        .andExpect(jsonPath("$[1].predicted").value(0.45));
  }

  @Test
  void empty_array_when_repo_returns_no_bins() throws Exception {
    when(players.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
    when(calibration.computePlayerBins("batted_ball", 660271L)).thenReturn(List.of());

    mvc.perform(get("/v1/players/660271/calibration").param("model", "batted_ball"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void rejects_unknown_model_with_400() throws Exception {
    mvc.perform(get("/v1/players/660271/calibration").param("model", "made_up_model"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejects_blank_model_with_400() throws Exception {
    mvc.perform(get("/v1/players/660271/calibration").param("model", " "))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missing_model_param_returns_400() throws Exception {
    mvc.perform(get("/v1/players/660271/calibration")).andExpect(status().isBadRequest());
  }

  @Test
  void unknown_player_returns_404() throws Exception {
    when(players.findById(9_999_999L)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/players/9999999/calibration").param("model", "pitch_outcome_pre"))
        .andExpect(status().isNotFound());
  }

  @Test
  void delegates_to_repo_with_correct_args() throws Exception {
    when(players.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
    when(calibration.computePlayerBins("pitch_outcome_post", 660271L)).thenReturn(List.of());

    mvc.perform(get("/v1/players/660271/calibration").param("model", "pitch_outcome_post"))
        .andExpect(status().isOk());
    verify(calibration).computePlayerBins("pitch_outcome_post", 660271L);
  }
}
