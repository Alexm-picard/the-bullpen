// @vitest-environment jsdom
/**
 * Behavioral tests for <PlayerSearch> (C-34). Exercises the real interaction paths: the 200ms
 * debounce coalescing keystrokes into a single search, selecting a result firing onSelect + clearing
 * the box, and the empty / error branches. Mocks the FETCH boundary (GET /v1/players/search), not
 * the hook, so the debounce + TanStack query run for real.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import type { PlayerSearchResult } from "../../api/players";
import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../../test-support/behavioral";

import { PlayerSearch } from "./player-search";

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
  it("debounces keystrokes into one search; selecting a result reports the chosen player", async () => {
    const fetchMock = stubFetchRoutes([
      { match: "/v1/players/search", body: [TROUT] },
    ]);
    const onSelect = vi.fn();
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={onSelect} />);

    const input = screen.getByPlaceholderText(/search players/i);
    await user.type(input, "trout");

    // Debounce: the 5 keystrokes coalesce into a SINGLE fetch, for the settled value.
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    expect(fetchMock).toHaveBeenCalledWith(expect.stringContaining("q=trout"));

    // The result renders in the dropdown; select via keyboard (jsdom can't position the Mantine
    // dropdown for a reliable option click - the template uses the same ArrowDown+Enter idiom).
    await screen.findByText(/Mike Trout · CF/i);
    await user.keyboard("{ArrowDown}{Enter}");
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(TROUT));
  });

  it("shows the empty state when the search returns no matches", async () => {
    stubFetchRoutes([{ match: "/v1/players/search", body: [] }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={vi.fn()} />);

    await user.type(screen.getByPlaceholderText(/search players/i), "zzz");
    expect(await screen.findByText(/no players match/i)).toBeInTheDocument();
  });

  it("shows the error state when the search request fails", async () => {
    stubFetchRoutes([{ match: "/v1/players/search", status: 500, body: null }]);
    const user = userEvent.setup({ delay: null });
    renderWithProviders(<PlayerSearch onSelect={vi.fn()} />);

    await user.type(screen.getByPlaceholderText(/search players/i), "trout");
    expect(await screen.findByText(/search unavailable/i)).toBeInTheDocument();
  });
});
