/**
 * <LivePitchLog> — the hero of /games. A vertical stack of PitchCard
 * instances, most-recent first.
 *
 * Why a thin wrapper: the existing `<PitchCard>` already carries the signature
 * single-pitch primitive (count + inning + pitch type + probability bar +
 * agreement marker). The log's responsibility is just composition + an
 * accent rule on the top (= newest) card that signals "this is the just-
 * thrown pitch" without any pulsing/animation.
 *
 * Accent rule: 3px scarlet left border on the first card. Not red-on-red,
 * not animated — just a printed-marginalia tick mark. Disagreement cards
 * already carry a 2px scarlet right edge (from PitchCard's existing logic),
 * so the visual vocabulary stays consistent: scarlet = "this pitch wants
 * your attention," whether because it just happened (left rule) or because
 * the model was wrong (right rule).
 *
 * No virtualization in v1 — 20 cards is well under the threshold where
 * react-window or similar would pay off.
 */

import type { LivePitchRow } from "../../api/games";
import { colors } from "../../design/tokens";
import { PitchCard } from "../game/pitch-card";

export type LivePitchLogProps = {
  pitches: LivePitchRow[];
  /** Optional caption above the log, mono-style. */
  caption?: string;
};

export function LivePitchLog({ pitches, caption }: LivePitchLogProps) {
  if (pitches.length === 0) {
    return (
      <div
        style={{
          backgroundColor: colors.bgSheet,
          border: `1px solid ${colors.bgEmphasis}`,
          borderRadius: 2,
          padding: 32,
          textAlign: "center",
          fontFamily: "var(--font-body)",
          color: colors.textMuted,
        }}
      >
        No pitches yet — waiting on the first pitch of the half-inning.
      </div>
    );
  }

  return (
    <div
      role="log"
      aria-label="Pitch log, most recent first"
      aria-live="polite"
    >
      {caption ? (
        <div
          style={{
            fontFamily: "var(--font-body)",
            fontSize: 12,
            fontWeight: 600,
            color: colors.textMuted,
            padding: "0 0 8px 4px",
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {caption}
        </div>
      ) : null}
      <div
        className="live-pitch-log"
        style={{ display: "flex", flexDirection: "column", gap: 6 }}
      >
        {pitches.map((p, i) => (
          <div
            key={`${p.gameId}-${p.cursor}`}
            data-newest={i === 0 ? "true" : undefined}
            style={{
              position: "relative",
              borderLeft:
                i === 0
                  ? `3px solid ${colors.scarlet}`
                  : "3px solid transparent",
              paddingLeft: i === 0 ? 4 : 4,
            }}
          >
            <PitchCard pitch={p} />
          </div>
        ))}
      </div>
    </div>
  );
}
