/**
 * The runs-by-inning line score (SPEC-game §6). Runs only, with the honesty caption inline; an
 * inning that cannot be worked out reads as a dash, not a 0; innings not yet played are blank.
 * Extra innings widen the table, which scrolls sideways inside its own wrapper on a phone.
 */
import { VisuallyHidden } from "@mantine/core";

import { LINE_SCORE_CAPTION } from "../../data/game-claims";

import type { LineScore as LineScoreData } from "./game-account";

function cell(
  runs: (number | null)[],
  i: number,
  played: number,
): string | null {
  if (i >= played) return null;
  const v = runs[i];
  return v == null ? "–" : String(v);
}

export function LineScore({
  data,
  awayTeam,
  homeTeam,
}: {
  data: LineScoreData;
  awayTeam: string;
  homeTeam: string;
}) {
  const cols = Array.from({ length: data.innings }, (_, i) => i);
  const row = (team: string, runs: (number | null)[], total: number) => (
    <tr>
      <th scope="row">{team}</th>
      {cols.map((i) => {
        const v = cell(runs, i, data.played);
        return (
          <td key={i}>
            {v == null ? (
              <VisuallyHidden>not played</VisuallyHidden>
            ) : v === "–" ? (
              <>
                <span aria-hidden="true">{v}</span>
                <VisuallyHidden>not logged</VisuallyHidden>
              </>
            ) : (
              v
            )}
          </td>
        );
      })}
      <td className="ed-line__r">{total}</td>
    </tr>
  );
  return (
    <div className="ed-line-wrap">
      <table className="ed-line">
        <caption>{LINE_SCORE_CAPTION}</caption>
        <thead>
          <tr>
            <th scope="col">
              <VisuallyHidden>Team</VisuallyHidden>
            </th>
            {cols.map((i) => (
              <th
                key={i}
                scope="col"
                aria-current={data.currentInning === i + 1 ? "true" : undefined}
              >
                {i + 1}
              </th>
            ))}
            <th scope="col" className="ed-line__r">
              R
            </th>
          </tr>
        </thead>
        <tbody>
          {row(awayTeam, data.away, data.awayTotal)}
          {row(homeTeam, data.home, data.homeTotal)}
        </tbody>
      </table>
    </div>
  );
}
