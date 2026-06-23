/**
 * <BattedBallsView> - a batter's in-play balls across all seasons (Phase 2.2/2.3), filterable by
 * hit type, by an "HRs only" toggle (the all-home-runs view), and by a game-date range. Fed by
 * useBatterBattedBalls (GET /v1/players/:id/batted-balls). Broadcast tokens only; the table prints
 * every value (EV / LA / distance, or an em-dash when untracked).
 */
import { Checkbox, SegmentedControl } from "@mantine/core";
import { useState } from "react";

import { useBatterBattedBalls } from "../../api/players";
import { colors, typography } from "../../design/broadcast";

const HIT_TYPES = [
  { label: "All", value: "all" },
  { label: "GB", value: "ground_ball" },
  { label: "LD", value: "line_drive" },
  { label: "FB", value: "fly_ball" },
  { label: "PU", value: "popup" },
];

function titleCase(s: string): string {
  if (!s) return "-";
  const t = s.replace(/_/g, " ");
  return t.charAt(0).toUpperCase() + t.slice(1);
}

function num(v: number | null, digits = 1): string {
  return v == null ? "-" : v.toFixed(digits);
}

const controlLabelStyle: React.CSSProperties = {
  display: "block",
  marginBottom: 4,
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  letterSpacing: "0.04em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

const dateInputStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  padding: "4px 6px",
  border: `1px solid ${colors.rule}`,
  borderRadius: 2,
  backgroundColor: colors.panel,
  color: colors.ink,
};

const mutedStyle: React.CSSProperties = {
  margin: "0 0 8px",
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
};

const errorStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontWeight: typography.weights.semibold,
  color: colors.goldInk,
};

const rowStyle: React.CSSProperties = {
  display: "grid",
  gridTemplateColumns: "104px 1fr 96px 56px 52px 64px",
  alignItems: "center",
  gap: 8,
  padding: "5px 8px",
  borderBottom: `1px solid ${colors.rule}`,
};

const headCellStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 10,
  letterSpacing: "0.06em",
  textTransform: "uppercase",
  color: colors.textMuted,
};

const cellStyle: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.ink,
  overflow: "hidden",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
};

const numCellStyle: React.CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
  fontSize: 12,
  color: colors.ink,
  textAlign: "right",
};

function avgExitVelo(rows: { launchSpeedMph: number | null }[]): number | null {
  const evs = rows
    .map((r) => r.launchSpeedMph)
    .filter((v): v is number => v != null);
  if (evs.length === 0) return null;
  return evs.reduce((a, b) => a + b, 0) / evs.length;
}

export function BattedBallsView({ playerId }: { playerId: number }) {
  const [hitType, setHitType] = useState("all");
  const [hrOnly, setHrOnly] = useState(false);
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");

  const query = useBatterBattedBalls(playerId, {
    bbType: hitType === "all" ? undefined : hitType,
    event: hrOnly ? "home_run" : undefined,
    from: from || undefined,
    to: to || undefined,
  });
  const rows = query.data ?? [];
  const avgEv = avgExitVelo(rows);

  return (
    <div>
      <div
        style={{
          display: "flex",
          gap: 20,
          alignItems: "flex-end",
          flexWrap: "wrap",
          marginBottom: 14,
        }}
      >
        <div>
          <span style={controlLabelStyle}>Hit type</span>
          <SegmentedControl
            size="xs"
            value={hitType}
            onChange={setHitType}
            data={HIT_TYPES}
          />
        </div>
        <div>
          <span style={controlLabelStyle}>Result</span>
          <Checkbox
            size="xs"
            checked={hrOnly}
            onChange={(e) => setHrOnly(e.currentTarget.checked)}
            label="HRs only"
          />
        </div>
        <div>
          <span style={controlLabelStyle}>From</span>
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            style={dateInputStyle}
            aria-label="From date"
          />
        </div>
        <div>
          <span style={controlLabelStyle}>To</span>
          <input
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            style={dateInputStyle}
            aria-label="To date"
          />
        </div>
      </div>

      {query.isError ? (
        <p style={errorStyle}>
          Could not load this batter&rsquo;s batted balls.
        </p>
      ) : query.isLoading ? (
        <p style={mutedStyle}>Loading batted balls&hellip;</p>
      ) : rows.length === 0 ? (
        <p style={mutedStyle}>No in-play balls match these filters.</p>
      ) : (
        <>
          <p style={mutedStyle}>
            {rows.length} batted balls
            {avgEv != null ? ` · avg exit velo ${avgEv.toFixed(1)} mph` : ""}
          </p>
          <div role="table" aria-label="Batted balls">
            <div role="row" style={rowStyle}>
              <span style={headCellStyle}>Date</span>
              <span style={headCellStyle}>Result</span>
              <span style={headCellStyle}>Type</span>
              <span style={{ ...headCellStyle, textAlign: "right" }}>EV</span>
              <span style={{ ...headCellStyle, textAlign: "right" }}>LA</span>
              <span style={{ ...headCellStyle, textAlign: "right" }}>Dist</span>
            </div>
            {rows.map((r, i) => (
              <div
                key={`${r.gameDate}-${i}`}
                role="row"
                style={{
                  ...rowStyle,
                  backgroundColor:
                    i % 2 === 0 ? colors.panel : colors.fieldSubtle,
                }}
              >
                <span
                  style={{
                    ...cellStyle,
                    fontFamily: typography.fonts.mono,
                    fontSize: 12,
                  }}
                >
                  {r.gameDate}
                </span>
                <span style={cellStyle}>{titleCase(r.events)}</span>
                <span style={cellStyle}>{titleCase(r.bbType)}</span>
                <span style={numCellStyle}>{num(r.launchSpeedMph)}</span>
                <span style={numCellStyle}>{num(r.launchAngleDeg)}</span>
                <span style={numCellStyle}>{num(r.hitDistanceFt, 0)}</span>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
