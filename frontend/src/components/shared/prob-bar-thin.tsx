/**
 * <ProbBarThin> — a 3px-tall horizontal probability bar.
 *
 * Used inside the live-prediction widget on /home to render the HR probability as a
 * sharp accent stripe on a muted track. No animation by default; the parent re-renders
 * on data change which Mantine handles via the CSS transition on width.
 *
 * Width is the source of truth — `value` is clamped to [0, 1].
 */
import { Box } from "@mantine/core";

import { colors, radii } from "../../design/tokens";

export type ProbBarThinProps = {
  /** Probability in [0, 1]. Values outside the range are clamped. */
  value: number;
  /** Optional accent override; defaults to the brand accent. */
  color?: string;
  /** Optional aria-label override; defaults to `Probability {pct}%`. */
  ariaLabel?: string;
  /** Bar height in px (defaults to 3). */
  height?: number;
};

export function ProbBarThin({
  value,
  color = colors.scarlet,
  ariaLabel,
  height = 3,
}: ProbBarThinProps) {
  const clamped = Math.max(0, Math.min(1, value));
  const pct = clamped * 100;
  return (
    <Box
      role="progressbar"
      aria-valuenow={Math.round(pct)}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={ariaLabel ?? `Probability ${pct.toFixed(1)} percent`}
      style={{
        width: "100%",
        height,
        backgroundColor: colors.bgEmphasis,
        borderRadius: radii.pill,
        overflow: "hidden",
      }}
    >
      <Box
        style={{
          width: `${pct}%`,
          height: "100%",
          backgroundColor: color,
          borderRadius: radii.pill,
          transition: "width 200ms cubic-bezier(0.4, 0, 0.2, 1)",
        }}
      />
    </Box>
  );
}
