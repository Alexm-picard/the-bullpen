/**
 * <SprayChart> — fan-shaped batted-ball density chart, scouting-packet style.
 *
 * Visual: a polar-coordinate fan (foul lines L and R, outfield arc) divided
 * into five sectors (LF-pull/oppo, LF-gap, CF, RF-gap, RF-pull/oppo for L/R
 * bats respectively). Each sector is filled with a `spray` ramp tint per its
 * density score (0..1) and labeled with the batted-ball count.
 *
 * Pure SVG. role="img" + aria-label on the root SVG paraphrasing the dominant
 * sector. Counts are always rendered as on-canvas text so color is never the
 * sole carrier of meaning (a11y §8). A legend strip beneath shows the ramp.
 */

import type { SprayZone } from "../../data/matchup-fixtures";
import { radii, colors, typography } from "../../design/broadcast";

export type SprayChartProps = {
  zones: SprayZone[];
  /** Optional caption above the chart. */
  caption?: string;
};

// ── Geometry ─────────────────────────────────────────────────────────────────

const SVG_WIDTH = 320;
const SVG_HEIGHT = 240;
const ORIGIN_X = SVG_WIDTH / 2;
const ORIGIN_Y = SVG_HEIGHT - 20; // home plate
const RADIUS = 200;

// 90° fan, -45° to +45° from straight-up. Five equal-angle sectors.
const FAN_HALF = 45;
const SECTOR_COUNT = 5;
const SECTOR_ANGLE = (2 * FAN_HALF) / SECTOR_COUNT; // 18° each

function angleToRad(deg: number): number {
  // 0° = straight up (north), positive = clockwise toward right field.
  return ((deg - 90) * Math.PI) / 180;
}

function arcPoint(angleDeg: number, r: number): { x: number; y: number } {
  const rad = angleToRad(angleDeg);
  return {
    x: ORIGIN_X + r * Math.cos(rad),
    y: ORIGIN_Y + r * Math.sin(rad),
  };
}

function sectorPath(startDeg: number, endDeg: number): string {
  // Pie-slice from origin to the outer arc, sweeping startDeg → endDeg.
  const start = arcPoint(startDeg, RADIUS);
  const end = arcPoint(endDeg, RADIUS);
  return `M ${ORIGIN_X} ${ORIGIN_Y} L ${start.x.toFixed(2)} ${start.y.toFixed(2)} A ${RADIUS} ${RADIUS} 0 0 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)} Z`;
}

function rampColor(density: number): string {
  const ramp = colors.spray;
  const clamped = Math.max(0, Math.min(1, density));
  if (clamped <= 0.001) return colors.panel;
  if (clamped < 0.33) return ramp[0];
  if (clamped < 0.66) return ramp[1];
  if (clamped < 0.88) return ramp[2];
  return ramp[3];
}

function describeDominant(zones: SprayZone[]): string {
  if (zones.length === 0) return "no spray data";
  // Non-empty after the guard, so [0] is defined.
  const top = [...zones].sort((a, b) => b.density - a.density)[0]!;
  const totalCount = zones.reduce((acc, z) => acc + z.count, 0);
  const pct =
    totalCount > 0 ? ((top.count / totalCount) * 100).toFixed(0) : "0";
  return `dominant sector ${top.label} with ${top.count} balls in play (${pct}% of total)`;
}

