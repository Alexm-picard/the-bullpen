// @vitest-environment jsdom
/** <Scorebug>: the live region covers the game state only, and an unknown score is never a 0. */
import "@testing-library/jest-dom/vitest";

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { Scorebug } from "./scorebug";

afterEach(cleanup);

describe("Scorebug", () => {
  it("keeps the per-pitch detail out of the live region", () => {
    render(
      <Scorebug
        awayTeam="BAL"
        homeTeam="BOS"
        awayScore={1}
        homeScore={2}
        state="INN 6"
        live
        detail="FF · 94.8"
      />,
    );
    const status = screen.getByRole("status");
    expect(status).toHaveAccessibleName("BAL 1, BOS 2, INN 6, live");
    expect(status).not.toHaveTextContent("94.8");
    expect(screen.getByText("FF · 94.8")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
  });

  it("announces a detail only when asked to (stopped-play descriptions)", () => {
    render(
      <Scorebug
        awayTeam="BAL"
        homeTeam="BOS"
        awayScore={1}
        homeScore={2}
        state="DELAY"
        detail="Delayed: Rain"
        announceDetail
      />,
    );
    expect(screen.getByRole("status")).toHaveAccessibleName(
      "BAL 1, BOS 2, DELAY, Delayed: Rain",
    );
  });

  it("renders an unknown score as an en-dash, never a fabricated 0", () => {
    render(
      <Scorebug
        awayTeam="BAL"
        homeTeam="BOS"
        awayScore={null}
        homeScore={null}
        state="—"
      />,
    );
    const status = screen.getByRole("status");
    expect(status).toHaveAccessibleName("BAL –, BOS –, —");
    expect(status).not.toHaveTextContent("0");
  });
});
