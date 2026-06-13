/**
 * <ParkHrHeatmap> - the 30-park home-run-probability heatmap (B1), the live face
 * of the parks page. Renders one row per park, sorted by P(HR) descending, with a
 * viridis-intensity bar (normalised across the 30 parks for THIS launch condition)
 * and the probability printed alongside.
 *
 * Presentational only: the page owns the launch-condition inputs + the
 * useAllParksPrediction query and hands the resolved map down. Color is never the
 * sole carrier - every row prints its P(HR)% (the same a11y rule the cellColor
 * heatmaps follow).
 */

import type { ParkRow } from "../../data/parks-fixtures";
import { colors, typography } from "../../design/broadcast";
import { viridis } from "./_viridis";

export type ParkHrHeatmapProps = {
  /** Park id (3-letter abbrev) -> model P(HR) in [0, 1] for the chosen launch condition. */
  probHrByPark: Record<string, number>;
  /** Park metadata for the human-readable name; falls back to the id when absent. */
  parkRows: ParkRow[];
};

export function ParkHrHeatmap({ probHrByPark, parkRows }: ParkHrHeatmapProps) {
  const nameById = new Map(parkRows.map((p) => [p.id, p.parkName]));
  const entries = Object.entries(probHrByPark)
    .map(([id, p]) => ({ id, p, name: nameById.get(id) ?? id }))
    .sort((a, b) => b.p - a.p);

  if (entries.length === 0) {
    return (
      <p style={{ fontFamily: typography.fonts.body, color: colors.textMuted }}>
        No per-park probabilities returned for this launch condition.
      </p>
    );
  }

  // Normalise the viridis intensity + bar width across the spread of THIS response,
  // so the most and least HR-prone parks anchor the ramp regardless of the absolute
  // level (a 110/28 scorcher and a 95/12 grounder both read clearly).
  const probs = entries.map((e) => e.p);
  const max = Math.max(...probs);
  const min = Math.min(...probs);
  const span = max - min || 1;

  return (
    <div role="table" aria-label="Home-run probability by park">
      {entries.map((e, i) => {
        const norm = (e.p - min) / span; // 0..1 across the 30 parks
        return (
          <div
            key={e.id}
            role="row"
            id={`park-hr-row-${e.id}`}
            style={{
              display: "grid",
              gridTemplateColumns: "28px 52px 1fr 64px",
              alignItems: "center",
              gap: 10,
              padding: "5px 8px",
              borderBottom: `1px solid ${colors.rule}`,
              backgroundColor: i % 2 === 0 ? colors.panel : colors.fieldSubtle,
            }}
          >
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 11,
                color: colors.textMuted,
                textAlign: "right",
              }}
            >
              {i + 1}
            </span>
            <span
              style={{
                fontFamily: typography.fonts.display,
                fontStyle: "italic",
                fontWeight: typography.weights.semibold,
                fontSize: 14,
                letterSpacing: "0.04em",
                color: colors.ink,
              }}
            >
              {e.id}
            </span>
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                minWidth: 0,
              }}
            >
              <span
                aria-hidden="true"
                style={{
                  height: 12,
                  width: `${20 + norm * 80}%`,
                  backgroundColor: viridis(norm),
                  flexShrink: 0,
                  borderRadius: 1,
                }}
              />
              <span
                style={{
                  fontFamily: typography.fonts.body,
                  fontSize: 12,
                  color: colors.textMuted,
                  whiteSpace: "nowrap",
                  overflow: "hidden",
                  textOverflow: "ellipsis",
                }}
              >
                {e.name}
              </span>
            </div>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontFeatureSettings: '"tnum" 1',
                fontSize: 13,
                fontWeight: typography.weights.semibold,
                color: colors.ink,
                textAlign: "right",
              }}
            >
              {(e.p * 100).toFixed(1)}%
            </span>
          </div>
        );
      })}
    </div>
  );
}
