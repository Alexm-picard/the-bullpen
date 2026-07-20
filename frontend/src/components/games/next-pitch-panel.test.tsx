// @vitest-environment jsdom
/**
 * NextPitchPanel (A6) state tests: the honest-caption data render, the CLEAN 503
 * "not yet promoted" state (the prod reality until the TD promotes PRE - it must
 * not read as an error), and the gated-off line (no request fired).
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { GameApiError } from "../../api/games";
import type { PitchPredictionResponse } from "../../api/games";

import { NextPitchPanel } from "./next-pitch-panel";

afterEach(cleanup);

const PREDICTION: PitchPredictionResponse = {
  probabilities: {
    ball: 0.35,
    called_strike: 0.2,
    swinging_strike: 0.1,
    foul: 0.2,
    in_play: 0.15,
  },
  winner: "ball",
  modelName: "pitch_outcome_pre",
  modelVersion: "v1",
  latencyMicros: 1830,
  correlationId: "corr-1",
};

describe("NextPitchPanel", () => {
  it("renders the 5-class distribution with the honest calibration caption", () => {
    render(
      <NextPitchPanel
        prediction={PREDICTION}
        isLoading={false}
        error={null}
        enabled
      />,
    );
    expect(
      screen.getByRole("list", { name: /next-pitch outcome probabilities/i }),
    ).toBeInTheDocument();
    expect(screen.getByText("Ball")).toBeInTheDocument();
    expect(screen.getByText("35.0%")).toBeInTheDocument();
    // The ADR-0014 public claim, verbatim - never an accuracy claim.
    expect(
      screen.getByText(
        /passes calibration \(ECE<0\.02\), not an accuracy claim/i,
      ),
    ).toBeInTheDocument();
    expect(screen.getByText(/pitch_outcome_pre v1/i)).toBeInTheDocument();
  });

  it("renders the clean not-yet-promoted state on a 503, not an error", () => {
    render(
      <NextPitchPanel
        prediction={undefined}
        isLoading={false}
        error={new GameApiError(503, "pitch predict failed: HTTP 503")}
        enabled
      />,
    );
    expect(screen.getByTestId("next-pitch-unpromoted")).toHaveTextContent(
      /not yet promoted/i,
    );
    expect(screen.queryByText(/unavailable/i)).not.toBeInTheDocument();
  });

  it("renders the awaiting line when gated off (no request fired)", () => {
    render(
      <NextPitchPanel
        prediction={undefined}
        isLoading={false}
        error={null}
        enabled={false}
      />,
    );
    expect(screen.getByText(/awaiting a settled at-bat/i)).toBeInTheDocument();
  });

  it("renders a generic degraded line on a non-503 error", () => {
    render(
      <NextPitchPanel
        prediction={undefined}
        isLoading={false}
        error={new GameApiError(500, "boom")}
        enabled
      />,
    );
    expect(screen.getByText(/unavailable right now/i)).toBeInTheDocument();
  });
});
