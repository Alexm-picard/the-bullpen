package net.thebullpen.baseball.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import net.thebullpen.baseball.data.GameMatchupsRepository;
import net.thebullpen.baseball.data.LivePitchesRepository;
import net.thebullpen.baseball.domain.GameMatchup;
import net.thebullpen.baseball.ingest.GameStatus;
import net.thebullpen.baseball.ingest.ScheduledGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MatchupControllerTest {

  private GameMatchupsRepository matchups;
  private LivePitchesRepository slate;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    matchups = mock(GameMatchupsRepository.class);
    slate = mock(LivePitchesRepository.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new MatchupController(matchups, slate))
            .setControllerAdvice(new ApiErrorAdvice())
            .build();
  }

  @Test
  void today_joins_matchup_with_team_context() throws Exception {
    LocalDate d = LocalDate.now();
    when(matchups.findForDate(any(LocalDate.class)))
        .thenReturn(
            List.of(
                new GameMatchup(
                    101L,
                    d,
                    "pitching",
                    1L,
                    "Ace A",
                    "pitcher",
                    2L,
                    "Ace B",
                    "pitcher",
                    7.4,
                    "default")));
    when(slate.findScheduledGames(any(LocalDate.class)))
        .thenReturn(
            List.of(
                new ScheduledGame(
                    101L,
                    GameStatus.SCHEDULED,
                    "BOS",
                    "NYY",
                    "Boston",
                    "New York",
                    Instant.parse("2026-06-05T20:10:00Z"),
                    1L,
                    "Ace A",
                    2L,
                    "Ace B")));

    mvc.perform(get("/v1/matchups/today"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gameId").value(101))
        .andExpect(jsonPath("$[0].homeTeam").value("BOS")) // abbreviation from the schedule
        .andExpect(jsonPath("$[0].awayTeam").value("NYY"))
        .andExpect(jsonPath("$[0].lean").value("pitching"))
        .andExpect(jsonPath("$[0].homePlayerName").value("Ace A"))
        .andExpect(jsonPath("$[0].homeRole").value("pitcher"))
        .andExpect(jsonPath("$[0].battleScore").value(7.4))
        .andExpect(jsonPath("$[0].stage").value("default"));
  }

  @Test
  void today_empty_when_no_matchups() throws Exception {
    when(matchups.findForDate(any(LocalDate.class))).thenReturn(List.of());
    when(slate.findScheduledGames(any(LocalDate.class))).thenReturn(List.of());

    mvc.perform(get("/v1/matchups/today"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void today_tolerates_a_matchup_without_a_schedule_row() throws Exception {
    LocalDate d = LocalDate.now();
    when(matchups.findForDate(any(LocalDate.class)))
        .thenReturn(
            List.of(
                new GameMatchup(
                    999L, d, "pitching", 1L, "A", "pitcher", 2L, "B", "pitcher", 5.0, "default")));
    when(slate.findScheduledGames(any(LocalDate.class))).thenReturn(List.of()); // no schedule row

    mvc.perform(get("/v1/matchups/today"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].gameId").value(999))
        .andExpect(jsonPath("$[0].homeTeam").value("")); // empty team, no crash
  }
}