export function SprayChart({ zones, caption }: SprayChartProps) {
  // Map zones to the fan, left-to-right. Zones come in field-order
  // (LF-pull / LF-gap / CF / RF-gap / RF-pull or oppo).
  const sectors = zones.slice(0, SECTOR_COUNT).map((z, i) => {
    const startDeg = -FAN_HALF + i * SECTOR_ANGLE;
    const endDeg = startDeg + SECTOR_ANGLE;
    return { zone: z, startDeg, endDeg };
  });

  const ariaLabel = `Spray chart — ${describeDominant(zones)}`;

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {caption ? (
        <div
          style={{
            fontFamily: typography.fonts.body,
            fontSize: 12,
            fontWeight: typography.weights.semibold,
            color: colors.textMuted,
            letterSpacing: "0.04em",
            textTransform: "uppercase",
          }}
        >
          {caption}
        </div>
      ) : null}
      <div
        style={{
          backgroundColor: colors.panel,
          border: `1px solid ${colors.rule}`,
          borderRadius: radii.sm,
          padding: 8,
        }}
      >
        <svg
          role="img"
          aria-label={ariaLabel}
          width="100%"
          viewBox={`0 0 ${SVG_WIDTH} ${SVG_HEIGHT}`}
          style={{ display: "block", maxWidth: SVG_WIDTH }}
        >
          <title>{ariaLabel}</title>
          {/* Background field */}
          <rect
            x={0}
            y={0}
            width={SVG_WIDTH}
            height={SVG_HEIGHT}
            fill={colors.panel}
          />
          {/* Sectors */}
          {sectors.map(({ zone, startDeg, endDeg }) => (
            <path
              key={zone.id}
              d={sectorPath(startDeg, endDeg)}
              fill={rampColor(zone.density)}
              stroke={colors.rule}
              strokeWidth={0.75}
              fillOpacity={0.9}
            >
              <title>{`${zone.label}: ${zone.count} batted balls`}</title>
            </path>
          ))}
          {/* Infield arc (small dirt arc near origin) */}
          <path
            d={`M ${ORIGIN_X - 28} ${ORIGIN_Y} A 28 28 0 0 1 ${ORIGIN_X + 28} ${ORIGIN_Y}`}
            fill={colors.fieldSubtle}
            stroke={colors.rule}
            strokeWidth={0.75}
          />
          {/* Foul lines */}
          <line
            x1={ORIGIN_X}
            y1={ORIGIN_Y}
            x2={arcPoint(-FAN_HALF, RADIUS).x}
            y2={arcPoint(-FAN_HALF, RADIUS).y}
            stroke={colors.chrome}
            strokeWidth={1.5}
          />
          <line
            x1={ORIGIN_X}
            y1={ORIGIN_Y}
            x2={arcPoint(FAN_HALF, RADIUS).x}
            y2={arcPoint(FAN_HALF, RADIUS).y}
            stroke={colors.chrome}
            strokeWidth={1.5}
          />
          {/* Outfield arc */}
          <path
            d={`M ${arcPoint(-FAN_HALF, RADIUS).x.toFixed(2)} ${arcPoint(-FAN_HALF, RADIUS).y.toFixed(2)} A ${RADIUS} ${RADIUS} 0 0 1 ${arcPoint(FAN_HALF, RADIUS).x.toFixed(2)} ${arcPoint(FAN_HALF, RADIUS).y.toFixed(2)}`}
            fill="none"
            stroke={colors.chrome}
            strokeWidth={1.5}
          />
          {/* Sector labels (count + zone name) */}
          {sectors.map(({ zone, startDeg, endDeg }) => {
            const midDeg = (startDeg + endDeg) / 2;
            const labelR = RADIUS * 0.62;
            const { x, y } = arcPoint(midDeg, labelR);
            return (
              <g key={`label-${zone.id}`}>
                <text
                  x={x}
                  y={y - 4}
                  textAnchor="middle"
                  fontFamily={typography.fonts.mono}
                  fontSize={13}
                  fontWeight={700}
                  fill={colors.ink}
                >
                  {zone.count}
                </text>
                <text
                  x={x}
                  y={y + 10}
                  textAnchor="middle"
                  fontFamily={typography.fonts.display}
                  fontSize={9}
                  fontWeight={700}
                  letterSpacing="0.04em"
                  fill={colors.textMuted}
                >
                  {zone.label.toUpperCase()}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      {/* Legend strip */}
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          fontFamily: typography.fonts.mono,
          fontSize: 10,
          color: colors.textMuted,
        }}
      >
        <span style={{ letterSpacing: "0.04em", textTransform: "uppercase" }}>
          Low
        </span>
        <div style={{ display: "flex", gap: 1 }}>
          {colors.spray.map((c, i) => (
            <div
              key={i}
              style={{
                width: 22,
                height: 8,
                backgroundColor: c,
                border: `1px solid ${colors.rule}`,
              }}
            />
          ))}
        </div>
        <span style={{ letterSpacing: "0.04em", textTransform: "uppercase" }}>
          High density
        </span>
      </div>
    </div>
  );
}
