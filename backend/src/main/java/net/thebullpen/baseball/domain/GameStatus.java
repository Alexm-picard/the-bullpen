package net.thebullpen.baseball.domain;

import java.time.Duration;

/**
 * Game lifecycle states from the MLB Stats API's {@code gameData.status.detailedState}, normalised
 * into the set of transitions we actually need (Risk Register G5 resolution).
 *
 * <p>Each state carries the {@link #pollInterval()} the live worker should use when this is the
 * game's current state. {@link #shouldPoll()} short-circuits the worker so postponed / completed
 * games stop costing API quota.
 */
public enum GameStatus {
  /** Scheduled but not yet started. Light polling so we catch first-pitch promptly. */
  SCHEDULED(Duration.ofMinutes(5), true),
  /** Warm-up / batting practice / national anthem. Polls in case of early start. */
  WARMUP(Duration.ofSeconds(60), true),
  /** Live. The hot path — 12 s default per leaf body. */
  IN_PROGRESS(Duration.ofSeconds(12), true),
  /** Between innings. Polling stays at 12 s — the next pitch could land any second. */
  MID_INNING(Duration.ofSeconds(12), true),
  /** Rain / delay. Slow poll waiting for resume. */
  DELAYED(Duration.ofMinutes(2), true),
  /** Suspended mid-game; will resume later (sometimes next day). */
  SUSPENDED(Duration.ofMinutes(10), true),
  /** Postponed before first pitch. Stop polling — schedule fetch will pick up the reschedule. */
  POSTPONED(Duration.ZERO, false),
  /** Game ended normally. Stop polling. */
  COMPLETED(Duration.ZERO, false),
  /** Defensive default for unrecognised MLB API values. Light poll so we don't lose visibility. */
  UNKNOWN(Duration.ofMinutes(5), true);

  private final Duration pollInterval;
  private final boolean shouldPoll;

  GameStatus(Duration pollInterval, boolean shouldPoll) {
    this.pollInterval = pollInterval;
    this.shouldPoll = shouldPoll;
  }

  public Duration pollInterval() {
    return pollInterval;
  }

  public boolean shouldPoll() {
    return shouldPoll;
  }

  /**
   * Map the MLB API's {@code detailedState} string into our enum. The API's vocabulary is wider
   * than we need (e.g. "Final: Tied" / "Game Over" both map to COMPLETED); we collapse defensively
   * — anything we don't recognise becomes {@link #UNKNOWN} rather than throwing, so a new MLB API
   * label doesn't bring the worker down.
   */
  public static GameStatus fromMlbDetailedState(String detailedState) {
    if (detailedState == null) {
      return UNKNOWN;
    }
    String normalised = detailedState.toLowerCase(java.util.Locale.ROOT);
    if (normalised.contains("postpone")) return POSTPONED;
    if (normalised.contains("suspend")) return SUSPENDED;
    if (normalised.contains("delay")) return DELAYED;
    if (normalised.contains("manager challenge")
        || normalised.contains("review")
        || normalised.equals("in progress")) {
      return IN_PROGRESS;
    }
    if (normalised.contains("mid inning") || normalised.contains("end inning")) {
      return MID_INNING;
    }
    if (normalised.contains("warmup")) return WARMUP;
    if (normalised.equals("scheduled")
        || normalised.contains("pre-game")
        || normalised.contains("pregame")) {
      return SCHEDULED;
    }
    if (normalised.contains("final")
        || normalised.contains("game over")
        || normalised.contains("completed")) {
      return COMPLETED;
    }
    return UNKNOWN;
  }
}
