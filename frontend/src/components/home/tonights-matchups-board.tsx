/**
 * <TonightsMatchupsBoard> - the /home slate on the broadcast identity
 * (Phase 4b). Reads the live {@link BoardRowView}s from GET /v1/matchups/today:
 * six columns - matchup (team-color marks) / first pitch / the two lean-driven
 * people / the lean badge / battle score / open. The people and the lean are
 * already chosen by the backend classifier; the board only renders them.
 *
 * Same custom-<table> rationale as before: the OPEN cell must be a real <a>.
 */

import { Link } from "react-router-dom";

import type { BoardRowView } from "../../api/matchups-view";
import { colors, typography } from "../../design/broadcast";
import { teamColor } from "../../design/teamColors";

export type TonightsMatchupsBoardProps = {
  rows: BoardRowView[];
  caption?: string;
};

const border = `1px solid ${colors.rule}`;

const headCell: React.CSSProperties = {
  backgroundColor: colors.chrome,
  color: colors.textOnChrome,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 13,
  letterSpacing: "0.06em",
  textTransform: "uppercase",
  padding: "8px 12px",
  borderRight: `1px solid ${colors.chromeEdge}`,
  whiteSpace: "nowrap",
  textAlign: "left",
};

const cell: React.CSSProperties = {
  fontFamily: typography.fonts.body,
  fontSize: 14,
  color: colors.text,
  padding: "10px 12px",
  borderBottom: border,
  borderRight: border,
  verticalAlign: "middle",
};

const monoCell: React.CSSProperties = {
  ...cell,
  fontFamily: typography.fonts.mono,
  fontFeatureSettings: '"tnum" 1',
};

const leanBadge: React.CSSProperties = {
  display: "inline-block",
  backgroundColor: colors.chromeDeep,
  color: colors.textOnChrome,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 11,
  letterSpacing: "0.06em",
  textTransform: "uppercase",
  padding: "2px 7px",
  whiteSpace: "nowrap",
};

function TeamMark({ team }: { team: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
      <span
        aria-hidden="true"
        style={{
          width: 4,
          height: 16,
          backgroundColor: teamColor(team),
        }}
      />
      <span
        style={{
          fontFamily: typography.fonts.display,
          fontStyle: "italic",
          fontWeight: typography.weights.bold,
          fontSize: 16,
          letterSpacing: "0.03em",
          textTransform: "uppercase",
          color: colors.ink,
        }}
      >
        {team}
      </span>
    </span>
  );
}

function Person({ name, role }: { name: string; role: string }) {
  return (
    <>
      <span style={{ fontWeight: typography.weights.semibold }}>{name}</span>{" "}
      <span
        style={{
          fontFamily: typography.fonts.mono,
          fontSize: 12,
          color: colors.textMuted,
        }}
      >
        · {role}
      </span>
    </>
  );
}

export function TonightsMatchupsBoard({
  rows,
  caption,
}: TonightsMatchupsBoardProps) {
  return (
    <div
      style={{
        overflowX: "auto",
        border,
        backgroundColor: colors.panel,
      }}
    >
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        {caption && (
          <caption
            style={{
              captionSide: "top",
              textAlign: "left",
              fontFamily: typography.fonts.mono,
              fontSize: 11,
              color: colors.textMuted,
              padding: "8px 12px 4px",
              letterSpacing: "0.04em",
              textTransform: "uppercase",
            }}
          >
            {caption}
          </caption>
        )}
        <thead>
          <tr>
            <th scope="col" style={headCell}>
              Matchup
            </th>
            <th scope="col" style={headCell}>
              First Pitch
            </th>
            <th scope="col" style={headCell}>
              Featured
            </th>
            <th scope="col" style={headCell}>
              Lean
            </th>
            <th scope="col" style={{ ...headCell, textAlign: "right" }}>
              Battle
            </th>
            <th
              scope="col"
              style={{ ...headCell, textAlign: "right", borderRight: "none" }}
            >
              Open
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => (
            <tr key={m.gameId}>
              <th
                scope="row"
                style={{ ...cell, whiteSpace: "nowrap", textAlign: "left" }}
              >
                <TeamMark team={m.awayTeam} />
                <span
                  aria-hidden="true"
                  style={{
                    color: colors.steel,
                    margin: "0 8px",
                    fontSize: 10,
                  }}
                >
                  ◆
                </span>
                <TeamMark team={m.homeTeam} />
              </th>
              <td style={monoCell}>{m.firstPitchEt}</td>
              <td style={cell}>
                <Person name={m.away.name} role={m.away.role} />
                <span style={{ color: colors.textMuted, margin: "0 6px" }}>
                  vs
                </span>
                <Person name={m.home.name} role={m.home.role} />
              </td>
              <td style={cell}>
                <span style={leanBadge}>{m.leanLabel}</span>
              </td>
              <td
                style={{
                  ...monoCell,
                  textAlign: "right",
                  fontWeight: typography.weights.bold,
                }}
                aria-label={`Battle score ${m.battleScore.toFixed(1)}`}
              >
                {m.battleScore.toFixed(1)}
              </td>
              <td style={{ ...cell, textAlign: "right", borderRight: "none" }}>
                <Link
                  to={`/games/${m.gameId}`}
                  style={{
                    fontFamily: typography.fonts.display,
                    fontStyle: "italic",
                    fontWeight: typography.weights.bold,
                    fontSize: 13,
                    letterSpacing: "0.08em",
                    textTransform: "uppercase",
                    color: colors.goldInk,
                    textDecoration: "none",
                    whiteSpace: "nowrap",
                  }}
                  aria-label={`Open game for ${m.awayTeam} at ${m.homeTeam}`}
                >
                  Open &rarr;
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
