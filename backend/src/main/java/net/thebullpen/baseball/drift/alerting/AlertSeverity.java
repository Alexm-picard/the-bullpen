package net.thebullpen.baseball.drift.alerting;

/**
 * Three-tier severity for drift alerts (decision [78]):
 *
 * <ul>
 *   <li>{@link #PAGE} — wake-up: champion calibration error > 1.5× training baseline sustained 3+
 *       days. Fires a Discord WARN-equivalent.
 *   <li>{@link #NOTICE} — informational: feature PSI > 0.25 sustained 7+ days. Fires Discord
 *       NOTICE.
 *   <li>{@link #LOGGED} — no Discord. Recorded in {@code alert_history} for the Ops dashboard only.
 * </ul>
 */
public enum AlertSeverity {
  PAGE,
  NOTICE,
  LOGGED
}
