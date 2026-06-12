/**
 * <TonightsMatchupsBoard> - the /home slate on the broadcast identity
 * (redesign PR-4, decision [160]). Replaces <TonightsMatchupsTable>: same six
 * columns (matchup / time / starters / EDGE / top read / open), now with
 * team-color bars in the matchup cell (fills only) and the EDGE heat tint
 * drawn from the BROADCAST condFormat ramp via cellColorWith.
 *
 * Same custom-<table> rationale as before: the OPEN cell must be a real <a>.
 * The a11y rule holds - the EDGE value text always renders on the tint.
 */

import { Link } from "react-router-dom";

import type { TonightMatchup } from "../../data/home-fixtures";
import { EDGE_METRIC } from "../../data/home-fixtures";
import { colors, typography } from "../../design/broadcast";
import { cellColorWith, rampFrom } from "../../design/cellColor";
import { teamColor } from "../../design/teamColors";

export type TonightsMatchupsBoardProps = {
  matchups: TonightMatchup[];
  caption?: string;
};

const BROADCAST_RAMP = rampFrom(colors.condFormat);

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

function formatEdge(edge: number): string {
  const sign = edge > 0 ? "+" : edge < 0 ? "" : " ";
  return `${sign}${edge.toFixed(1)}`;
}

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

export function TonightsMatchupsBoard({
  matchups,
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
              Time
            </th>
            <th scope="col" style={headCell}>
              Starters
            </th>
            <th scope="col" style={{ ...headCell, textAlign: "right" }}>
              Edge
            </th>
            <th scope="col" style={headCell}>
              Top Read
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
          {matchups.map((m) => {
            const edgeBg = cellColorWith(BROADCAST_RAMP, m.edge, EDGE_METRIC);
            return (
              <tr key={m.id}>
                <th
                  scope="row"
                  style={{ ...cell, whiteSpace: "nowrap", textAlign: "left" }}
                >
                  <TeamMark team={m.away} />
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
                  <TeamMark team={m.home} />
                </th>
                <td style={monoCell}>{m.timeEt}</td>
                <td style={cell}>
                  <span style={{ fontWeight: typography.weights.semibold }}>
                    {m.awayStarter.name}
                  </span>{" "}
                  <span
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: 12,
                      color: colors.textMuted,
                    }}
                  >
                    · {m.awayStarter.hand}
                  </span>
                  <span style={{ color: colors.textMuted, margin: "0 6px" }}>
                    /
                  </span>
                  <span style={{ fontWeight: typography.weights.semibold }}>
                    {m.homeStarter.name}
                  </span>{" "}
                  <span
                    style={{
                      fontFamily: typography.fonts.mono,
                      fontSize: 12,
                      color: colors.textMuted,
                    }}
                  >
                    · {m.homeStarter.hand}
                  </span>
                </td>
                <td
                  style={{
                    ...monoCell,
                    backgroundColor: edgeBg,
                    textAlign: "right",
                    fontWeight: typography.weights.bold,
                  }}
                  aria-label={`Edge ${formatEdge(m.edge)} runs`}
                >
                  {formatEdge(m.edge)}
                </td>
                <td style={cell}>{m.topRead}</td>
                <td
                  style={{ ...cell, textAlign: "right", borderRight: "none" }}
                >
                  <Link
                    to={`/players/${m.batterId}`}
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
                    aria-label={`Open report for ${m.away} at ${m.home}`}
                  >
                    Open &rarr;
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
