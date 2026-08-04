/**
 * PitchTypePanel - the calibrated pitch-type PRIOR (decision [183], champion since 2026-08-02).
 *
 * Sibling to {@link NextPitchPanel} in shape, and deliberately UNLIKE it in one respect: that panel
 * emphasises the argmax (bold label, gold bar, bold percentage) because its head is promoted on a
 * calibrated outcome distribution where the most probable class is a meaningful read. This one
 * emphasises NOTHING. Top-1 here is ~0.45 because pitch selection is high-entropy, so a highlighted
 * row would be wrong more often than right while carrying all the visual authority of an answer.
 *
 * [183] is enforced in three independent places, which is deliberate - any one of them alone would
 * be a convention someone could undo without noticing:
 *   1. the API omits a `winner` field, so there is nothing to render;
 *   2. `PitchTypePriorResponse` does not model one, so adding a headline requires editing a type;
 *   3. this panel styles every row identically, so no row can read as "the call".
 *
 * Rows are ranked by probability because that is how a distribution is read, not because the top
 * one is a prediction. Uniform styling is what keeps ranking honest: the order conveys magnitude,
 * the presentation conveys no verdict.
 *
 * priorPitches is surfaced rather than buried. A prior over 12 career pitches and one over 14,000
 * are not comparable, and the field exists precisely so a caller can tell them apart - so the panel
 * that hides it defeats the reason it was added.
 *
 * States:
 *  - gated off: no request was made (the enabled gate is mandatory - every call writes to
 *    prediction_log, the drift-baseline source).
 *  - 503: the SERVER'S OWN REASON, verbatim. Two distinct conditions share the status and the
 *    error code today (no promoted champion vs a stale/missing career-prior snapshot; issue 401),
 *    so the panel does not classify them - it reports what the server said. A refusal to serve a
 *    prior computed over the wrong history is a designed honesty feature, so the explanation IS the
 *    payload rather than an error to be flattened.
 *  - other error / loading / data: the usual shapes.
 */
import type { PitchTypePriorResponse } from "../../api/games";
import { GameApiError } from "../../api/games";
import { colors, typography } from "../../design/broadcast";

/** The seven classes the model emits, with display names. Order here is not display order. */
const CLASS_LABELS: Record<string, string> = {
  FF: "Four-seam",
  SI: "Sinker",
  FC: "Cutter",
  SL: "Slider",
  CU: "Curveball",
  CH: "Changeup",
  OFF: "Other",
};

const mutedMono: React.CSSProperties = {
  margin: 0,
  fontFamily: typography.fonts.mono,
  fontSize: 12,
  letterSpacing: "0.02em",
  color: colors.textMuted,
};

export type PitchTypePanelProps = {
  prior: PitchTypePriorResponse | undefined;
  isLoading: boolean;
  error: unknown;
  /** True when the query was allowed to fire. Mirrors NextPitchPanel's gate exactly. */
  enabled: boolean;
};

export function PitchTypePanel({
  prior,
  isLoading,
  error,
  enabled,
}: PitchTypePanelProps) {
  if (!enabled) {
    return (
      <p style={mutedMono}>
        Awaiting a settled at-bat &mdash; the pitch-type prior describes one
        specific upcoming pitch, so it needs the same live context.
      </p>
    );
  }
  if (error instanceof GameApiError && error.status === 503) {
    // The server's reason, verbatim - the frontend owns no facts about WHY a prior is unavailable.
    const reason = error.message.trim();
    return (
      <p data-testid="pitch-type-unavailable" style={mutedMono}>
        Pitch-type prior unavailable &mdash;{" "}
        {reason === "" ? "the server gave no reason." : reason}
      </p>
    );
  }
  if (error) {
    return <p style={mutedMono}>Pitch-type prior unavailable right now.</p>;
  }
  if (isLoading || !prior) {
    return (
      <p aria-busy="true" style={mutedMono}>
        Composing the pitch-type prior&hellip;
      </p>
    );
  }

  const ranked = Object.entries(prior.probabilities).sort(
    ([, a], [, b]) => b - a,
  );

  return (
    <div>
      <ul
        aria-label="Pitch-type prior probabilities"
        style={{ listStyle: "none", margin: 0, padding: 0 }}
      >
        {ranked.map(([cls, p]) => (
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
            {/* No conditional weight or colour anywhere below: every row is styled identically,
                including the highest. That uniformity IS the [183] constraint in the DOM. */}
            <span
              style={{
                fontFamily: typography.fonts.body,
                fontSize: 13,
                color: colors.text,
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
                  background: colors.steel,
                }}
              />
            </span>
            <span
              style={{
                fontFamily: typography.fonts.mono,
                fontSize: 12,
                fontFeatureSettings: '"tnum" 1',
                textAlign: "right",
                color: colors.textMuted,
              }}
            >
              {(p * 100).toFixed(1)}%
            </span>
          </li>
        ))}
      </ul>
      <p style={{ ...mutedMono, marginTop: 8 }}>
        {prior.modelName} {prior.servingVersion} &middot; calibrated pitch-type
        prior (ECE &lt; 0.02) &mdash; not a top-1 prediction. Computed over{" "}
        {prior.priorPitches.toLocaleString()} career pitches.
      </p>
    </div>
  );
}
