/**
 * Reliability diagram (leaf 4b.3) — calibrated predicted vs. observed plot.
 *
 * Pure SVG. No charts library — the geometry is simple (axes, diagonal, scatter)
 * and avoiding a dep keeps the bundle thin. Scales: 240 × 240 plot area inside a
 * 320 × 320 viewbox so labels have room.
 *
 * Points are sized + intensity-shaded by sample size n via a 5-stop Viridis ramp
 * from tokens. The diagonal y=x reference uses the brand accent.
 *
 * Renders "Insufficient data" when total n is below {@link MIN_SAMPLE_THRESHOLD}.
 */
import { Stack, Text } from "@mantine/core";

import type { CalibrationBin } from "../../api/players";
import { colors, typography } from "../../design/tokens";

export const MIN_SAMPLE_THRESHOLD = 50;

const PLOT = {
  width: 240,
  height: 240,
  marginLeft: 50,
  marginTop: 16,
  marginBottom: 40,
  marginRight: 16,
};
const SVG_WIDTH = PLOT.width + PLOT.marginLeft + PLOT.marginRight;
const SVG_HEIGHT = PLOT.height + PLOT.marginTop + PLOT.marginBottom;
const TICKS = [0, 0.25, 0.5, 0.75, 1.0];

export type ReliabilityDiagramProps = {
  bins: CalibrationBin[] | undefined;
  isLoading?: boolean;
  isError?: boolean;
  errorMessage?: string;
  /** Caption shown below the chart (e.g., "pitch_outcome_pre v3"). */
  caption?: string;
};

export function ReliabilityDiagram({
  bins,
  isLoading,
  isError,
  errorMessage,
  caption,
}: ReliabilityDiagramProps) {
  if (isError) {
    return (
      <Text c="red" size="sm">
        Could not load calibration data{errorMessage ? `: ${errorMessage}` : ""}
        .
      </Text>
    );
  }

  if (isLoading) {
    return (
      <Text c="dimmed" size="sm">
        Loading calibration…
      </Text>
    );
  }

  const totalN = (bins ?? []).reduce((acc, b) => acc + b.n, 0);
  if (!bins || totalN < MIN_SAMPLE_THRESHOLD) {
    return (
      <Text c="dimmed" size="sm">
        Insufficient data for a reliability diagram — need ≥{" "}
        {MIN_SAMPLE_THRESHOLD} predictions; have {totalN}.
      </Text>
    );
  }

  const xToPx = (p: number) => PLOT.marginLeft + p * PLOT.width;
  const yToPx = (p: number) => PLOT.marginTop + (1 - p) * PLOT.height;
  const maxN = Math.max(...bins.map((b) => b.n));

  return (
    <Stack gap={4}>
      <svg
        width={SVG_WIDTH}
        height={SVG_HEIGHT}
        viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`}
        role="img"
        aria-label="Reliability diagram"
        style={{
          fontFamily: typography.fonts.data,
          fontSize: typography.scale[0] - 1,
        }}
      >
        {/* Plot background */}
        <rect
          x={PLOT.marginLeft}
          y={PLOT.marginTop}
          width={PLOT.width}
          height={PLOT.height}
          fill={colors.bgElevated}
          stroke={colors.bgEmphasis}
        />

        {/* Gridlines + tick labels */}
        {TICKS.map((t) => (
          <g key={`gx-${t}`}>
            <line
              x1={xToPx(t)}
              x2={xToPx(t)}
              y1={PLOT.marginTop}
              y2={PLOT.marginTop + PLOT.height}
              stroke={colors.bgEmphasis}
              strokeDasharray="2,3"
            />
            <text
              x={xToPx(t)}
              y={PLOT.marginTop + PLOT.height + 14}
              textAnchor="middle"
              fill={colors.textMuted}
            >
              {t.toFixed(2)}
            </text>
          </g>
        ))}
        {TICKS.map((t) => (
          <g key={`gy-${t}`}>
            <line
              x1={PLOT.marginLeft}
              x2={PLOT.marginLeft + PLOT.width}
              y1={yToPx(t)}
              y2={yToPx(t)}
              stroke={colors.bgEmphasis}
              strokeDasharray="2,3"
            />
            <text
              x={PLOT.marginLeft - 6}
              y={yToPx(t) + 4}
              textAnchor="end"
              fill={colors.textMuted}
            >
              {t.toFixed(2)}
            </text>
          </g>
        ))}

        {/* Diagonal y = x reference */}
        <line
          x1={xToPx(0)}
          y1={yToPx(0)}
          x2={xToPx(1)}
          y2={yToPx(1)}
          stroke={colors.accent}
          strokeWidth={1.5}
        />

        {/* Bin points */}
        {bins.map((b, i) => {
          const cx = xToPx(b.predicted);
          const cy = yToPx(b.actual);
          const r = 4 + 4 * (b.n / maxN);
          const intensity = Math.min(4, Math.floor((b.n / maxN) * 4));
          const fill = colors.viz.viridis[intensity];
          return (
            <circle
              key={`bin-${i}`}
              cx={cx}
              cy={cy}
              r={r}
              fill={fill}
              fillOpacity={0.85}
              stroke={colors.textStrong}
              strokeWidth={0.5}
            >
              <title>
                n={b.n} · predicted={b.predicted.toFixed(3)} · actual=
                {b.actual.toFixed(3)}
              </title>
            </circle>
          );
        })}

        {/* Axis labels */}
        <text
          x={PLOT.marginLeft + PLOT.width / 2}
          y={SVG_HEIGHT - 4}
          textAnchor="middle"
          fill={colors.textDefault}
          fontSize={typography.scale[0]}
        >
          predicted probability
        </text>
        <text
          x={-(PLOT.marginTop + PLOT.height / 2)}
          y={14}
          transform="rotate(-90)"
          textAnchor="middle"
          fill={colors.textDefault}
          fontSize={typography.scale[0]}
        >
          actual frequency
        </text>
      </svg>
      {caption ? (
        <Text size="xs" c="dimmed">
          {caption} · n={totalN}
        </Text>
      ) : null}
    </Stack>
  );
}
