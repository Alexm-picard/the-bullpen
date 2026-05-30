/**
 * <GradeBlock> — one row inside a PlayerProfileCard's grades column.
 *
 * Visual: a stacked label / value pair plus a "chevron bar" that fills from
 * 20 → 80 on the standard scouting scale. The bar fills with the metric's
 * cellColor() so the eye reads good / neutral / bad without needing to parse
 * the number. The number is ALSO rendered in mono so color is never the sole
 * carrier of meaning (a11y §8).
 *
 * Used inside PlayerProfileCard.batter and PlayerProfileCard.pitcher columns;
 * not used standalone in the page composition.
 */

import { cellColor } from "../../design/cellColor";
import { radii, colors, typography } from "../../design/tokens";
import { METRIC_META } from "../../data/matchup-fixtures";

export type GradeBlockProps = {
  /** Short label, e.g. "Power", "FB", "Ctrl". */
  label: string;
  /** 20–80 grade value; null renders as a muted em-dash with neutral bar. */
  value: number | null;
};

const GRADE_MIN = 20;
const GRADE_MAX = 80;

export function GradeBlock({ label, value }: GradeBlockProps) {
  const numeric =
    typeof value === "number" && Number.isFinite(value) ? value : null;
  const pct =
    numeric === null
      ? 0
      : Math.max(
          0,
          Math.min(1, (numeric - GRADE_MIN) / (GRADE_MAX - GRADE_MIN)),
        );
  const fillBg = cellColor(numeric, METRIC_META.grade);

  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "64px 1fr 36px",
        alignItems: "center",
        gap: 10,
        padding: "4px 0",
      }}
    >
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontSize: typography.scale[0], // 12
          fontWeight: typography.weights.bold,
          textTransform: "uppercase",
          letterSpacing: "0.06em",
          color: colors.textMuted,
        }}
      >
        {label}
      </span>
      <div
        role="meter"
        aria-label={`${label} grade ${numeric ?? "no read"} of 80`}
        aria-valuemin={GRADE_MIN}
        aria-valuemax={GRADE_MAX}
        aria-valuenow={numeric ?? undefined}
        style={{
          position: "relative",
          height: 8,
          backgroundColor: colors.bgSubtle,
          border: `1px solid ${colors.bgEmphasis}`,
          borderRadius: radii.sm,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            bottom: 0,
            width: `${pct * 100}%`,
            backgroundColor: fillBg,
            transition: "width 200ms cubic-bezier(0.4, 0, 0.2, 1)",
          }}
        />
      </div>
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: typography.scale[1], // 14
          fontWeight: typography.weights.bold,
          color: colors.textStrong,
          textAlign: "right",
          fontFeatureSettings: '"tnum" 1',
        }}
      >
        {numeric === null ? "—" : numeric}
      </span>
    </div>
  );
}
