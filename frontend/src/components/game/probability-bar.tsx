/**
 * 5-segment horizontal stacked probability bar (leaf 4d.2).
 *
 * Pure SVG. Computes segment offsets up-front (no running counter during the
 * render pass — keeps eslint's react-hooks/immutability rule happy and reads
 * better in any case).
 */
import { colors } from "../../design/tokens";

import { CLASS_COLOR, PITCH_OUTCOME_CLASSES } from "./pitch-outcome-classes";

export type ProbabilityBarProps = {
  predicted: Record<string, number> | null;
  height?: number;
  width?: number;
};

export function ProbabilityBar({
  predicted,
  height = 12,
  width = 160,
}: ProbabilityBarProps) {
  if (predicted == null) {
    return (
      <svg
        width={width}
        height={height}
        role="img"
        aria-label="No prediction available"
        viewBox={`0 0 ${width} ${height}`}
      >
        <rect
          x={0}
          y={0}
          width={width}
          height={height}
          fill={colors.bgSubtle}
          stroke={colors.bgEmphasis}
        />
      </svg>
    );
  }

  const classes = PITCH_OUTCOME_CLASSES.map((c) => ({
    name: c,
    p: clamp01(predicted[c] ?? 0),
  }));
  const total = classes.reduce((acc, c) => acc + c.p, 0) || 1;

  // Pre-compute cumulative x via reduce so the render pass is pure.
  const segments = classes.reduce<
    {
      name: (typeof classes)[number]["name"];
      p: number;
      x: number;
      width: number;
    }[]
  >((acc, c) => {
    const prev = acc[acc.length - 1];
    const segX = prev ? prev.x + prev.width : 0;
    const segWidth = (c.p / total) * width;
    return [...acc, { ...c, x: segX, width: segWidth }];
  }, []);

  return (
    <svg
      width={width}
      height={height}
      role="img"
      aria-label={`Predicted distribution: ${classes
        .map((c) => `${c.name} ${(c.p * 100).toFixed(0)}%`)
        .join(", ")}`}
      viewBox={`0 0 ${width} ${height}`}
    >
      {segments.map((seg) => (
        <rect
          key={seg.name}
          x={seg.x}
          y={0}
          width={seg.width}
          height={height}
          fill={CLASS_COLOR[seg.name]}
        >
          <title>
            {seg.name} · {(seg.p * 100).toFixed(1)}%
          </title>
        </rect>
      ))}
      <rect
        x={0}
        y={0}
        width={width}
        height={height}
        fill="none"
        stroke={colors.bgEmphasis}
      />
    </svg>
  );
}

function clamp01(p: number): number {
  if (Number.isNaN(p)) return 0;
  if (p < 0) return 0;
  if (p > 1) return 1;
  return p;
}
