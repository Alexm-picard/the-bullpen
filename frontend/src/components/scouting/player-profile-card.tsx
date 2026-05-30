/**
 * <PlayerProfileCard> — the headshot-less player profile block at the top of
 * each column. Composes meta + summary + grades (via {@link GradeBlock}) in a
 * report-sheet panel with a navy lower-third header bar.
 *
 * `variant` toggles small affordances per side:
 *   - "batter": header label "BATTER" + tab-like layout
 *   - "pitcher": header label "PITCHER" + tab-like layout
 *
 * Grades render inside the card (not in a separate panel) — spec §1 requires
 * this for the eye-grouping of "everything about this player is in one place."
 */

import type { ScoutingPlayer } from "../../data/matchup-fixtures";
import { radii, colors, typography } from "../../design/tokens";

import { GradeBlock } from "./grade-block";

export type PlayerProfileCardProps = {
  player: ScoutingPlayer;
  variant: "batter" | "pitcher";
};

export function PlayerProfileCard({ player, variant }: PlayerProfileCardProps) {
  const headerLabel = variant === "batter" ? "Batter" : "Pitcher";
  return (
    <section
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
        display: "flex",
        flexDirection: "column",
      }}
      aria-labelledby={`profile-${player.id}-header`}
    >
      {/* Lower-third header bar */}
      <header
        id={`profile-${player.id}-header`}
        style={{
          backgroundColor: colors.navy,
          color: colors.textOnNavy,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          padding: "8px 14px",
        }}
      >
        <span
          style={{
            fontFamily: typography.fonts.display,
            fontSize: 13,
            fontWeight: typography.weights.bold,
            textTransform: "uppercase",
            letterSpacing: "0.08em",
          }}
        >
          {headerLabel}
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            color: colors.silver,
            letterSpacing: "0.04em",
          }}
        >
          #{player.jersey} · {player.team}
        </span>
      </header>

      {/* Identity row */}
      <div
        style={{
          padding: "16px 16px 12px",
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <div
          style={{
            fontFamily: typography.fonts.display,
            fontSize: typography.scale[4], // 24
            fontWeight: typography.weights.heavy,
            color: colors.textStrong,
            textTransform: "uppercase",
            letterSpacing: "0.005em",
            lineHeight: 1.05,
          }}
        >
          {player.name}
        </div>
        <div
          style={{
            marginTop: 6,
            display: "flex",
            gap: 10,
            flexWrap: "wrap",
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            color: colors.textMuted,
            letterSpacing: "0.02em",
            fontFeatureSettings: '"tnum" 1',
          }}
        >
          <span>{player.position}</span>
          <span>·</span>
          <span>{player.hand}</span>
          <span>·</span>
          <span>Age {player.age}</span>
          <span>·</span>
          <span>{player.height}</span>
          <span>·</span>
          <span>{player.weight} lb</span>
        </div>
      </div>

      {/* Summary */}
      <div
        style={{
          padding: "12px 16px",
          borderBottom: `1px solid ${colors.bgEmphasis}`,
          fontFamily: typography.fonts.body,
          fontSize: 13,
          color: colors.textDefault,
          lineHeight: 1.45,
        }}
      >
        {player.summary}
      </div>

      {/* Grades block (inside the card) */}
      <div style={{ padding: "12px 16px 16px" }}>
        <div
          style={{
            fontFamily: typography.fonts.display,
            fontSize: 11,
            fontWeight: typography.weights.bold,
            textTransform: "uppercase",
            letterSpacing: "0.1em",
            color: colors.textMuted,
            marginBottom: 6,
          }}
        >
          Tool Grades (20–80)
        </div>
        {player.grades.map((g) => (
          <GradeBlock key={g.label} label={g.label} value={g.value} />
        ))}
      </div>
    </section>
  );
}
