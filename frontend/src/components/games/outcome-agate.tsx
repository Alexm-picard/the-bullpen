/**
 * OutcomeAgate - the next pitch, by outcome (pitch_outcome_pre, ADR-0014 / decision [180]) in the
 * [195] editorial skin. Replaces the broadcast NextPitchPanel; its state logic is carried over
 * unchanged:
 *  - gated off (no settled at-bat / game not live): a note, no request was made;
 *  - 503: the clean "not yet promoted" line - the endpoint 503s by design until promotion
 *    (rule 6, human-gated), so it is not an error;
 *  - other errors / loading / data.
 *
 * [180]: the five classes keep a FIXED order (a stable reading position across pitches) and the
 * most probable class may carry ink weight - never colour, never the accent. The caption is the
 * model's public claim, verbatim in substance from ADR-0014.
 */
import { VisuallyHidden } from "@mantine/core";

import { GameApiError, type PitchPredictionResponse } from "../../api/games";
import { OUTCOME_CAPTION } from "../../data/game-claims";
import { agateShare } from "../../lib/pitch-type-prior";

import { OUTCOME_CLASS_LABELS, OUTCOME_CLASS_ORDER } from "./game-account";

const PERCENT = new Intl.NumberFormat("en-US", {
  style: "percent",
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});

export type OutcomeAgateProps = {
  prediction:
    | Pick<
        PitchPredictionResponse,
        "probabilities" | "winner" | "modelName" | "modelVersion"
      >
    | undefined;
  isLoading: boolean;
  error: unknown;
  /** True when the query was allowed to fire (live game + settled at-bat), or /live served it. */
  enabled: boolean;
  /** Provenance suffix, e.g. "worker-computed 19:42:07 ET". */
  computed?: string;
};

function Gated({ children, testId }: { children: string; testId?: string }) {
  return (
    <div className="ed-gated" data-testid={testId}>
      <p className="ed-note">{children}</p>
    </div>
  );
}

export function OutcomeAgate({
  prediction,
  isLoading,
  error,
  enabled,
  computed,
}: OutcomeAgateProps) {
  if (!enabled) {
    return (
      <Gated>
        Awaiting a settled at-bat. The next-pitch estimate runs only mid-at-bat,
        on live pitches with full context.
      </Gated>
    );
  }
  if (error instanceof GameApiError && error.status === 503) {
    return (
      <Gated testId="next-pitch-unpromoted">
        Pitch model not yet promoted. The pre-pitch head serves once its
        calibration gate passes review; promotion is human-gated.
      </Gated>
    );
  }
  if (error) {
    return <Gated>Next-pitch estimate unavailable right now.</Gated>;
  }
  if (isLoading || !prediction) {
    return (
      <div role="status" aria-busy="true">
        <VisuallyHidden>Scoring the next pitch</VisuallyHidden>
        <div aria-hidden="true">
          {OUTCOME_CLASS_ORDER.map((c) => (
            <div
              key={c}
              className="ed-shell"
              style={{ height: "1.4rem", margin: "0.7rem 0" }}
            />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div>
      <table
        className="ed-agate ed-agate--big"
        aria-label="Next-pitch outcome probabilities"
        aria-live="off"
      >
        <caption>{OUTCOME_CAPTION}</caption>
        <thead>
          <tr>
            <th scope="col">Outcome</th>
            <th scope="col" className="ed-barcell">
              <VisuallyHidden>Share bar</VisuallyHidden>
            </th>
            <th scope="col" className="ed-num">
              Prob.
            </th>
          </tr>
        </thead>
        <tbody>
          {OUTCOME_CLASS_ORDER.map((cls) => {
            const p = Math.max(
              0,
              Math.min(1, prediction.probabilities[cls] ?? 0),
            );
            const top = cls === prediction.winner;
            return (
              <tr
                key={cls}
                className={
                  top ? "ed-agate__row ed-agate__row--top" : "ed-agate__row"
                }
                data-top={top ? "true" : undefined}
              >
                <td>{OUTCOME_CLASS_LABELS[cls] ?? cls}</td>
                <td className="ed-barcell" aria-hidden="true">
                  <div className="ed-track">
                    <span
                      className="ed-bar"
                      style={{ transform: `scaleX(${p})` }}
                    />
                  </div>
                </td>
                <td className="ed-num">
                  <span aria-hidden="true">{agateShare(p)}</span>
                  <VisuallyHidden>{PERCENT.format(p)}</VisuallyHidden>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="ed-prov">
        {`${prediction.modelName} ${prediction.modelVersion}`.trim()}
        {computed ? ` · ${computed}` : ""}
      </p>
    </div>
  );
}
