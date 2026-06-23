package net.thebullpen.baseball.api;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import net.thebullpen.baseball.api.dto.ArsenalPitch;
import net.thebullpen.baseball.api.dto.BattedBallRow;
import net.thebullpen.baseball.api.dto.PlayerPredictionRow;
import net.thebullpen.baseball.api.dto.PlayerSearchResult;
import net.thebullpen.baseball.data.BatterBattedBallsRepository;
import net.thebullpen.baseball.data.PitcherArsenalRepository;
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
  private PitcherArsenalRepository arsenal;
  private BatterBattedBallsRepository battedBalls;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    repo = mock(PlayerRepository.class);
    predictions = mock(PlayerPredictionsRepository.class);
    arsenal = mock(PitcherArsenalRepository.class);
    battedBalls = mock(BatterBattedBallsRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new PlayerController(repo, predictions, arsenal, battedBalls))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void search_returns_repo_results() throws Exception {
    when(repo.search(eq("judge"), anyInt()))
        .thenReturn(
            List.of(
                new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY"),
                new PlayerSearchResult(660272L, "Other Judge", "C", false, "NYY")));

    mvc.perform(get("/v1/players/search").param("q", "judge"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(660271))
        .andExpect(jsonPath("$[0].name").value("Aaron Judge"))
        .andExpect(jsonPath("$[0].primaryPosition").value("RF"))
        .andExpect(jsonPath("$[0].active").value(true))
        .andExpect(jsonPath("$[0].team").value("NYY"))
        .andExpect(jsonPath("$[1].active").value(false));
  }

  // --- roster (Browse) ---------------------------------------------------

  @Test
  void roster_byTeam_delegates_and_serializes_team() throws Exception {
    when(repo.roster(eq("NYY"), isNull(), anyInt()))
        .thenReturn(List.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));

    mvc.perform(get("/v1/players/roster").param("team", "NYY"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Aaron Judge"))
        .andExpect(jsonPath("$[0].team").value("NYY"));
    verify(repo).roster("NYY", null, 50);
  }

  @Test
  void roster_byPosition_delegates_with_default_limit() throws Exception {
    when(repo.roster(isNull(), eq("SS"), eq(50))).thenReturn(List.of());

    mvc.perform(get("/v1/players/roster").param("position", "SS")).andExpect(status().isOk());
    verify(repo).roster(null, "SS", 50);
  }

  @Test
  void roster_limit_outOfRange_returns_400() throws Exception {
    mvc.perform(get("/v1/players/roster").param("team", "NYY").param("limit", "500"))
        .andExpect(status().isBadRequest());
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
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));

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
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
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
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
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
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));

    mvc.perform(get("/v1/players/660271/predictions").param("limit", "5000"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/v1/players/660271/predictions").param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void predictionsFor_respects_custom_limit() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Aaron Judge", "RF", true, "NYY")));
    when(predictions.findRecentForPlayer(660271L, 25)).thenReturn(List.of());

    mvc.perform(get("/v1/players/660271/predictions").param("limit", "25"))
        .andExpect(status().isOk());
    verify(predictions).findRecentForPlayer(660271L, 25);
  }

  // --- arsenalFor (Phase 2.1) --------------------------------------------

  @Test
  void arsenalFor_returns_rows_when_player_exists() throws Exception {
    when(repo.findById(660271L))
        .thenReturn(Optional.of(new PlayerSearchResult(660271L, "Tarik Skubal", "P", true, "DET")));
    when(arsenal.findArsenal(660271L))
        .thenReturn(List.of(new ArsenalPitch("FF", 1200L, 0.55, 95.1, 97.3, 99.8)));

    mvc.perform(get("/v1/players/660271/arsenal"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].pitchType").value("FF"))
        .andExpect(jsonPath("$[0].count").value(1200))
        .andExpect(jsonPath("$[0].veloMaxMph").value(99.8));
  }

  @Test
  void arsenalFor_404_when_player_unknown() throws Exception {
    when(repo.findById(9_999_999L)).thenReturn(Optional.empty());
    mvc.perform(get("/v1/players/9999999/arsenal")).andExpect(status().isNotFound());
  }

  // --- battedBallsFor (Phase 2.2/2.3) ------------------------------------

  @Test
  void battedBallsFor_delegates_with_filters() throws Exception {
    when(repo.findById(592450L))
        .thenReturn(Optional.of(new PlayerSearchResult(592450L, "Aaron Judge", "RF", true, "NYY")));
    when(battedBalls.findBattedBalls(
            eq(592450L),
            eq("fly_ball"),
            eq("home_run"),
            eq(LocalDate.parse("2025-04-01")),
            eq(LocalDate.parse("2025-09-30")),
            eq(200)))
        .thenReturn(
            List.of(
                new BattedBallRow(
                    "2025-07-04", "home_run", "fly_ball", 110.2, 29.0, 441.0, "NYY", "R")));

    mvc.perform(
            get("/v1/players/592450/batted-balls")
                .param("bbType", "fly_ball")
                .param("event", "home_run")
                .param("from", "2025-04-01")
                .param("to", "2025-09-30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].events").value("home_run"))
        .andExpect(jsonPath("$[0].hitDistanceFt").value(441.0));
    verify(battedBalls)
        .findBattedBalls(
            592450L,
            "fly_ball",
            "home_run",
            LocalDate.parse("2025-04-01"),
            LocalDate.parse("2025-09-30"),
            200);
  }

  @Test
  void battedBallsFor_404_when_player_unknown() throws Exception {
    when(repo.findById(9_999_999L)).thenReturn(Optional.empty());
    mvc.perform(get("/v1/players/9999999/batted-balls")).andExpect(status().isNotFound());
  }

  @Test
  void battedBallsFor_400_when_limit_out_of_range() throws Exception {
    mvc.perform(get("/v1/players/592450/batted-balls").param("limit", "5000"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void battedBallsFor_400_when_date_malformed() throws Exception {
    when(repo.findById(592450L))
        .thenReturn(Optional.of(new PlayerSearchResult(592450L, "Aaron Judge", "RF", true, "NYY")));
    mvc.perform(get("/v1/players/592450/batted-balls").param("from", "not-a-date"))
        .andExpect(status().isBadRequest());
  }
}
