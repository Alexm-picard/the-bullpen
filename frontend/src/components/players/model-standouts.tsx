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
import { Link } from "react-router-dom";

import {
  MODEL_STANDOUTS,
  type StandoutRow,
} from "../../data/players-landing-fixtures";
import { colors, typography } from "../../design/broadcast";
import { LowerThird } from "../broadcast/lower-third";

type MetricKey = "xwoba" | "xfip";

const METRICS: MetricKey[] = ["xwoba", "xfip"];

function toggleButtonStyle(active: boolean): React.CSSProperties {
  return {
    fontFamily: typography.fonts.mono,
    fontWeight: typography.weights.medium,
    fontSize: 12,
    letterSpacing: "0.04em",
    padding: "5px 14px",
    border: "none",
    cursor: "pointer",
    backgroundColor: active ? colors.chrome : "transparent",
    color: active ? colors.textOnChrome : colors.textMuted,
  };
}

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
        <div
          role="group"
          aria-label="Leaderboard metric"
          style={{
            display: "inline-flex",
            border: `1px solid ${colors.rule}`,
            backgroundColor: colors.panel,
          }}
        >
          {METRICS.map((m, i) => (
            <button
              key={m}
              type="button"
              aria-pressed={metricKey === m}
              onClick={() => setMetricKey(m)}
              style={{
                ...toggleButtonStyle(metricKey === m),
                borderLeft: i > 0 ? `1px solid ${colors.rule}` : "none",
              }}
            >
              {MODEL_STANDOUTS[m].label}
            </button>
          ))}
        </div>
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
                  style={{
                    fontWeight: typography.weights.semibold,
                    color: colors.ink,
                    textDecoration: "none",
                  }}
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
