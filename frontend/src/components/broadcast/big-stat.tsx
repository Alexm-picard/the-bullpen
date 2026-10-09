/**
 * <BigStat> - the broadcast identity's big-number block (decision [160]):
 * condensed uppercase label over a huge tabular numeral, optional sub-line.
 * Gold tone reserved for the one number a screen wants to shout.
 */

import { colors, typography } from "../../design/broadcast";

export type BigStatProps = {
  label: string;
  value: string;
  sub?: string;
  tone?: "default" | "gold";
  /** Reserve the numeral's width in ch so a changing value does not reflow the row. */
  minCh?: number;
};

export function BigStat({
  label,
  value,
  sub,
  tone = "default",
  minCh,
}: BigStatProps) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontWeight: typography.weights.semibold,
          fontSize: 13,
          letterSpacing: "0.1em",
          textTransform: "uppercase",
          color: colors.textMuted,
        }}
      >
        {label}
      </span>
      <span
        style={{
          display: "inline-block",
          minWidth: minCh ? `${minCh}ch` : undefined,
          whiteSpace: "nowrap",
          fontFamily: typography.fonts.mono,
          fontWeight: typography.weights.heavy,
          fontSize: typography.scale[6],
          lineHeight: 1,
          fontFeatureSettings: '"tnum" 1',
          color: tone === "gold" ? colors.goldInk : colors.ink,
        }}
      >
        {value}
      </span>
      {sub && (
        <span
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 12,
            fontFeatureSettings: '"tnum" 1',
            color: colors.textMuted,
          }}
        >
          {sub}
        </span>
      )}
    </div>
  );
}
