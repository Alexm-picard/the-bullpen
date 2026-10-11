// @vitest-environment jsdom
/**
 * Behavioral tests for <PlayerSearch> ([195] editorial skin). Exercises the real interaction paths:
 * the 200ms debounce coalescing keystrokes into one search, selecting a result firing onSelect and
 * clearing the box, a Statcast-ID query keeping its server-filtered matches, the "/" shortcut, and
 * the empty / error branches. Mocks the FETCH boundary (GET /v1/players/search), not the hook.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import type { PlayerSearchResult } from "../../api/players";
import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../../test-support/behavioral";

import { PlayerSearch, SEARCH_LABEL } from "./player-search";

const TROUT: PlayerSearchResult = {
  id: 545361,
  name: "Mike Trout",
  primaryPosition: "CF",
  active: true,
  team: "LAA",
};

beforeAll(installMantineShims);

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("PlayerSearch", () => {
  it("is labelled 'Name or Statcast ID' (visible label, not a placeholder) and never mentions jersey numbers", () => {
    stubFetchRoutes([]);
    const { container } = renderWithProviders(
      <PlayerSearch onSelect={vi.fn()} />,
    );
    expect(SEARCH_LABEL).toBe("Name or Statcast ID");
    expect(
      screen.getByLabelText(SEARCH_LABEL, { selector: "input" }),
    ).toBeInTheDocument();
    expect(container.textContent?.toLowerCase()).not.toContain("jersey");
  });

  it("debounces keystrokes into one search; selecting a result reports the chosen player", async () => {
    const fetchMock = stubFetchRoutes([
      { match: "/v1/players/search", body: [TROUT] },
    ]);
    const onSelect = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={onSelect} />);

    const input = screen.getByLabelText(SEARCH_LABEL, { selector: "input" });
    await user.type(input, "trout");

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("q=trout"));

    // The option renders the name (typed part in <mark>), position and team.
    const option = await screen.findByRole("option", { hidden: true });
    expect(option.textContent).toBe("Mike TroutCFLAA");
    expect(option.querySelector("mark")?.textContent).toBe("Trout");
    await user.keyboard("{ArrowDown}{Enter}");
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(TROUT));
  });

  it("keeps a Statcast-ID query's matches (the server filters; the ID is not in the label)", async () => {
    stubFetchRoutes([{ match: "/v1/players/search", body: [TROUT] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={vi.fn()} />);
    await user.type(
      screen.getByLabelText(SEARCH_LABEL, { selector: "input" }),
      "5453",
    );
    expect(
      await screen.findByRole("option", { hidden: true }),
    ).toHaveTextContent("Mike Trout");
    expect(await screen.findByText("1 player found")).toBeInTheDocument();
  });

  it("'/' focuses the field from the page, but not while typing elsewhere", () => {
    stubFetchRoutes([]);
    renderWithProviders(
      <div>
        <input aria-label="other" />
        <PlayerSearch onSelect={vi.fn()} />
      </div>,
    );
    const field = screen.getByLabelText(SEARCH_LABEL, { selector: "input" });
    fireEvent.keyDown(document.body, { key: "/" });
    expect(field).toHaveFocus();

    const other = screen.getByLabelText("other");
    other.focus();
    fireEvent.keyDown(other, { key: "/" });
    expect(other).toHaveFocus();
  });

  it("shows the empty state when the search returns no matches", async () => {
    stubFetchRoutes([{ match: "/v1/players/search", body: [] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={vi.fn()} />);

    await user.type(
      screen.getByLabelText(SEARCH_LABEL, { selector: "input" }),
      "zzz",
    );
    expect(await screen.findByText(/no player matches/i)).toBeInTheDocument();
  });

  it("shows the error state alone when the search request fails", async () => {
    stubFetchRoutes([{ match: "/v1/players/search", status: 500, body: null }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={vi.fn()} />);

    await user.type(
      screen.getByLabelText(SEARCH_LABEL, { selector: "input" }),
      "trout",
    );
    expect(
      await screen.findByText("Search is unavailable right now."),
    ).toBeInTheDocument();
    // A failed fetch says nothing about the data: never a false "no match" beside it.
    expect(screen.queryByText(/no player matches/i)).not.toBeInTheDocument();
  });
});
