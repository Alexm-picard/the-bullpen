package net.thebullpen.baseball.ingest;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import net.thebullpen.baseball.domain.GameStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allowed transitions in the live-game state machine (Risk Register G5 resolution).
 *
 * <p>The MLB Stats API is the source of truth for the current state; this class is the
 * <em>validator</em> that decides whether a reported transition is plausible — so that an upstream
 * glitch (e.g. a momentary "Scheduled" reading mid-game) doesn't restart polling and double-insert
 * pitches. Logging warns on suspicious transitions instead of throwing; the worker still adopts the
 * API's reported state because the API is ultimately authoritative.
 *
 * <p>The transition table is intentionally permissive in one direction (anything → POSTPONED,
 * anything → SUSPENDED) and strict in the other (COMPLETED is terminal until the next day's
 * schedule rotates the game id).
 */
public class GameStateMachine {

  private static final Logger log = LoggerFactory.getLogger(GameStateMachine.class);

  private static final Map<GameStatus, Set<GameStatus>> ALLOWED =
      Map.ofEntries(
          Map.entry(
              GameStatus.SCHEDULED,
              EnumSet.of(
                  GameStatus.SCHEDULED,
                  GameStatus.WARMUP,
                  GameStatus.IN_PROGRESS,
                  GameStatus.POSTPONED,
                  GameStatus.DELAYED,
                  GameStatus.UNKNOWN)),
          Map.entry(
              GameStatus.WARMUP,
              EnumSet.of(
                  GameStatus.WARMUP,
                  GameStatus.IN_PROGRESS,
                  GameStatus.DELAYED,
                  GameStatus.POSTPONED,
                  GameStatus.UNKNOWN)),
          Map.entry(
              GameStatus.IN_PROGRESS,
              EnumSet.of(
                  GameStatus.IN_PROGRESS,
                  GameStatus.MID_INNING,
                  GameStatus.DELAYED,
                  GameStatus.SUSPENDED,
                  GameStatus.COMPLETED,
                  GameStatus.UNKNOWN)),
          Map.entry(
              GameStatus.MID_INNING,
              EnumSet.of(
                  GameStatus.MID_INNING,
                  GameStatus.IN_PROGRESS,
                  GameStatus.DELAYED,
                  GameStatus.SUSPENDED,
                  GameStatus.COMPLETED,
                  GameStatus.UNKNOWN)),
          Map.entry(
              GameStatus.DELAYED,
              EnumSet.of(
                  GameStatus.DELAYED,
                  GameStatus.IN_PROGRESS,
                  GameStatus.MID_INNING,
                  GameStatus.SUSPENDED,
                  GameStatus.POSTPONED,
                  GameStatus.COMPLETED,
                  GameStatus.UNKNOWN)),
          Map.entry(
              GameStatus.SUSPENDED,
              EnumSet.of(
                  GameStatus.SUSPENDED,
                  GameStatus.IN_PROGRESS,
                  GameStatus.MID_INNING,
                  GameStatus.COMPLETED,
                  GameStatus.UNKNOWN)),
          // Terminal states — the only "transition" is identity.
          Map.entry(GameStatus.POSTPONED, EnumSet.of(GameStatus.POSTPONED)),
          Map.entry(GameStatus.COMPLETED, EnumSet.of(GameStatus.COMPLETED)),
          Map.entry(GameStatus.UNKNOWN, EnumSet.allOf(GameStatus.class)));

  /** True if the API-reported next status is plausible given the current state. */
  public boolean isAllowed(GameStatus from, GameStatus to) {
    return ALLOWED.getOrDefault(from, EnumSet.noneOf(GameStatus.class)).contains(to);
  }

  /**
   * Adopt the API-reported next state, logging a WARN if the transition isn't in the allowed table.
   * Always returns {@code to} (the API is authoritative); the validator is a heuristic that
   * surfaces noise without overriding it.
   */
  public GameStatus transition(long gameId, GameStatus from, GameStatus to) {
    if (from == to) {
      return to;
    }
    if (!isAllowed(from, to)) {
      log.warn(
          "suspicious game-state transition for game {}: {} → {} (adopting per API)",
          gameId,
          from,
          to);
    }
    return to;
  }

  /**
   * Pollable, non-terminal subset of states. Worker uses this to decide whether to keep polling.
   */
  public static boolean shouldPoll(GameStatus status) {
    return status != null && status.shouldPoll();
  }

  /** Convenience: which polling cadence to use for the current state. */
  public static Duration pollIntervalFor(GameStatus status) {
    return status == null ? GameStatus.UNKNOWN.pollInterval() : status.pollInterval();
  }
}
