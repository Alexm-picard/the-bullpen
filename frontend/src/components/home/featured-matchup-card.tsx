/**
 * <FeaturedMatchupCard> — the full-width card under the slate table that
 * surfaces the editorial pick for tonight.
 *
 * Pattern: report-sheet panel (bgSheet, 1-px bgEmphasis border, radius 2)
 * with a navy lower-third header bar at the top — same vocabulary as
 * KeyNotes and PlayerProfileCard. Inside:
 *   1. Two-column batter / pitcher header (1fr | 1fr, stacks at < 900px).
 *      Each side: small scarlet eyebrow (BATTER / PITCHER), Saira Condensed
 *      heavy 32px name, mono meta line (#jersey · pos · hand · team).
 *   2. Two numbered key-reads in an <ol> with scarlet "01" / "02" markers,
 *      same numbering pattern as KeyNotes but at 15px body for editorial
 *      gravity (KeyNotes is 14px; this card is the page's gravity well).
 *   3. Scarlet CTA pill bottom-right linking to the full report.
 *
 * Distinct from <KeyNotes> because it carries additional structure (matchup
 * header strip + CTA pill). Reuses KeyNotes' visual numbering language so
 * the page reads as a single voice with the Matchup Report.
 */

import { Link } from "react-router-dom";

import type { ScoutingPlayer } from "../../data/matchup-fixtures";
import { radii, colors, typography } from "../../design/tokens";

export type FeaturedMatchupCardProps = {
  batter: ScoutingPlayer;
  pitcher: ScoutingPlayer;
  /** Free-form context line shown in the navy header strip (right side). */
  context: string;
  /** Exactly two key-read paragraphs. */
  keyReads: [string, string];
  /** CTA href (e.g. "/players/judge_aaron"). */
  ctaHref: string;
  /** CTA label (e.g. "Pull the full report →"). */
  ctaLabel: string;
};

function SideEyebrow({ children }: { children: React.ReactNode }) {
  return (
    <span
      style={{
        fontFamily: typography.fonts.display,
        fontSize: 12,
        fontWeight: typography.weights.bold,
        textTransform: "uppercase",
        letterSpacing: "0.1em",
        color: colors.scarlet,
        display: "block",
        marginBottom: 4,
      }}
    >
      {children}
    </span>
  );
}

function PlayerSide({
  label,
  player,
}: {
  label: "Batter" | "Pitcher";
  player: ScoutingPlayer;
}) {
  return (
    <div>
      <SideEyebrow>{label}</SideEyebrow>
      <div
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[5], // 32
          fontWeight: typography.weights.heavy,
          color: colors.textStrong,
          textTransform: "uppercase",
          letterSpacing: "0.005em",
          lineHeight: typography.lineHeights.display,
        }}
      >
        {player.name}
      </div>
      <div
        style={{
          marginTop: 4,
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          color: colors.textMuted,
          letterSpacing: "0.02em",
          fontFeatureSettings: '"tnum" 1',
        }}
      >
        #{player.jersey} · {player.position} · {player.hand} · {player.team}
      </div>
    </div>
  );
}

export function FeaturedMatchupCard({
  batter,
  pitcher,
  context,
  keyReads,
  ctaHref,
  ctaLabel,
}: FeaturedMatchupCardProps) {
  return (
    <section
      className="home-featured"
      aria-labelledby="featured-matchup-header"
      style={{
        backgroundColor: colors.bgSheet,
        border: `1px solid ${colors.bgEmphasis}`,
        borderRadius: radii.sm,
      }}
    >
      {/* Navy lower-third header */}
      <header
        id="featured-matchup-header"
        style={{
          backgroundColor: colors.navy,
          color: colors.textOnNavy,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "baseline",
          padding: "10px 16px",
          gap: 12,
          flexWrap: "wrap",
        }}
      >
        <span
          style={{
            fontFamily: typography.fonts.display,
            fontSize: 14,
            fontWeight: typography.weights.bold,
            textTransform: "uppercase",
            letterSpacing: "0.08em",
          }}
        >
          Featured Matchup &middot; Tonight&rsquo;s Top Read
        </span>
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.silver,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {context}
        </span>
      </header>

      {/* Two-column batter/pitcher header */}
      <div
        className="home-featured__cols"
        style={{
          padding: "20px 24px",
          borderBottom: `1px solid ${colors.bgEmphasis}`,
        }}
      >
        <PlayerSide label="Batter" player={batter} />
        <PlayerSide label="Pitcher" player={pitcher} />
      </div>

      {/* Two numbered key-reads */}
      <ol
        style={{
          listStyle: "none",
          margin: 0,
          padding: "20px 24px",
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        {keyReads.map((note, i) => (
          <li
            key={i}
            style={{
              display: "grid",
              gridTemplateColumns: "32px 1fr",
              gap: 12,
              alignItems: "baseline",
              fontFamily: typography.fonts.body,
              fontSize: 15,
              lineHeight: typography.lineHeights.body,
              color: colors.textDefault,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 16,
                fontWeight: typography.weights.bold,
                color: colors.scarlet,
              }}
            >
              {String(i + 1).padStart(2, "0")}
            </span>
            <span>{note}</span>
          </li>
        ))}
      </ol>

      {/* CTA pill */}
      <div
        style={{
          padding: "0 24px 20px",
          display: "flex",
          justifyContent: "flex-end",
        }}
      >
        <Link
          to={ctaHref}
          style={{
            display: "inline-flex",
            alignItems: "center",
            gap: 8,
            padding: "10px 18px",
            backgroundColor: colors.scarlet,
            color: colors.bgSheet,
            fontFamily: typography.fonts.display,
            fontWeight: typography.weights.bold,
            fontSize: 14,
            textTransform: "uppercase",
            letterSpacing: "0.08em",
            borderRadius: radii.sm,
            textDecoration: "none",
          }}
        >
          {ctaLabel}
        </Link>
      </div>
    </section>
  );
}
