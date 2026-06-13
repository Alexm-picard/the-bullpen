/**
 * <NoHistoryNote> - the first-class "no data yet" panel for the player profile's
 * live sections (B2). prediction_log is sparse until the pitch model serves a
 * player's matchups live and the outcomes settle, so an empty result is the
 * COMMON case for weeks - this renders it as a designed, explanatory state, not
 * an error and not a terse blank table.
 *
 * Presentational. Mono goldInk kicker + a plain-language explanation of WHY it is
 * empty and what fills it.
 */

import { colors, typography } from "../../design/broadcast";
import { BroadcastPanel } from "../broadcast/broadcast-panel";

export type NoHistoryNoteProps = {
  title?: string;
  children: React.ReactNode;
};

export function NoHistoryNote({
  title = "No prediction history yet",
  children,
}: NoHistoryNoteProps) {
  return (
    <BroadcastPanel padding={16}>
      <span
        style={{
          display: "block",
          marginBottom: 6,
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          fontWeight: typography.weights.semibold,
          letterSpacing: "0.1em",
          textTransform: "uppercase",
          color: colors.goldInk,
        }}
      >
        {title}
      </span>
      <p
        style={{
          margin: 0,
          fontFamily: typography.fonts.body,
          fontSize: 14,
          lineHeight: 1.5,
          color: colors.textMuted,
        }}
      >
        {children}
      </p>
    </BroadcastPanel>
  );
}
