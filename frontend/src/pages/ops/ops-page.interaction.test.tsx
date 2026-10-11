// @vitest-environment jsdom
/**
 * /ops fallback honesty ([202], SPEC-ops 6.2 / 6.7), driven at the FETCH boundary (C-34) so the
 * real query -> state -> section logic runs.
 *
 *  - offline (every /v1/ops/* answers 503): the fleet shows the verified ROSTER with every figure
 *    em-dashed; latency, queue and log say they could not be read; nothing invented appears.
 *  - empty (every list resolves []): shown as empty, never backfilled with the roster.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen, within } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { ROSTER } from "../../data/ops-claims";
import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../../test-support/behavioral";

import OpsPage from "./ops-page";

beforeAll(installMantineShims);

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("OpsPage, backend unreachable (503)", () => {
  it("shows the roster with no figures and says what could not be read", async () => {
    stubFetchRoutes([{ match: "/v1/ops", status: 503, body: null }]);
    renderWithProviders(<OpsPage />);

    const roster = await screen.findByTestId("ops-roster");
    const serving = screen.getByRole("region", { name: "Serving" });
    expect(within(serving).getByText("Showcase data.")).toBeInTheDocument();
    // Every roster model is listed; no figure cell carries a digit.
    const rows = roster.querySelectorAll("tbody tr");
    expect(rows).toHaveLength(ROSTER.length);
    for (const row of rows) {
      const figures = [...row.querySelectorAll("td.op-num")].map(
        (td) => td.textContent,
      );
      expect(figures.every((f) => f === "—")).toBe(true);
    }
    expect(
      within(serving).getByText(
        "Latency could not be read. Nothing is shown in its place.",
      ),
    ).toBeInTheDocument();

    const log = screen.getByRole("region", { name: "Event log" });
    expect(
      within(log).getByText(
        "The event log could not be read. No invented events are shown in its place.",
      ),
    ).toBeInTheDocument();
    expect(within(log).queryByRole("table")).toBeNull();

    const queue = screen.getByRole("region", { name: "Retraining" });
    expect(
      within(queue).getByText(
        "The retrain queue could not be read. Nothing is shown in its place.",
      ),
    ).toBeInTheDocument();

    // Drift keeps the watched surface but says "not read", never "awaiting".
    const drift = screen.getByRole("region", { name: "Drift" });
    expect(within(drift).queryByText("awaiting first value")).toBeNull();
    expect(within(drift).getAllByText("not read").length).toBeGreaterThan(0);

    expect(document.querySelector(".ed-dateline")?.textContent).toContain(
      "0 of 4 live sections answered",
    );
  });
});

describe("OpsPage, every source answered empty", () => {
  it("says the registry returned no models and never backfills the roster", async () => {
    stubFetchRoutes([
      {
        match: "/v1/ops/events",
        body: { rows: [], page: 0, size: 20, hasNext: false },
      },
      { match: "/v1/ops", body: [] },
    ]);
    renderWithProviders(<OpsPage />);

    expect(
      await screen.findByText("The registry returned no models."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("ops-roster")).toBeNull();
    expect(screen.queryByText(/Roster as verified/)).toBeNull();
    expect(
      await screen.findByText("No events recorded yet."),
    ).toBeInTheDocument();
    expect(
      await screen.findByText(
        "The queue is empty. Nothing is training or waiting for promotion.",
      ),
    ).toBeInTheDocument();
  });
});
