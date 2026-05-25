package net.thebullpen.baseball.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GameStateMachineTest {

  private final GameStateMachine sm = new GameStateMachine();

  // --- fromMlbDetailedState -----------------------------------------------

  @Test
  void fromMlbDetailedState_recognises_all_documented_labels() {
    assertThat(GameStatus.fromMlbDetailedState("Scheduled")).isEqualTo(GameStatus.SCHEDULED);
    assertThat(GameStatus.fromMlbDetailedState("Pre-Game")).isEqualTo(GameStatus.SCHEDULED);
    assertThat(GameStatus.fromMlbDetailedState("Warmup")).isEqualTo(GameStatus.WARMUP);
    assertThat(GameStatus.fromMlbDetailedState("In Progress")).isEqualTo(GameStatus.IN_PROGRESS);
    assertThat(GameStatus.fromMlbDetailedState("Mid Inning")).isEqualTo(GameStatus.MID_INNING);
    assertThat(GameStatus.fromMlbDetailedState("End Inning")).isEqualTo(GameStatus.MID_INNING);
    assertThat(GameStatus.fromMlbDetailedState("Delayed: Rain")).isEqualTo(GameStatus.DELAYED);
    assertThat(GameStatus.fromMlbDetailedState("Suspended: Rain")).isEqualTo(GameStatus.SUSPENDED);
    assertThat(GameStatus.fromMlbDetailedState("Postponed")).isEqualTo(GameStatus.POSTPONED);
    assertThat(GameStatus.fromMlbDetailedState("Final")).isEqualTo(GameStatus.COMPLETED);
    assertThat(GameStatus.fromMlbDetailedState("Game Over")).isEqualTo(GameStatus.COMPLETED);
    assertThat(GameStatus.fromMlbDetailedState("Completed Early")).isEqualTo(GameStatus.COMPLETED);
  }

  @Test
  void fromMlbDetailedState_collapses_unknown_to_UNKNOWN_not_throw() {
    assertThat(GameStatus.fromMlbDetailedState(null)).isEqualTo(GameStatus.UNKNOWN);
    assertThat(GameStatus.fromMlbDetailedState("")).isEqualTo(GameStatus.UNKNOWN);
    assertThat(GameStatus.fromMlbDetailedState("Some New State MLB Invented"))
        .isEqualTo(GameStatus.UNKNOWN);
  }

  // --- transition validation ----------------------------------------------

  @Test
  void scheduled_can_become_warmup_in_progress_postponed_delayed() {
    assertThat(sm.isAllowed(GameStatus.SCHEDULED, GameStatus.WARMUP)).isTrue();
    assertThat(sm.isAllowed(GameStatus.SCHEDULED, GameStatus.IN_PROGRESS)).isTrue();
    assertThat(sm.isAllowed(GameStatus.SCHEDULED, GameStatus.POSTPONED)).isTrue();
    assertThat(sm.isAllowed(GameStatus.SCHEDULED, GameStatus.DELAYED)).isTrue();
  }

  @Test
  void scheduled_cannot_become_completed_directly() {
    assertThat(sm.isAllowed(GameStatus.SCHEDULED, GameStatus.COMPLETED)).isFalse();
  }

  @Test
  void completed_is_terminal_except_self_loop() {
    for (GameStatus s : GameStatus.values()) {
      if (s == GameStatus.COMPLETED) {
        assertThat(sm.isAllowed(GameStatus.COMPLETED, s)).isTrue();
      } else {
        assertThat(sm.isAllowed(GameStatus.COMPLETED, s)).isFalse();
      }
    }
  }

  @Test
  void postponed_is_terminal_except_self_loop() {
    for (GameStatus s : GameStatus.values()) {
      if (s == GameStatus.POSTPONED) {
        assertThat(sm.isAllowed(GameStatus.POSTPONED, s)).isTrue();
      } else {
        assertThat(sm.isAllowed(GameStatus.POSTPONED, s)).isFalse();
      }
    }
  }

  @Test
  void rain_delay_to_resumed_in_progress_allowed() {
    assertThat(sm.isAllowed(GameStatus.DELAYED, GameStatus.IN_PROGRESS)).isTrue();
    assertThat(sm.isAllowed(GameStatus.DELAYED, GameStatus.MID_INNING)).isTrue();
  }

  @Test
  void suspended_can_resume_or_finish_but_not_revert_to_scheduled() {
    assertThat(sm.isAllowed(GameStatus.SUSPENDED, GameStatus.IN_PROGRESS)).isTrue();
    assertThat(sm.isAllowed(GameStatus.SUSPENDED, GameStatus.COMPLETED)).isTrue();
    assertThat(sm.isAllowed(GameStatus.SUSPENDED, GameStatus.SCHEDULED)).isFalse();
  }

  @Test
  void transition_returns_to_even_when_disallowed_and_logs_warn() {
    // API is authoritative — we don't refuse the update, only flag it.
    GameStatus result = sm.transition(12345L, GameStatus.COMPLETED, GameStatus.IN_PROGRESS);
    assertThat(result).isEqualTo(GameStatus.IN_PROGRESS);
  }

  @Test
  void transition_identity_returns_unchanged() {
    assertThat(sm.transition(1L, GameStatus.IN_PROGRESS, GameStatus.IN_PROGRESS))
        .isEqualTo(GameStatus.IN_PROGRESS);
  }

  // --- polling cadence ----------------------------------------------------

  @Test
  void in_progress_polls_at_12_seconds() {
    assertThat(GameStateMachine.pollIntervalFor(GameStatus.IN_PROGRESS))
        .isEqualTo(Duration.ofSeconds(12));
  }

  @Test
  void completed_and_postponed_do_not_poll() {
    assertThat(GameStateMachine.shouldPoll(GameStatus.COMPLETED)).isFalse();
    assertThat(GameStateMachine.shouldPoll(GameStatus.POSTPONED)).isFalse();
  }

  @Test
  void in_progress_should_poll_true() {
    assertThat(GameStateMachine.shouldPoll(GameStatus.IN_PROGRESS)).isTrue();
    assertThat(GameStateMachine.shouldPoll(GameStatus.MID_INNING)).isTrue();
  }
}
