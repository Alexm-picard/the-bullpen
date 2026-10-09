/**
 * <ModelStandouts> - the /players landing leaderboard. A segmented toggle flips
 * between the hitter metric (xwOBA -> top hitters) and the pitcher metric
 * (xFIP -> top pitchers), so one widget covers both sides of the battery. Rows
 * link to /players/:id.
 *
 * Showcase data (players-landing-fixtures): there is no leaders endpoint yet, so
 * the board is illustrative and the section header says so. When a leaders
 * endpoint lands it serves both metrics off one ?metric= param.
 */

import { useState } from "react";
import { Link } from "react-router";

import {
  MODEL_STANDOUTS,
  type StandoutRow,
} from "../../data/players-landing-fixtures";
import { colors, typography } from "../../design/broadcast";
import { LowerThird } from "../broadcast/lower-third";
import { SegmentedToggle } from "../shared/segmented-toggle";

type MetricKey = "xwoba" | "xfip";

const METRIC_OPTIONS: { key: MetricKey; label: string }[] = (
  ["xwoba", "xfip"] as const
).map((key) => ({ key, label: MODEL_STANDOUTS[key].label }));

const headCellStyle: React.CSSProperties = {
  backgroundColor: colors.chrome,
  color: colors.textOnChrome,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.semibold,
  fontSize: 13,
  letterSpacing: "0.04em",
  textTransform: "uppercase",
  textAlign: "left",
  padding: "9px 12px",
};

function vsAvgStyle(tone: StandoutRow["tone"]): React.CSSProperties {
  return {
    display: "inline-block",
    minWidth: 54,
    textAlign: "right",
    padding: "2px 8px",
    fontFamily: typography.fonts.mono,
    fontWeight: typography.weights.bold,
    fontSize: 13,
    fontFeatureSettings: '"tnum" 1',
    backgroundColor: colors.condFormat[tone],
    // D4 (AA contrast): ink on the retuned good3 token reads at 5.9:1; the old
    // light-on-green pairing sat at 3.9:1.
    color: colors.ink,
  };
}

export function ModelStandouts() {
  const [metricKey, setMetricKey] = useState<MetricKey>("xwoba");
  const metric = MODEL_STANDOUTS[metricKey];

  return (
    <section aria-labelledby="model-standouts-label">
      <div
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 16,
          flexWrap: "wrap",
          marginBottom: 14,
        }}
      >
        <LowerThird id="model-standouts-label" meta={metric.tag}>
          Model Standouts
        </LowerThird>
        <SegmentedToggle
          options={METRIC_OPTIONS}
          value={metricKey}
          onChange={setMetricKey}
          ariaLabel="Leaderboard metric"
        />
      </div>

      <table
        style={{
          width: "100%",
          borderCollapse: "collapse",
          backgroundColor: colors.panel,
          border: `1px solid ${colors.rule}`,
        }}
      >
        <thead>
          <tr>
            <th style={{ ...headCellStyle, width: 34 }} aria-label="Rank" />
            <th style={headCellStyle}>Player</th>
            <th style={headCellStyle}>Team</th>
            <th style={{ ...headCellStyle, textAlign: "right" }}>
              {metric.column}
            </th>
            <th style={{ ...headCellStyle, textAlign: "right" }}>vs avg</th>
          </tr>
        </thead>
        <tbody>
          {metric.rows.map((row, i) => (
            <tr key={row.playerId}>
              <td
                style={{
                  borderTop: `1px solid ${colors.rule}`,
                  padding: "10px 12px",
                  fontFamily: typography.fonts.mono,
                  fontSize: 14,
                  color: colors.textMuted,
                }}
              >
                {i + 1}
              </td>
              <td
                style={{
                  borderTop: `1px solid ${colors.rule}`,
                  padding: "10px 12px",
                  fontSize: 14,
                }}
              >
                <Link
                  to={`/players/${row.playerId}`}
                  className="bp-link--ink"
                  style={{ fontWeight: typography.weights.semibold }}
                >
                  {row.name}
                </Link>
              </td>
              <td
                style={{
                  borderTop: `1px solid ${colors.rule}`,
                  padding: "10px 12px",
                  fontFamily: typography.fonts.mono,
                  fontSize: 12,
                  textTransform: "uppercase",
                  color: colors.textMuted,
                }}
              >
                {row.team}
              </td>
              <td
                style={{
                  borderTop: `1px solid ${colors.rule}`,
                  padding: "10px 12px",
                  fontFamily: typography.fonts.mono,
                  fontWeight: typography.weights.bold,
                  fontSize: 14,
                  fontFeatureSettings: '"tnum" 1',
                  textAlign: "right",
                  color: colors.ink,
                }}
              >
                {row.value}
              </td>
              <td
                style={{
                  borderTop: `1px solid ${colors.rule}`,
                  padding: "10px 12px",
                  textAlign: "right",
                }}
              >
                <span style={vsAvgStyle(row.tone)}>{row.vsAvg}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p
        style={{
          margin: "8px 0 0",
          fontFamily: typography.fonts.mono,
          fontSize: 11,
          letterSpacing: "0.04em",
          color: colors.textMuted,
        }}
      >
        Showcase board · no live leaders endpoint yet
      </p>
    </section>
  );
}
