/**
 * ParkAgate - one batted ball scored across every park, as a plain standings-style agate
 * (SPEC-game §13 answer 2: all 30 parks, no curate-your-parks reveal). Shared by the game page
 * (one real ball) and /parks (the what-if ball, SPEC-parks), one visual system for both.
 *
 * Honesty rules carried over from the retired BattedBallExplorer mapping:
 *  - the all-parks endpoint reports P(HR) per park ONLY, so in the default `verdict` mode a park
 *    reads "HR" at/above HR_THRESHOLD and "in play" otherwise - never an invented single/double/out;
 *    the `prob` mode (/parks) prints the P(HR) itself;
 *  - carry is the model's when the champion serves one, else the ball's own (estimated) distance,
 *    and it is labelled which;
 *  - the model reports no per-park uncertainty, so no band is drawn (the caption says so).
 * Rows arrive sorted by P(HR), highest first, ties by park id, so the order is stable across polls.
 * Bold (`data-hr`) is ink weight at the same threshold in both modes, never colour.
 */
import { VisuallyHidden } from "@mantine/core";

import { probText } from "../../lib/park-ball";
import { PARKS_CAPTION } from "../../data/game-claims";

import type { ParkLine } from "./game-account";

export function ParkAgate({
  lines,
  columns = 3,
  carryIsModel,
  figure = "verdict",
  caption,
}: {
  lines: readonly ParkLine[];
  columns?: 2 | 3;
  /** True when the carry figures are the model's per-park carry; false = the ball's own distance. */
  carryIsModel: boolean;
  /** "verdict" (game page): HR / in play. "prob" (/parks): the estimated P(HR), name visible. */
  figure?: "verdict" | "prob";
  /** Replaces the default caption (the /parks page states its own, beside its own controls). */
  caption?: React.ReactNode;
}) {
  const prob = figure === "prob";
  return (
    <div>
      <ul
        className={columns === 2 ? "ed-parks ed-parks--two" : "ed-parks"}
        aria-label="This batted ball in every park"
      >
        {lines.map((l) => (
          <li key={l.id} data-hr={l.hr ? "true" : "false"}>
            {prob ? (
              <span>
                {l.team} <span className="ed-parks__name">{l.parkName}</span>
              </span>
            ) : (
              <span>
                {l.team}
                {l.here ? <span aria-hidden="true"> &dagger;</span> : null}
                <VisuallyHidden>
                  {`, ${l.parkName}${l.here ? ", tonight's park" : ""}`}
                </VisuallyHidden>
              </span>
            )}
            <span>{l.carryFt != null ? `${l.carryFt} ft` : "—"}</span>
            {prob ? (
              <span>
                <VisuallyHidden>estimated </VisuallyHidden>
                {probText(l.prob)}
              </span>
            ) : (
              <span>{l.hr ? "HR" : "in play"}</span>
            )}
          </li>
        ))}
      </ul>
      {caption ?? (
        <p className="ed-note">
          {PARKS_CAPTION}{" "}
          {carryIsModel
            ? "Carry is the model's estimate per park."
            : "Carry is the ball's own tracked distance; this champion serves no per-park carry."}{" "}
          <span aria-hidden="true">&dagger;</span> tonight&rsquo;s park.
        </p>
      )}
    </div>
  );
}
