import type { CSSProperties } from "react";

import { colors, layouts, typography } from "../design/broadcast";

const FIELD: CSSProperties = {
  backgroundColor: colors.paper,
  minHeight: "100%",
  padding: "48px 16px 72px",
};

const COLUMN: CSSProperties = {
  maxWidth: layouts.editorialMaxWidth,
  margin: "0 auto",
  color: colors.paperText,
};

const EYEBROW: CSSProperties = {
  fontFamily: typography.fonts.mono,
  fontSize: 11,
  letterSpacing: "0.16em",
  textTransform: "uppercase",
  color: colors.paperMuted,
  marginBottom: 8,
};

const H1: CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.heavy,
  fontSize: 48,
  lineHeight: 1.05,
  textTransform: "uppercase",
  color: colors.paperText,
  margin: "0 0 8px",
};

const INTRO: CSSProperties = {
  fontSize: 16,
  lineHeight: 1.6,
  color: colors.paperMuted,
  maxWidth: "56ch",
  marginBottom: 48,
};

const SECTION: CSSProperties = {
  marginBottom: 56,
};

const H2: CSSProperties = {
  fontFamily: typography.fonts.display,
  fontWeight: typography.weights.heavy,
  fontSize: 24,
  textTransform: "uppercase",
  letterSpacing: "0.02em",
  color: colors.paperText,
  margin: "0 0 12px",
  paddingBottom: 8,
  borderBottom: `2px solid ${colors.paperRule}`,
};

const BODY: CSSProperties = {
  fontSize: 15,
  lineHeight: 1.65,
  color: colors.paperText,
};

const CALLOUT: CSSProperties = {
  backgroundColor: colors.paperRule,
  border: `1px solid ${colors.paperRule}`,
  borderRadius: 2,
  padding: "12px 16px",
  fontSize: 14,
  color: colors.paperMuted,
  margin: "16px 0",
};

export default function ModelGuidePage() {
  return (
    <div style={FIELD}>
      <div style={COLUMN}>
        <div style={EYEBROW}>The Bullpen - Model Guide</div>
        <h1 style={H1}>How the models work</h1>
        <p style={INTRO}>
          Four calibrated models predict live during MLB games. This page
          explains what each one does, what it sees, and how its accuracy is
          measured. The data surfaces on the game page and elsewhere link here
          rather than carrying their own explanations.
        </p>

        <section id="next-pitch" style={SECTION}>
          <h2 style={H2}>Next-Pitch Outcome</h2>
          <div style={BODY}>
            <p>
              Predicts the outcome of the next pitch before it is thrown: ball,
              called strike, swinging strike, foul, or in play. The model sees
              the count, runners, batter/pitcher history (28-day form, target
              encodings), and park - everything available before the windup.
            </p>
            <div style={CALLOUT}>
              <strong>Primary metric:</strong> Brier score (lower is better).
              The champion passed its promotion gate at Brier 0.104 vs the LR
              baseline's 0.149, with ECE 0.0013 across 710k rolling-origin CV
              rows.
            </div>
            <p>
              Calibration is isotonic per class: each outcome's predicted
              probability is mapped through a piecewise-linear function fitted
              on the validation fold, then renormalized so the five classes sum
              to 1. The calibrator is a separate artifact from the model weights
              and is verified at registration (rule 7).
            </p>
          </div>
        </section>

        <section id="pitch-type" style={SECTION}>
          <h2 style={H2}>Pitch-Type Prior</h2>
          <div style={BODY}>
            <p>
              A calibrated prior over seven pitch-type classes (FF, SI, FC, SL,
              CU, CH, OFF) for the pitch about to be thrown. The model sees the
              pitcher's career arsenal frequencies, the current count, the
              outing sequence (previous two pitches, pitches into the outing),
              and the batter's handedness.
            </p>
            <div style={CALLOUT}>
              <strong>Primary metric:</strong> ECE (expected calibration error).
              The champion passed at ECE 0.0036, well under the 0.02 threshold.
              This model is scoped as a calibrated prior, not a top-1 predictor
              - its value is calibration, not accuracy.
            </div>
            <p>
              Calibration is temperature scaling: a single positive scalar
              divides the logits and re-softmaxes, preserving the ranking while
              adjusting confidence. Order-preserving by construction.
            </p>
          </div>
        </section>

        <section id="batted-ball" style={SECTION}>
          <h2 style={H2}>Batted-Ball Outcome</h2>
          <div style={BODY}>
            <p>
              Predicts the fielded outcome (out, single, double, triple, home
              run) of a batted ball at each of the 30 MLB parks, given exit
              velocity, launch angle, spray angle, hit distance, batter
              handedness, base state, and outs. The model is a multi-output MLP
              with a shared backbone and 30 per-park heads.
            </p>
            <div style={CALLOUT}>
              <strong>What the /parks page shows:</strong> the model's P(HR)
              surface across all 30 parks for user-adjustable launch parameters.
              The per-park carry distance (in feet) comes from a second output
              head on the same architecture.
            </div>
            <p>
              Calibration is per-park isotonic: 30 parks times 5 outcomes = 150
              calibrators, each a piecewise-linear function. The calibrator file
              is 241 KB of JSON and is verified at registration.
            </p>
          </div>
        </section>

        <section id="how-scored" style={SECTION}>
          <h2 style={H2}>How accuracy is scored</h2>
          <div style={BODY}>
            <p>
              Every prediction is logged to ClickHouse with the model version,
              the input features, and the predicted probabilities. When the
              outcome is known (the next pitch is thrown, the play resolves), a
              truth-join matches the prediction to the result. The /accuracy
              page shows two surfaces that never mix:
            </p>
            <ul>
              <li>
                <strong>Live scorecard (rolling 7 days):</strong> realized top-1
                accuracy from truth-joined prediction_log rows. This is what the
                model actually got right on real, served predictions.
              </li>
              <li>
                <strong>Held-out scorecard:</strong> offline rolling-origin CV
                metrics (Brier, ECE, confusion matrices) from the training
                evaluation. This is the model's expected performance on unseen
                data.
              </li>
            </ul>
            <p>
              The two surfaces measure different things and are kept separate so
              neither contaminates the other. A model can have strong offline
              metrics and weak live accuracy (distribution shift) or vice versa
              (the training set was harder than production).
            </p>
          </div>
        </section>

        <footer
          style={{
            fontFamily: typography.fonts.mono,
            fontSize: 11,
            color: colors.paperMuted,
            letterSpacing: "0.04em",
            borderTop: `1px solid ${colors.paperRule}`,
            paddingTop: 16,
            marginTop: 48,
          }}
        >
          THE BULLPEN - MODEL GUIDE
        </footer>
      </div>
    </div>
  );
}
