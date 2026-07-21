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
import java.util.Map;
import java.util.Optional;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.GameSummary;
import net.thebullpen.baseball.domain.LivePitchRow;
import net.thebullpen.baseball.domain.PostPredictionRow;
import net.thebullpen.baseball.domain.PostPredictionsPage;
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
                    null,
                    102.5,
                    28.0,
                    412.0,
                    "fly_ball",
                    "home_run",
                    // A5 pre-pitch context (V028): serialized through the games DTO so the frontend
                    // can build the A6 next-pitch request. scoreDiff is the serving-path constant
                    // 0.
                    "R",
                    "L",
                    1,
                    "BOS",
                    0)));

    mvc.perform(get("/v1/games/777001/pitches"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].description").value("called_strike"))
        .andExpect(jsonPath("$[0].cursor").value(101))
        .andExpect(jsonPath("$[0].launchSpeedMph").value(102.5))
        .andExpect(jsonPath("$[0].launchAngleDeg").value(28.0))
        .andExpect(jsonPath("$[0].hitDistanceFt").value(412.0))
        .andExpect(jsonPath("$[0].bbType").value("fly_ball"))
        .andExpect(jsonPath("$[0].event").value("home_run"))
        .andExpect(jsonPath("$[0].pitcherThrows").value("R"))
        .andExpect(jsonPath("$[0].batterStand").value("L"))
        .andExpect(jsonPath("$[0].baseState").value(1))
        .andExpect(jsonPath("$[0].parkId").value("BOS"))
        .andExpect(jsonPath("$[0].scoreDiff").value(0));
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

  @Test
  void postPredictions_defaults_return_the_page() throws Exception {
    when(repo.findPostPredictions(777001L, 0, 50))
        .thenReturn(
            new PostPredictionsPage(
                List.of(
                    new PostPredictionRow(
                        1,
                        1,
                        7,
                        660271L,
                        545361L,
                        "hit_into_play",
                        Map.of("in_play", 0.7, "ball", 0.3),
                        "in_play",
                        "v1")),
                0,
                50,
                false));

    mvc.perform(get("/v1/games/777001/post-predictions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.hasNext").value(false))
        .andExpect(jsonPath("$.rows[0].atBatIndex").value(1))
        .andExpect(jsonPath("$.rows[0].realizedOutcome").value("hit_into_play"))
        .andExpect(jsonPath("$.rows[0].postWinner").value("in_play"))
        .andExpect(jsonPath("$.rows[0].modelVersion").value("v1"));
    verify(repo).findPostPredictions(777001L, 0, 50);
  }

  @Test
  void postPredictions_forwards_page_and_size_parameters() throws Exception {
    when(repo.findPostPredictions(eq(777001L), eq(2), eq(10)))
        .thenReturn(new PostPredictionsPage(List.of(), 2, 10, false));

    mvc.perform(get("/v1/games/777001/post-predictions").param("page", "2").param("size", "10"))
        .andExpect(status().isOk());
    verify(repo).findPostPredictions(777001L, 2, 10);
  }

  @Test
  void postPredictions_negative_page_rejected_with_400() throws Exception {
    mvc.perform(get("/v1/games/777001/post-predictions").param("page", "-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postPredictions_size_over_max_rejected_with_400() throws Exception {
    mvc.perform(get("/v1/games/777001/post-predictions").param("size", "201"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void postPredictions_size_below_min_rejected_with_400() throws Exception {
    mvc.perform(get("/v1/games/777001/post-predictions").param("size", "0"))
        .andExpect(status().isBadRequest());
  }
}
