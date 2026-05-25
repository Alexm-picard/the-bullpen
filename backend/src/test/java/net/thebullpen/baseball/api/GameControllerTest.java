package net.thebullpen.baseball.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.thebullpen.baseball.api.dto.GameSummary;
import net.thebullpen.baseball.api.dto.LivePitchRow;
import net.thebullpen.baseball.data.LivePitchesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GameControllerTest {

  private LivePitchesRepository repo;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    repo = mock(LivePitchesRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new GameController(repo))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void today_returns_list_for_current_et_date() throws Exception {
    when(repo.findGamesForDate(any(LocalDate.class)))
        .thenReturn(
            List.of(
                new GameSummary(
                    777001L,
                    LocalDate.now(),
                    "NYY",
                    "BOS",
                    3,
                    2,
                    7,
                    "IN_PROGRESS",
                    "In Progress")));

    mvc.perform(get("/v1/games/today"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gameId").value(777001))
        .andExpect(jsonPath("$[0].homeTeam").value("NYY"));
  }

  @Test
  void today_returns_empty_list_when_repo_empty() throws Exception {
    when(repo.findGamesForDate(any(LocalDate.class))).thenReturn(List.of());

    mvc.perform(get("/v1/games/today"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void get_byId_returns_summary_when_present() throws Exception {
    when(repo.findGame(777001L))
        .thenReturn(
            Optional.of(
                new GameSummary(
                    777001L,
                    LocalDate.parse("2026-05-25"),
                    "NYY",
                    "BOS",
                    3,
                    2,
                    7,
                    "IN_PROGRESS",
                    "In Progress")));

    mvc.perform(get("/v1/games/777001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.homeTeam").value("NYY"))
        .andExpect(jsonPath("$.inning").value(7));
  }

  @Test
  void get_byId_returns_404_when_absent() throws Exception {
    when(repo.findGame(9_999_999L)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/games/9999999")).andExpect(status().isNotFound());
  }

  @Test
  void pitchesSince_default_zero_cursor_returns_all() throws Exception {
    when(repo.findPitchesSince(777001L, 0L))
        .thenReturn(
            List.of(
                new LivePitchRow(
                    777001L,
                    1,
                    1,
                    101L,
                    Instant.parse("2026-05-25T18:30:00Z"),
                    660271L,
                    545361L,
                    "called_strike",
                    "FF",
                    94.3,
                    0.1,
                    2.6,
                    0,
                    1,
                    0,
                    1,
                    0,
                    0,
                    null,
                    null)));

    mvc.perform(get("/v1/games/777001/pitches"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].description").value("called_strike"))
        .andExpect(jsonPath("$[0].cursor").value(101));
    verify(repo).findPitchesSince(777001L, 0L);
  }

  @Test
  void pitchesSince_forwards_cursor_parameter() throws Exception {
    when(repo.findPitchesSince(eq(777001L), eq(305L))).thenReturn(List.of());

    mvc.perform(get("/v1/games/777001/pitches").param("since", "305")).andExpect(status().isOk());
    verify(repo).findPitchesSince(777001L, 305L);
  }

  @Test
  void pitchesSince_negative_cursor_rejected_with_400() throws Exception {
    mvc.perform(get("/v1/games/777001/pitches").param("since", "-1"))
        .andExpect(status().isBadRequest());
  }
}
