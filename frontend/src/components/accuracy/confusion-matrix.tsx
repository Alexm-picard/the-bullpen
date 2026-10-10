/**
 * <ConfusionMatrix> - an NxN confusion grid (true rows x called cols) in the [195] editorial
 * agate (decision [199] re-skin of the Phase 3 PR-gamma grid).
 *
 * A real <table> with row/column headers and a caption, so a screen reader can walk it cell by
 * cell (the broadcast version was a role="img" table, which hid every count from assistive
 * tech). No colour ramp: every count is printed, and the agreeing cells (true == called) carry
 * INK WEIGHT, never colour - the [180] convention the game page uses.
 *
 * Built-in empty path: an empty or malformed matrix renders an explanatory note rather than an
 * empty grid (never fabricated zeros presented as data).
 */
export type ConfusionMatrixProps = {
  /** Class labels in matrix order (used for both axes). */
  labels: string[];
  /** NxN integer count matrix; matrix[trueIdx][predIdx]. */
  matrix: number[][];
  /** Caption rendered under the grid. */
  caption?: string;
};

const COUNT = new Intl.NumberFormat("en-US");

/**
 * Agate abbreviations for the batted-ball outcome codes the backfill ships, so five count
 * columns fit a phone. Unknown labels pass through unchanged; the full name rides on `title`
 * and the header's `abbr` so assistive tech reads the class, not the code.
 */
const SHORT: Record<string, string> = {
  out: "Out",
  single: "1B",
  double: "2B",
  triple: "3B",
  home_run: "HR",
};
const short = (label: string) => SHORT[label] ?? label;
const full = (label: string) => label.replace(/_/g, " ");

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
      <p className="ed-note">Confusion matrix unavailable: no scored events.</p>
    );
  }
  return (
    <div className="acc-cm-wrap">
      <table className="acc-cm">
        {caption ? <caption>{caption}</caption> : null}
        <thead>
          <tr>
            <th scope="col">True / called</th>
            {labels.map((label) => (
              <th
                scope="col"
                key={`c-${label}`}
                abbr={full(label)}
                title={full(label)}
              >
                {short(label)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {matrix.map((row, trueIdx) => (
            <tr key={`r-${labels[trueIdx]}`}>
              <th
                scope="row"
                abbr={full(labels[trueIdx] ?? "")}
                title={full(labels[trueIdx] ?? "")}
              >
                {short(labels[trueIdx] ?? "")}
              </th>
              {row.map((count, predIdx) => {
                const safe = Number.isFinite(count) ? count : 0;
                return (
                  <td
                    key={`cell-${trueIdx}-${predIdx}`}
                    className={trueIdx === predIdx ? "acc-cm__hit" : undefined}
                  >
                    {COUNT.format(safe)}
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
