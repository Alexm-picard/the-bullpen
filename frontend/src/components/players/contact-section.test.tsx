// @vitest-environment jsdom
/**
 * <ContactSection> - the batter file's hero. Filters drive the real query (fetch mocked at the
 * boundary); untracked balls are counted, not plotted; an empty filtered result offers a reset.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import type { BattedBallRow } from "../../api/players";
import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../../test-support/behavioral";

import { DASH, eventWord } from "../../lib/player-file";

import { ContactSection } from "./contact-section";

beforeAll(installMantineShims);
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const HR: BattedBallRow = {
  gameDate: "2026-10-04",
  events: "home_run",
  bbType: "fly_ball",
  launchSpeedMph: 117.4,
  launchAngleDeg: 27,
  hitDistanceFt: 468,
  parkId: "NYY",
  stand: "R",
};
const UNTRACKED: BattedBallRow = {
  ...HR,
  events: "field_out",
  launchSpeedMph: null,
  launchAngleDeg: null,
  hitDistanceFt: null,
};

describe("ContactSection", () => {
  it("plots tracked balls, counts the untracked, and lists them newest first", async () => {
    stubFetchRoutes([{ match: "/batted-balls", body: [HR, UNTRACKED] }]);
    const { container } = renderWithProviders(
      <ContactSection playerId={592450} />,
    );
    expect(
      await screen.findByText(
        /1 ball has no tracked speed or angle and is left off\./,
      ),
    ).toBeInTheDocument();
    expect(container.querySelectorAll(".pl-sc__hr")).toHaveLength(1);
    expect(container.querySelectorAll(".pl-sc__pt")).toHaveLength(0);
    expect(screen.getByText("Home run, fly ball")).toBeInTheDocument();
    expect(screen.getAllByText(DASH).length).toBeGreaterThanOrEqual(3);
    expect(
      screen.getByText(
        "All 2, newest first. A dash means Statcast did not track it.",
      ),
    ).toBeInTheDocument();
  });

  it("sends the filters to the endpoint and offers a reset when nothing matches", async () => {
    const fetchMock = stubFetchRoutes([{ match: "/batted-balls", body: [] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<ContactSection playerId={592450} />);
    expect(
      await screen.findByText("No balls in play on file."),
    ).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Fly" }));
    await user.click(screen.getByRole("checkbox", { name: "Home runs only" }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenLastCalledWith(
        expect.stringMatching(/bbType=fly_ball.*event=home_run/),
      ),
    );
    expect(
      await screen.findByText(/No balls in play match these filters\./),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    expect(screen.getByRole("button", { name: "All" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(
      screen.getByRole("checkbox", { name: "Home runs only" }),
    ).not.toBeChecked();
  });

  it("says so when the request fails", async () => {
    stubFetchRoutes([{ match: "/batted-balls", status: 500, body: null }]);
    renderWithProviders(<ContactSection playerId={592450} />);
    expect(
      await screen.findByText("Could not load his balls in play."),
    ).toBeInTheDocument();
  });

  it("words events plainly and never invents one", () => {
    expect(eventWord("home_run")).toBe("Home run");
    expect(eventWord("grounded_into_double_play")).toBe("Double play");
    expect(eventWord("catcher_interf")).toBe("Catcher interf");
    expect(eventWord("")).toBe(DASH);
  });
});
