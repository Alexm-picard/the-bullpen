/**
 * <PostPredictionPanel> - the RETROSPECTIVE post-pitch champion scorecard (F2.1c, decision [177]).
 *
 * For each completed pitch the pitch_outcome_post CHAMPION actually scored, it shows the model's
 * LOGGED call vs what really happened - never a live/next-pitch prediction ([154]/ADR-0011 hold; the
 * next-pitch column on <LivePitchBoard> stays PRE-only). Honest by construction: it reads the
 * predictions the system already made, joined to the realized outcome. Broadcast-identity styling,
 * reusing the pitch board's outcome chips + tokens - no hex codes.
 */

import type { PostPredictionRow } from "../../api/games";
import { colors, typography } from "../../design/broadcast";
import { outcomeChip } from "./outcome-chip";

export type PostPredictionPanelProps = {
  /** Chronological, as the endpoint returns them. */
  rows: PostPredictionRow[];
  /** True when the game has more post predictions than the fetched window. */
  hasNext?: boolean;
};

/**
 * The champion version these holdout numbers were measured on (PR-210 evidence). Pinned so the
 * accuracy label never gets paired with a different served version by accident - promotion is
 * human-gated ([177]), so a version past this must refresh the figure deliberately.
 */
const HOLDOUT_VERSION = "v1";
/** Verified 2026-holdout accuracy the gate produced (PR-210 evidence) - the honest label [177] asks for. */
const HOLDOUT_ACCURACY = "59.1% top-1 · 80.8% top-2 (verified 2026 holdout)";

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

/** The most-probable class + its probability, or null when the distribution is missing. */
function topClass(
  classes: Record<string, number> | null,
): { name: string; p: number } | null {
  if (!classes) return null;
  let name: string | null = null;
  let p = -1;
  for (const [k, v] of Object.entries(classes)) {
    if (v > p) {
      name = k;
      p = v;
    }
  }
  return name == null ? null : { name, p };
}

const dash = <span style={{ color: colors.textMuted }}>-</span>;

/** The logged winner, or the argmax fallback if the winner field is somehow absent. */
function winnerOf(row: PostPredictionRow): string | null {
  return row.postWinner ?? topClass(row.postClasses)?.name ?? null;
}

function championCall(row: PostPredictionRow) {
  const winner = winnerOf(row);
  if (winner == null) return dash;
  // Read the WINNER's own probability, not the argmax's. They are equal by construction -
  // LivePitchPredictor logs winner = argmax(probabilities) - but reading it directly keeps the
  // shown % honest even if that ever decouples, and shows no % rather than a wrong one on a miss.
  const p = row.postClasses?.[winner];
  const pct = p != null ? ` ${Math.round(p * 100)}%` : "";
  return (
    <span>
      {winner.replace(/_/g, " ")}
      <span style={{ color: colors.textMuted }}>{pct}</span>
    </span>
  );
}

/** Did the champion's top-1 call match the realized outcome? Only when BOTH are present. */
function result(row: PostPredictionRow) {
  const winner = winnerOf(row);
  if (winner == null || row.realizedOutcome == null) {
    return (
      <span
        role="img"
        aria-label="not scored yet"
        style={{ color: colors.textMuted }}
      >
        -
      </span>
    );
  }
  const hit = winner === row.realizedOutcome;
  return (
    <span
      role="img"
      aria-label={hit ? "call matched" : "call missed"}
      style={{ color: hit ? colors.text : colors.goldInk }}
    >
      {hit ? "✓" : "✗"}
    </span>
  );
}

export function PostPredictionPanel({
  rows,
  hasNext = false,
}: PostPredictionPanelProps) {
  const version =
    rows.find((r) => r.modelVersion != null)?.modelVersion ?? null;
  // Fall back to the pinned champion version (never assert one the data didn't carry), and only
  // pair the holdout numbers with the version they were actually measured on ([177] promotion is
  // human-gated, so a newer served version would refresh HOLDOUT_ACCURACY deliberately).
  const shownVersion = version ?? HOLDOUT_VERSION;
  const accuracy =
    version == null || version === HOLDOUT_VERSION
      ? ` · ${HOLDOUT_ACCURACY}`
      : "";

  return (
    <div
      style={{
        backgroundColor: colors.panel,
        border: `1px solid ${colors.rule}`,
      }}
    >
      <div style={{ padding: "12px 12px 8px" }}>
        <div
          style={{
            fontFamily: typography.fonts.display,
            fontStyle: "italic",
            fontWeight: typography.weights.bold,
            fontSize: 15,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            color: colors.text,
          }}
        >
          Post-Pitch Champion · Retrospective
        </div>
        <div
          style={{
            fontFamily: typography.fonts.body,
            fontSize: 12.5,
            color: colors.textMuted,
            marginTop: 3,
          }}
        >
          pitch_outcome_post {shownVersion}
          {accuracy} · the model's logged call on each thrown pitch vs what
          actually happened (not a prediction of the next pitch).
        </div>
      </div>

      {rows.length === 0 ? (
        <div
          role="status"
          style={{
            padding: 24,
            textAlign: "center",
            fontFamily: typography.fonts.body,
            fontSize: 14,
            color: colors.textMuted,
          }}
        >
          No post-pitch predictions logged for this game yet.
        </div>
      ) : (
        <div style={{ overflowX: "auto" }}>
          <table
            style={{
              width: "100%",
              borderCollapse: "collapse",
              tableLayout: "fixed",
            }}
          >
            <thead>
              <tr>
                <th scope="col" style={{ ...headCell, width: "18%" }}>
                  Pitch
                </th>
                <th scope="col" style={headCell}>
                  Champion call
                </th>
                <th scope="col" style={headCell}>
                  Actual
                </th>
                <th
                  scope="col"
                  style={{ ...headCell, width: "12%", textAlign: "center" }}
                >
                  Hit
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.atBatIndex}-${row.pitchNumber}`}>
                  <td style={cell}>
                    {row.inning > 0 ? (
                      <>
                        <span style={{ color: colors.textMuted }}>inn </span>
                        {row.inning}
                      </>
                    ) : (
                      dash
                    )}
                    <span style={{ color: colors.textMuted }}>
                      {" "}
                      #{row.atBatIndex}.{row.pitchNumber}
                    </span>
                  </td>
                  <td style={cell}>{championCall(row)}</td>
                  <td style={cell}>
                    {row.realizedOutcome == null
                      ? dash
                      : outcomeChip(row.realizedOutcome)}
                  </td>
                  <td style={{ ...cell, textAlign: "center" }}>
                    {result(row)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasNext && (
        <div
          style={{
            padding: "8px 12px",
            fontFamily: typography.fonts.body,
            fontSize: 12,
            color: colors.textMuted,
          }}
        >
          Showing the first {rows.length}; more of the game's predictions exist.
        </div>
      )}
    </div>
  );
}
