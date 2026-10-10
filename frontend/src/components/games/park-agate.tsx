/**
 * ParkAgate - one real batted ball scored across every park, as a plain standings-style agate
 * (SPEC-game §13 answer 2: all 30 parks, no curate-your-parks reveal).
 *
 * Honesty rules carried over from the retired BattedBallExplorer mapping:
 *  - the all-parks endpoint reports P(HR) per park ONLY, so a park reads "HR" at/above
 *    HR_THRESHOLD and "in play" otherwise - never an invented single/double/out;
 *  - carry is the model's when the champion serves one, else the ball's own (estimated) distance,
 *    and it is labelled which;
 *  - the model reports no per-park uncertainty, so no band is drawn (the caption says so).
 * Rows sort by P(HR), highest first, ties by park id, so the order is stable across polls.
 */
import { VisuallyHidden } from "@mantine/core";

import { PARKS_CAPTION } from "../../data/game-claims";

import type { ParkLine } from "./game-account";

export function ParkAgate({
  lines,
  columns = 3,
  carryIsModel,
}: {
  lines: readonly ParkLine[];
  columns?: 2 | 3;
  /** True when the carry figures are the model's per-park carry; false = the ball's own distance. */
  carryIsModel: boolean;
}) {
  return (
    <div>
      <ul
        className={columns === 2 ? "ed-parks ed-parks--two" : "ed-parks"}
        aria-label="This batted ball in every park"
      >
        {lines.map((l) => (
          <li key={l.id} data-hr={l.hr ? "true" : "false"}>
            <span>
              {l.team}
              {l.here ? <span aria-hidden="true"> &dagger;</span> : null}
              <VisuallyHidden>
                {`, ${l.parkName}${l.here ? ", tonight's park" : ""}`}
              </VisuallyHidden>
            </span>
            <span>{l.carryFt != null ? `${l.carryFt} ft` : "—"}</span>
            <span>{l.hr ? "HR" : "in play"}</span>
          </li>
        ))}
      </ul>
      <p className="ed-note">
        {PARKS_CAPTION}{" "}
        {carryIsModel
          ? "Carry is the model's estimate per park."
          : "Carry is the ball's own tracked distance; this champion serves no per-park carry."}{" "}
        <span aria-hidden="true">&dagger;</span> tonight&rsquo;s park.
      </p>
    </div>
  );
}
