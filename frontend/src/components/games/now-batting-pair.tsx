/**
 * <NowBattingPair> — compact 2-column batter / pitcher identity block.
 *
 * Deliberately NOT a duplicate of `<PlayerProfileCard>`. The full Matchup
 * Report has the rich player card with tool grades + summary; this is the
 * "live-game moment" condensed version — ~⅓ the height, one-line bios, and
 * a "this game" line that wouldn't make sense in the season-aggregate
 * matchup report.
 *
 * Layout per half:
 *   - Navy 8px lower-third header bar: ROLE label left, jersey + team mono
 *     right (silver). Same idiom as `<PlayerProfileCard>` header.
 *   - 16px padded body: Saira-heavy 20px uppercase name; mono 12px muted
 *     position / hand / age line; body 14px "this game" line.
 *
 * At <900px the two halves stack vertically (CSS in live-game.css).
 */

import type { NowBattingHalf } from "../../data/games-fixtures";
import { colors, typography } from "../../design/tokens";

export type NowBattingPairProps = {
  batter: NowBattingHalf;
  pitcher: NowBattingHalf;
};

function Half({ half }: { half: NowBattingHalf }) {
  return (
    <section
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: 2,
        display: "flex",
        flexDirection: "column",
      }}
      aria-labelledby={`now-batting-${half.role.toLowerCase()}`}
    >
      <header
        id={`now-batting-${half.role.toLowerCase()}`}
        style={{
          backgroundColor: colors.navy,
          color: colors.textOnNavy,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          padding: "6px 14px",
        }}
      >
        <span
          style={{
            fontFamily: typography.fonts.display,
            fontSize: 12,
            fontWeight: typography.weights.bold,
            textTransform: "uppercase",
            letterSpacing: "0.08em",
          }}
        >
          {half.role}
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.silver,
            letterSpacing: "0.04em",
          }}
        >
          #{half.jersey} · {half.team}
        </span>
      </header>
      <div style={{ padding: "12px 14px" }}>
        <div
          style={{
            fontFamily: typography.fonts.display,
            fontSize: typography.scale[3], // 20
            fontWeight: typography.weights.heavy,
            color: colors.textStrong,
            textTransform: "uppercase",
            letterSpacing: "0.005em",
            lineHeight: 1.05,
          }}
        >
          {half.name}
        </div>
        <div
          style={{
            marginTop: 4,
            display: "flex",
            gap: 8,
            flexWrap: "wrap",
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.textMuted,
            letterSpacing: "0.02em",
            fontFeatureSettings: '"tnum" 1',
          }}
        >
          <span>{half.position}</span>
          <span>·</span>
          <span>{half.hand}</span>
          <span>·</span>
          <span>Age {half.age}</span>
        </div>
        <div
          style={{
            marginTop: 8,
            paddingTop: 8,
            borderTop: `1px solid ${colors.bgEmphasis}`,
            fontFamily: typography.fonts.body,
            fontSize: 13,
            color: colors.textDefault,
            lineHeight: 1.45,
          }}
        >
          <span
            style={{
              fontFamily: typography.fonts.display,
              fontSize: 10,
              fontWeight: typography.weights.bold,
              textTransform: "uppercase",
              letterSpacing: "0.1em",
              color: colors.textMuted,
              marginRight: 8,
            }}
          >
            This Game
          </span>
          {half.thisGame}
        </div>
      </div>
    </section>
  );
}

export function NowBattingPair({ batter, pitcher }: NowBattingPairProps) {
  return (
    <div className="now-batting__pair">
      <Half half={pitcher} />
      <Half half={batter} />
    </div>
  );
}
