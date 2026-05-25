package net.thebullpen.baseball.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.api.dto.PlayerPredictionRow;
import net.thebullpen.baseball.api.dto.PlayerSearchResult;
import net.thebullpen.baseball.data.PlayerPredictionsRepository;
import net.thebullpen.baseball.data.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP-level test for {@link PlayerController}. Uses {@code standaloneSetup} + a mocked {@link
 * PlayerRepository} so the slice runs without ClickHouse — the repo-against-real-CH coverage lives
 * in {@code PlayerRepositoryIT}.
 *
 * <p>Wires {@link ApiErrorAdvice} into the standalone MockMvc so validation failures map to the
 * canonical {@code {error: {code, message, ...}}} body shape that the rest of the API uses.
 */
class PlayerControllerTest {

  private PlayerRepository repo;
  private PlayerPredictionsRepository predictions;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    repo = mock(PlayerRepository.class);
    predictions = mock(PlayerPredictionsRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new PlayerController(repo, predictions))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void search_returns_repo_results() throws Exception {
    when(repo.search(eq("judge"), anyInt()))
        .thenReturn(
            List.of(
                new PlayerSearchResult(660271L, "Aaron Judge", "RF", true),
                new PlayerSearchResult(660272L, "Other Judge", "C", false)));

    mvc.perform(get("/v1/players/search").param("q", "judge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(660271))
        .andExpect(jsonPath("$[0].name").value("Aaron Judge"))
        .andExpect(jsonPath("$[0].primaryPosition").value("RF"))
        .andExpect(jsonPath("$[0].active").value(true))
        .andExpect(jsonPath("$[1].active").value(false));
  }

  @Test
  void search_defaultLimit_is_10() throws Exception {
    when(repo.search(eq("judge"), eq(10))).thenReturn(List.of());

    mvc.perform(get("/v1/players/search").param("q", "judge")).andExpect(status().isOk());
    verify(repo).search("judge", 10);
  }

  @Test
  void search_respects_limit_param() throws Exception {
    when(repo.search(eq("judge"), eq(3))).thenReturn(List.of());

    mvc.perform(get("/v1/players/search").param("q", "judge").param("limit", "3"))
        .andExpect(status().isOk());
    verify(repo).search("judge", 3);
  }

  @Test
  void search_missingQ_returns_400() throws Exception {
    mvc.perform(get("/v1/players/search")).andExpect(status().isBadRequest());
  }

  @Test
  void search_limit_outOfRange_returns_400() throws Exception {
    mvc.perform(get("/v1/players/search").param("q", "judge").param("limit", "999"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void search_limit_belowMin_returns_400() throws Exception {
    mvc.perform(get("/v1/players/search").param("q", "judge").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void get_byId_present_returns_200() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true)));

    mvc.perform(get("/v1/players/660271"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Aaron Judge"));
  }

  @Test
  void get_byId_absent_returns_404() throws Exception {
    when(repo.findById(9_999_999L)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/players/9999999")).andExpect(status().isNotFound());
  }

  // --- predictionsFor (leaf 4b.2) ----------------------------------------

  @Test
  void predictionsFor_returns_rows_when_player_exists() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true)));
    when(predictions.findRecentForPlayer(660271L, 50))
        .thenReturn(
            List.of(
                new PlayerPredictionRow(
                    Instant.parse("2026-05-20T18:30:00Z"),
                    "pitch_outcome_pre",
                    "v3",
                    "champion",
                    "ball",
                    0.42,
                    null,
                    null)));

    mvc.perform(get("/v1/players/660271/predictions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].modelName").value("pitch_outcome_pre"))
        .andExpect(jsonPath("$[0].winnerClass").value("ball"))
        .andExpect(jsonPath("$[0].winnerProb").value(0.42));
  }

  @Test
  void predictionsFor_empty_list_when_no_traffic() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true)));
    when(predictions.findRecentForPlayer(660271L, 50)).thenReturn(List.of());

    mvc.perform(get("/v1/players/660271/predictions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void predictionsFor_404_when_player_unknown() throws Exception {
    when(repo.findById(9_999_999L)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/players/9999999/predictions")).andExpect(status().isNotFound());
  }

  @Test
  void predictionsFor_400_when_limit_out_of_range() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true)));

    mvc.perform(get("/v1/players/660271/predictions").param("limit", "5000"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/players/660271/predictions").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void predictionsFor_respects_custom_limit() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true)));
    when(predictions.findRecentForPlayer(660271L, 25)).thenReturn(List.of());

    mvc.perform(get("/v1/players/660271/predictions").param("limit", "25"))
        .andExpect(status().isOk());
    verify(predictions).findRecentForPlayer(660271L, 25);
  }
}
