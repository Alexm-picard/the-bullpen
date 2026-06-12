/**
 * <LivePitchBoard> - the broadcast-identity pitch log (redesign PR-2, decision
 * [160]). Replaces the paper-era <LivePitchLog>/<PitchCard> stack on migrated
 * screens with a dense telecast board: one mono row per pitch, newest first,
 * a gold tick on the just-thrown pitch, outcome chips filled from the
 * categorical palette (fills, never colored text), and an honest prediction
 * column that reads "n/a" while live runs champion-less ([154]).
 *
 * Broadcast energy in the FRAME, analytical restraint in the CELLS: the board
 * itself is calm - the chrome around it (LowerThird, ticker) carries the TV.
 */

import type { LivePitchRow } from "../../api/games";
import { colors, typography } from "../../design/broadcast";

export type LivePitchBoardProps = {
  /** Newest-first, as `useLivePitches` returns them. */
  pitches: LivePitchRow[];
  /** Rows rendered (newest N). */
  limit?: number;
};

/** Outcome chip fills - token-derived, text always ink-on-light or white-on-fill. */
const OUTCOME_FILL: Record<string, string> = {
  ball: colors.fieldSubtle,
  called_strike: colors.viz.categorical[2],
  swinging_strike: colors.viz.categorical[0],
  foul: colors.steel,
  in_play: colors.viz.categorical[1],
  hit_by_pitch: colors.viz.categorical[4],
};

const OUTCOME_TEXT_ON_FILL: Record<string, string> = {
  ball: colors.text,
  called_strike: colors.textOnChrome,
  swinging_strike: colors.textOnChrome,
  foul: colors.textOnChrome,
  in_play: colors.ink,
  hit_by_pitch: colors.textOnChrome,
};

const cell: React.CSSProperties = {
  padding: "7px 10px",
  borderBottom: `1px solid ${colors.rule}`,
  fontFamily: typography.fonts.mono,
  fontSize: 13,
  fontFeatureSettings: '"tnum" 1',
  color: colors.text,
  whiteSpace: "nowrap",
  verticalAlign: "middle",
};

const headCell: React.CSSProperties = {
  ...cell,
  fontFamily: typography.fonts.display,
  fontStyle: "italic",
  fontWeight: typography.weights.bold,
  fontSize: 13,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: colors.textMuted,
  borderBottom: `2px solid ${colors.chrome}`,
  textAlign: "left",
};

function outcomeChip(description: string) {
  const fill = OUTCOME_FILL[description] ?? colors.fieldSubtle;
  const text = OUTCOME_TEXT_ON_FILL[description] ?? colors.text;
  return (
    <span
      style={{
        display: "inline-block",
        padding: "2px 8px",
        backgroundColor: fill,
        color: text,
        fontFamily: typography.fonts.display,
        fontWeight: typography.weights.semibold,
        fontSize: 12,
        letterSpacing: "0.05em",
        textTransform: "uppercase",
      }}
    >
      {description.replace(/_/g, " ")}
    </span>
  );
}

function predictionRead(p: LivePitchRow) {
  if (p.predictedWinner == null) {
    return (
      <span
        style={{ color: colors.textMuted }}
        title="no pitch model promoted ([154])"
      >
        n/a
      </span>
    );
  }
  const agreed = p.predictedWinner === p.description;
  return (
    <span style={{ color: agreed ? colors.text : colors.goldInk }}>
      {agreed ? "✓" : "✗"} {p.predictedWinner.replace(/_/g, " ")}
    </span>
  );
}

export function LivePitchBoard({ pitches, limit = 50 }: LivePitchBoardProps) {
  if (pitches.length === 0) {
    return (
      <div
        role="status"
        style={{
          backgroundColor: colors.panel,
          border: `1px solid ${colors.rule}`,
          padding: 28,
          textAlign: "center",
          fontFamily: typography.fonts.body,
          fontSize: 14,
          color: colors.textMuted,
        }}
      >
        Waiting for the first pitch…
      </div>
    );
  }

  const rows = pitches.slice(0, limit);
  return (
    <div
      style={{
        overflowX: "auto",
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
      }}
    >
      <table style={{ borderCollapse: "collapse", width: "100%" }}>
        <thead>
          <tr>
            <th scope="col" style={headCell} aria-label="just thrown" />
            <th scope="col" style={headCell}>
              Inn
            </th>
            <th scope="col" style={headCell}>
              Cnt
            </th>
            <th scope="col" style={headCell}>
              Pitch
            </th>
            <th scope="col" style={{ ...headCell, textAlign: "right" }}>
              Velo
            </th>
            <th scope="col" style={headCell}>
              Result
            </th>
            <th scope="col" style={headCell}>
              Model
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((p, i) => (
            <tr key={p.cursor}>
              <td style={{ ...cell, padding: 0, width: 4 }}>
                {i === 0 ? (
                  <span
                    aria-hidden="true"
                    data-testid="just-thrown-tick"
                    style={{
                      display: "block",
                      width: 4,
                      height: 30,
                      backgroundColor: colors.gold,
                    }}
                  />
                ) : null}
              </td>
              <td style={cell}>{p.inning}</td>
              <td style={cell}>
                {p.balls}-{p.strikes}
              </td>
              <td style={{ ...cell, fontWeight: typography.weights.bold }}>
                {p.pitchType || "—"}
              </td>
              <td style={{ ...cell, textAlign: "right" }}>
                {p.releaseSpeedMph != null ? p.releaseSpeedMph.toFixed(1) : "—"}
              </td>
              <td style={cell}>{outcomeChip(p.description)}</td>
              <td style={cell}>{predictionRead(p)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
