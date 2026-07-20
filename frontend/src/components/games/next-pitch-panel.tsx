/**
 * NextPitchPanel (A6, ADR-0014 / decision [180]) - the first user-visible FORWARD-looking pitch
 * prediction: the pre-pitch head's calibrated 5-class outcome distribution for the upcoming pitch.
 *
 * Honesty contract (verbatim from ADR-0014): the model's public claim is "calibrated pre-pitch
 * estimate; passes calibration (ECE<0.02), not an accuracy claim." - the caption below the bars
 * says exactly that, and the panel never presents the argmax as a best-guess call, only as the
 * most probable class of a trustworthy distribution.
 *
 * States:
 *  - gated off (no settled at-bat / game not live): muted awaiting line - no request was made.
 *  - 503: a clean "model not yet promoted" line, NOT an error - the endpoint 503s by design until
 *    the PRE champion is promoted (rule 6, human-gated).
 *  - loading / other errors / data: the usual shapes.
 */
import type { PitchPredictionResponse } from "../../api/games";
import { GameApiError } from "../../api/games";
import { colors, typography } from "../../design/broadcast";

/** Canonical 5-class display order - matches the pitches-table outcome vocabulary. */
const CLASS_ORDER = [
  "ball",
  "called_strike",
  "swinging_strike",
  "foul",
  "in_play",
] as const;

const CLASS_LABELS: Record<string, string> = {
  ball: "Ball",
  called_strike: "Called strike",
  swinging_strike: "Swinging strike",
  foul: "Foul",
  in_play: "In play",
};

const mutedMono: React.CSSProperties = {
  margin: 0,
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  letterSpacing: "0.02em",
  color: colors.textMuted,
};

export type NextPitchPanelProps = {
  prediction: PitchPredictionResponse | undefined;
  isLoading: boolean;
  error: unknown;
  /** True when the query was allowed to fire (live game + settled at-bat). */
  enabled: boolean;
};

export function NextPitchPanel({
  prediction,
  isLoading,
  error,
  enabled,
}: NextPitchPanelProps) {
  if (!enabled) {
    return (
      <p style={mutedMono}>
        Awaiting a settled at-bat &mdash; the next-pitch estimate fires only
        mid-at-bat, on live pitches with full context.
      </p>
    );
  }
  if (error instanceof GameApiError && error.status === 503) {
    return (
      <p data-testid="next-pitch-unpromoted" style={mutedMono}>
        Pitch model not yet promoted &mdash; the pre-pitch head serves once its
        calibration gate passes review (promotion is human-gated).
      </p>
    );
  }
  if (error) {
    return <p style={mutedMono}>Next-pitch estimate unavailable right now.</p>;
  }
  if (isLoading || !prediction) {
    return (
      <p aria-busy="true" style={mutedMono}>
        Scoring the next pitch&hellip;
      </p>
    );
  }

  return (
    <div>
      <ul
        aria-label="Next-pitch outcome probabilities"
        style={{ listStyle: "none", margin: 0, padding: 0 }}
      >
        {CLASS_ORDER.map((cls) => {
          const p = prediction.probabilities[cls] ?? 0;
          const isWinner = cls === prediction.winner;
          return (
            <li
              key={cls}
              style={{
                display: "grid",
                gridTemplateColumns: "130px 1fr 56px",
                alignItems: "center",
                gap: 10,
                padding: "3px 0",
              }}
            >
              <span
                style={{
                  fontFamily: typography.fonts.body,
                  fontSize: 13,
                  fontWeight: isWinner ? 700 : 400,
                  color: isWinner ? colors.ink : colors.text,
                }}
              >
                {CLASS_LABELS[cls] ?? cls}
              </span>
              <span
                aria-hidden="true"
                style={{
                  display: "block",
                  height: 10,
                  background: colors.fieldSubtle,
                  overflow: "hidden",
                }}
              >
                <span
                  style={{
                    display: "block",
                    height: "100%",
                    width: `${Math.round(p * 1000) / 10}%`,
                    background: isWinner ? colors.gold : colors.steel,
                  }}
                />
              </span>
              <span
                style={{
                  fontFamily: typography.fonts.mono,
                  fontSize: 12,
                  fontFeatureSettings: '"tnum" 1',
                  textAlign: "right",
                  fontWeight: isWinner ? 700 : 400,
                  color: isWinner ? colors.goldInk : colors.textMuted,
                }}
              >
                {(p * 100).toFixed(1)}%
              </span>
            </li>
          );
        })}
      </ul>
      <p style={{ ...mutedMono, marginTop: 8 }}>
        {prediction.modelName} {prediction.modelVersion} &middot; calibrated
        pre-pitch estimate; passes calibration (ECE&lt;0.02), not an accuracy
        claim.
      </p>
    </div>
  );
}
