// @vitest-environment jsdom
/**
 * <BrowsePlayers> - the /players index ([195] editorial skin). Covers the facets, the division
 * register with its team marks, the quiet initial state (no facet = no fetch), the single-filter
 * toggle, and the roster's loaded / empty / error branches. Fetch is mocked at the boundary.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import { teamColor } from "../../design/teamColors";
import { DIVISIONS, POSITIONS } from "../../lib/player-file";
import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../../test-support/behavioral";

import { BrowsePlayers } from "./browse-players";

beforeAll(installMantineShims);
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const SKUBAL = {
  id: 669373,
  name: "Tarik Skubal",
  primaryPosition: "P",
  active: true,
  team: "DET",
};

describe("BrowsePlayers", () => {
  it("renders every position as words and every team in its division, each with a colour mark", () => {
    const fetchMock = stubFetchRoutes([]);
    const { container } = renderWithProviders(<BrowsePlayers />);
    for (const p of POSITIONS) {
      expect(screen.getByRole("button", { name: p.word })).toBeInTheDocument();
    }
    for (const d of DIVISIONS) {
      const group = screen.getByRole("group", { name: d.name });
      expect(within(group).getAllByRole("button")).toHaveLength(5);
    }
    const marks = container.querySelectorAll(".pl-mark");
    expect(marks).toHaveLength(30);
    const det = screen.getByRole("button", { name: "Detroit (DET)" });
    expect(det.querySelector(".pl-mark")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
    expect(
      (det.querySelector(".pl-mark") as HTMLElement).style.backgroundColor,
    ).not.toBe("");
    expect(teamColor("DET")).toBeTruthy();
    // Quiet until a facet is picked: nothing fetched, no roster block.
    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("picking a team loads its roster with links to live files; picking it again clears it", async () => {
    const fetchMock = stubFetchRoutes([
      { match: "/v1/players/roster", body: [SKUBAL] },
    ]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<BrowsePlayers />);

    const det = screen.getByRole("button", { name: "Detroit (DET)" });
    await user.click(det);
    expect(det).toHaveAttribute("aria-pressed", "true");
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("team=DET"));
    const link = await screen.findByRole("link", {
      name: "Open Tarik Skubal's file",
    });
    expect(link).toHaveAttribute("href", "/players/669373");
    expect(
      screen.getByRole("heading", { name: "Detroit" }),
    ).toBeInTheDocument();

    await user.click(det);
    expect(det).toHaveAttribute("aria-pressed", "false");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  it("one filter at a time: a position replaces the team", async () => {
    stubFetchRoutes([{ match: "/v1/players/roster", body: [SKUBAL] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<BrowsePlayers />);
    const det = screen.getByRole("button", { name: "Detroit (DET)" });
    await user.click(det);
    await user.click(screen.getByRole("button", { name: "Pitcher" }));
    expect(det).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Pitcher" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
  });

  it("says so when a facet has nobody, and when browse is down", async () => {
    stubFetchRoutes([{ match: "/v1/players/roster", body: [] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<BrowsePlayers />);
    await user.click(screen.getByRole("button", { name: "Catcher" }));
    expect(
      await screen.findByText("No active players for this filter."),
    ).toBeInTheDocument();

    cleanup();
    vi.unstubAllGlobals();
    stubFetchRoutes([{ match: "/v1/players/roster", status: 500, body: null }]);
    renderWithProviders(<BrowsePlayers />);
    await user.click(screen.getByRole("button", { name: "Catcher" }));
    expect(
      await screen.findByText("Browse is unavailable right now."),
    ).toBeInTheDocument();
  });
});
