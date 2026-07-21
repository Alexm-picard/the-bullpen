package net.thebullpen.baseball.domain;

/**
 * Operationally-meaningful event types written to {@code ops_events} (V015) and surfaced on the Ops
 * dashboard's log. Underscore names map to the frontend's hyphenated display labels (e.g. {@code
 * DRIFT_OK} → {@code DRIFT-OK}). The set mirrors the {@code CHECK} constraint in the migration.
 */
public enum OpsEventType {
  DEPLOY,
  REGISTER,
  PROMOTE,
  DRIFT_OK,
  ALERT,
  RETRAIN_OK,
  RESTORE_DRILL
}
