// @vitest-environment jsdom
/**
 * Behavioral (render + DOM query) tests for <PostPredictionPanel> (F2.1c, decision [177]).
 * Covers the retrospective contract: the champion's LOGGED call vs the realized outcome, the
 * honest holdout-accuracy + "not a prediction of the next pitch" framing, the ✓/✗ hit read, the
 * empty state, and the registry-guard join-miss edge (0/null identity must render as "-", never a
 * real id 0). The panel is purely presentational (plain table + design tokens, no fetch), so this
 * needs only jsdom + render.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { PostPredictionRow } from "../../api/games";

import { PostPredictionPanel } from "./post-prediction-panel";

// The suite runs without vitest `globals`, so testing-library's auto-cleanup is not registered.
afterEach(cleanup);

function row(over: Partial<PostPredictionRow> = {}): PostPredictionRow {
  return {
    atBatIndex: 12,
    pitchNumber: 3,
    inning: 6,
    pitcherId: 1,
    batterId: 2,
    realizedOutcome: "called_strike",
    postClasses: { called_strike: 0.71, ball: 0.19, foul: 0.1 },
    postWinner: "called_strike",
    modelVersion: "v1", // the current post champion the pinned holdout numbers were measured on
    ...over,
  };
}

describe("PostPredictionPanel", () => {
  it("renders the model identity and the honest holdout-accuracy + retrospective framing", () => {
    render(<PostPredictionPanel rows={[row()]} />);
    expect(screen.getByText(/pitch_outcome_post/)).toBeInTheDocument();
    expect(screen.getByText(/v1/)).toBeInTheDocument();
    expect(screen.getByText(/59\.1% top-1/)).toBeInTheDocument();
    expect(screen.getByText(/80\.8% top-2/)).toBeInTheDocument();
    expect(
      screen.getByText(/not a prediction of the next pitch/),
    ).toBeInTheDocument();
  });

  it("suppresses the pinned holdout numbers for a version they were not measured on", () => {
    // The 59.1%/80.8% figure describes v1 (PR-210). A future promoted version must not inherit
    // v1's accuracy label - it shows the retrospective framing + its own version, no stale numbers.
    render(<PostPredictionPanel rows={[row({ modelVersion: "v2" })]} />);
    expect(screen.getByText(/pitch_outcome_post/)).toBeInTheDocument();
    expect(screen.getByText(/v2/)).toBeInTheDocument();
    expect(screen.queryByText(/59\.1% top-1/)).not.toBeInTheDocument();
    expect(
      screen.getByText(/not a prediction of the next pitch/),
    ).toBeInTheDocument();
  });

  it("shows the champion's top-1 call with its rounded probability and the realized outcome chip", () => {
    render(<PostPredictionPanel rows={[row()]} />);
    // champion call: winner label + rounded pct (0.71 -> 71%)
    expect(screen.getByText("71%")).toBeInTheDocument();
    // "called strike" appears both in the champion-call cell and the Actual chip
    expect(screen.getAllByText("called strike").length).toBeGreaterThanOrEqual(
      2,
    );
    // header + one data row
    expect(screen.getAllByRole("row")).toHaveLength(2);
  });

  it("reads a hit as ✓ when the champion's call matched the realized outcome", () => {
    render(<PostPredictionPanel rows={[row()]} />);
    expect(screen.getByText("✓")).toBeInTheDocument();
    expect(screen.queryByText("✗")).not.toBeInTheDocument();
  });

  it("reads a miss as ✗ when the champion's call differed from the realized outcome", () => {
    render(
      <PostPredictionPanel
        rows={[row({ postWinner: "ball", realizedOutcome: "called_strike" })]}
      />,
    );
    expect(screen.getByText("✗")).toBeInTheDocument();
    expect(screen.queryByText("✓")).not.toBeInTheDocument();
  });

  it("falls back to the most-probable class when postWinner is absent", () => {
    render(
      <PostPredictionPanel
        rows={[
          row({
            postWinner: null,
            postClasses: { ball: 0.2, in_play: 0.8 },
            realizedOutcome: "in_play",
          }),
        ]}
      />,
    );
    expect(screen.getByText("80%")).toBeInTheDocument();
    expect(screen.getByText("✓")).toBeInTheDocument();
  });

  it("renders a join-miss row's 0/null identity as '-', never a real id 0", () => {
    // registry-guard note: identity fields come from a LEFT JOIN; a not-yet-reconciled pitch has
    // inning 0 + realizedOutcome null. Neither may surface as "inn 0" or a bogus outcome.
    render(
      <PostPredictionPanel
        rows={[row({ inning: 0, realizedOutcome: null })]}
      />,
    );
    const rows = screen.getAllByRole("row");
    const dataRow = rows[1]!;
    expect(within(dataRow).queryByText(/inn 0/)).not.toBeInTheDocument();
    // realized outcome absent -> no chip, and the hit column cannot be scored
    expect(screen.queryByText("✓")).not.toBeInTheDocument();
    expect(screen.queryByText("✗")).not.toBeInTheDocument();
    // the at-bat/pitch coordinate still renders
    expect(within(dataRow).getByText(/#12\.3/)).toBeInTheDocument();
  });

  it("renders the empty state when the game has no post predictions yet", () => {
    render(<PostPredictionPanel rows={[]} />);
    expect(screen.getByRole("status")).toHaveTextContent(
      "No post-pitch predictions logged for this game yet",
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("notes when more of the game's predictions exist beyond the shown window", () => {
    render(<PostPredictionPanel rows={[row()]} hasNext />);
    expect(
      screen.getByText(/more of the game's predictions exist/),
    ).toBeInTheDocument();
  });
});
