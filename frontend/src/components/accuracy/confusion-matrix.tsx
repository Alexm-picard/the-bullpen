/**
 * <ConfusionMatrix> - a pure-HTML NxN confusion grid (true rows x predicted
 * cols), broadcast-token styled (Phase 3 PR-gamma).
 *
 * Cell background intensity is driven by the ROW-normalized fraction (each true
 * class normalizes against its own row total, so a dominant diagonal reads even
 * when class supports differ by orders of magnitude). The integer count is
 * always rendered as mono text ON the cell - color is NEVER the sole carrier of
 * meaning (the a11y rule from design.md section 8). The intensity ramp is the
 * 4-stop {@code colors.spray} green sequential token ramp; the text color flips
 * to a light token on the darkest stop so the count stays legible.
 *
 * Accessibility: the grid is a {@code role="img"} with an aria-label summarizing
 * the matrix; a visible {@code <caption>}-style title sits above it; every cell
 * carries a {@code <title>} ("true X, predicted Y: N") for hover/SR detail.
 *
 * Built-in empty path: an empty or malformed matrix renders an explanatory
 * panel rather than an empty grid (never fabricated zeros presented as data).
 */

import { colors, radii, typography } from "../../design/broadcast";

export type ConfusionMatrixProps = {
  /** Class labels in matrix order (used for both axes). */
  labels: string[];
  /** NxN integer count matrix; matrix[trueIdx][predIdx]. */
  matrix: number[][];
  /** Optional caption rendered above the grid. */
  caption?: string;
};

const SPRAY = colors.spray; // 4-stop green sequential ramp (all tokens)

/** Map a row-normalized fraction in [0, 1] to a spray ramp stop. */
function fractionToStop(fraction: number): { bg: string; darkest: boolean } {
  if (!Number.isFinite(fraction) || fraction <= 0) {
    return { bg: colors.panel, darkest: false };
  }
  if (fraction < 0.25) return { bg: SPRAY[0], darkest: false };
  if (fraction < 0.5) return { bg: SPRAY[1], darkest: false };
  if (fraction < 0.75) return { bg: SPRAY[2], darkest: false };
  return { bg: SPRAY[3], darkest: true };
}

function isWellFormed(labels: string[], matrix: number[][]): boolean {
  if (labels.length === 0 || matrix.length === 0) return false;
  if (matrix.length !== labels.length) return false;
  return matrix.every(
    (row) => Array.isArray(row) && row.length === labels.length,
  );
}

export function ConfusionMatrix({
  labels,
  matrix,
  caption,
}: ConfusionMatrixProps) {
  if (!isWellFormed(labels, matrix)) {
    return (
      <div
        role="img"
        aria-label="Confusion matrix unavailable - no data"
        style={{
          border: `1px solid ${colors.rule}`,
          borderRadius: radii.sm,
          backgroundColor: colors.panel,
          padding: 16,
          fontFamily: typography.fonts.body,
          fontSize: 14,
          color: colors.textMuted,
        }}
      >
        Confusion matrix unavailable - no scored events.
      </div>
    );
  }

  const total = matrix.reduce(
    (acc, row) =>
      acc + row.reduce((s, v) => s + (Number.isFinite(v) ? v : 0), 0),
    0,
  );
  const rowTotals = matrix.map((row) =>
    row.reduce((s, v) => s + (Number.isFinite(v) ? v : 0), 0),
  );

  const headerCellStyle: React.CSSProperties = {
    backgroundColor: colors.chrome,
    color: colors.textOnChrome,
    fontFamily: typography.fonts.display,
    fontStyle: "italic",
    fontWeight: typography.weights.bold,
    fontSize: 13,
    letterSpacing: "0.04em",
    textTransform: "uppercase",
    padding: "6px 10px",
    borderBottom: `1px solid ${colors.rule}`,
    borderRight: `1px solid ${colors.rule}`,
    textAlign: "center",
    whiteSpace: "nowrap",
  };

  const cornerStyle: React.CSSProperties = {
    ...headerCellStyle,
    backgroundColor: colors.chromeDeep,
    fontSize: 11,
  };

  const rowLabelStyle: React.CSSProperties = {
    backgroundColor: colors.fieldSubtle,
    color: colors.ink,
    fontFamily: typography.fonts.body,
    fontWeight: typography.weights.semibold,
    fontSize: 13,
    padding: "6px 10px",
    borderBottom: `1px solid ${colors.rule}`,
    borderRight: `1px solid ${colors.rule}`,
    whiteSpace: "nowrap",
  };

  return (
    <div
      style={{
        overflowX: "auto",
        border: `1px solid ${colors.rule}`,
        borderRadius: radii.sm,
        backgroundColor: colors.panel,
      }}
    >
      <table
        role="img"
        aria-label={`Confusion matrix of ${total} scored events: true classes in rows, predicted classes in columns, across ${labels.join(", ")}.`}
        style={{
          borderCollapse: "collapse",
          width: "100%",
          backgroundColor: colors.panel,
        }}
      >
        {caption && (
          <caption
            style={{
              captionSide: "top",
              textAlign: "left",
              fontFamily: typography.fonts.body,
              fontSize: 12,
              fontWeight: typography.weights.semibold,
              color: colors.textMuted,
              padding: "8px 10px 4px",
              letterSpacing: "0.04em",
              textTransform: "uppercase",
            }}
          >
            {caption}
          </caption>
        )}
        <thead>
          <tr>
            <th scope="col" style={cornerStyle}>
              true \ pred
            </th>
            {labels.map((label) => (
              <th key={`col-${label}`} scope="col" style={headerCellStyle}>
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {matrix.map((row, trueIdx) => (
            <tr key={`row-${labels[trueIdx]}`}>
              <th scope="row" style={rowLabelStyle}>
                {labels[trueIdx]}
              </th>
              {row.map((count, predIdx) => {
                const rowTotal = rowTotals[trueIdx] ?? 0;
                const fraction = rowTotal > 0 ? count / rowTotal : 0;
                const { bg, darkest } = fractionToStop(fraction);
                const safeCount = Number.isFinite(count) ? count : 0;
                return (
                  <td
                    key={`cell-${trueIdx}-${predIdx}`}
                    title={`true ${labels[trueIdx]}, predicted ${labels[predIdx]}: ${safeCount}`}
                    style={{
                      backgroundColor: bg,
                      color: darkest ? colors.textOnChrome : colors.ink,
                      fontFamily: typography.fonts.mono,
                      fontSize: 13,
                      fontFeatureSettings: '"tnum" 1',
                      textAlign: "right",
                      padding: "6px 10px",
                      borderBottom: `1px solid ${colors.rule}`,
                      borderRight: `1px solid ${colors.rule}`,
                      fontWeight:
                        trueIdx === predIdx
                          ? typography.weights.bold
                          : typography.weights.regular,
                    }}
                  >
                    {safeCount.toLocaleString()}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
