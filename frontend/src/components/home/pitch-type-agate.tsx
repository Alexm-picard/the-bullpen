/**
 * PitchTypeAgate - the front page's "next pitch, by type" table ([195] editorial skin).
 *
 * Two honest modes, never blended:
 *  - "prior": the live `pitch_type_pre` distribution for the upcoming pitch (decision [194]'s
 *    worker-computed answer, read from GET /v1/games/{id}/live - home adds no inference call and
 *    no prediction_log rows).
 *  - "career": the pitcher's career usage before first pitch. Not a model output, and the caption
 *    says so.
 *
 * [183]: every row is styled identically - same classes, no conditional weight, colour, or bar
 * colour on any row, including the first. Rank order is the only emphasis, and the caption
 * disowns even that ("not a call"). The test asserts the uniformity, so adding emphasis to row 0
 * is a red test rather than a quiet regression.
 */
import { VisuallyHidden } from "@mantine/core";
import type { ReactNode } from "react";

import { agateShare, type RankedShare } from "../../lib/pitch-type-prior";

const PERCENT = new Intl.NumberFormat("en-US", {
  style: "percent",
  maximumFractionDigits: 0,
});

export type PitchTypeAgateProps = {
  /** Ranked rows; empty with `note` for the unavailable / waiting states. */
  rows: readonly RankedShare[];
  /** The table caption: what this distribution IS (and is not). */
  caption: string;
  /** Column header for the share column. */
  valueHeader: string;
  /** Mono provenance line under the table (model + version, or the data source). */
  provenance?: ReactNode;
  /** A sentence shown instead of rows (refusal reason, offline, waiting). */
  note?: ReactNode;
  /** Pre-sized static placeholder rows while the first answer is in flight. */
  busy?: boolean;
};

const SHELL_ROW_COUNT = 7;

export function PitchTypeAgate({
  rows,
  caption,
  valueHeader,
  provenance,
  note,
  busy = false,
}: PitchTypeAgateProps) {
  if (busy) {
    return (
      <div role="status" aria-busy="true">
        <VisuallyHidden>Loading the pitch-type table</VisuallyHidden>
        <div aria-hidden="true">
          {Array.from({ length: SHELL_ROW_COUNT }, (_, i) => (
            <div
              key={i}
              className="ed-shell"
              style={{ height: "1.25rem", margin: "0.6rem 0" }}
            />
          ))}
        </div>
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <div>
        <p className="ed-note" data-testid="pitch-type-agate-note">
          {note}
        </p>
        {provenance ? <p className="ed-prov">{provenance}</p> : null}
      </div>
    );
  }
  return (
    <div>
      <table className="ed-agate" aria-live="off">
        <caption>{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Pitch</th>
            <th scope="col" className="ed-barcell">
              <VisuallyHidden>Share bar</VisuallyHidden>
            </th>
            <th scope="col" className="ed-num">
              {valueHeader}
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            // Identical markup for every row - the [183] uniformity lives here.
            <tr key={r.code} className="ed-agate__row">
              <td>
                <span className="ed-agate__code" aria-hidden="true">
                  {r.code}
                </span>
                {r.label}
              </td>
              <td className="ed-barcell" aria-hidden="true">
                <div className="ed-track">
                  <span
                    className="ed-bar"
                    style={{ transform: `scaleX(${r.share})` }}
                  />
                </div>
              </td>
              <td className="ed-num">
                <span aria-hidden="true">{agateShare(r.share)}</span>
                <VisuallyHidden>{PERCENT.format(r.share)}</VisuallyHidden>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {note ? <p className="ed-note">{note}</p> : null}
      {provenance ? <p className="ed-prov">{provenance}</p> : null}
    </div>
  );
}
