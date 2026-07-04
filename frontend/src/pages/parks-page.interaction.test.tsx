// @vitest-environment jsdom
/**
 * Behavioral test for <ParksPage> (C-34). The launch-condition controls feed a 300ms-debounced
 * all-parks prediction; changing an input re-keys the query and refetches, which is what recolors
 * the heatmap. Asserts the real chain: an initial prediction fires on load, and flipping the bat
 * side triggers a refetch carrying the new `stand`. Mocks the fetch boundary (POST /all-parks).
 *
 * The static content coverage (section labels, 30 park abbreviations, methodology) stays in the
 * smoke suite parks-page.test.tsx - per plan task 12, static renders remain as smoke.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

import {
  installMantineShims,
  renderWithProviders,
  stubFetchRoutes,
} from "../test-support/behavioral";

import ParksPage from "./parks-page";

const RESPONSE = {
  probHrByPark: { COL: 0.42, NYY: 0.31 },
  modelName: "battedball_outcome",
  modelVersion: "v2",
  latencyMicros: 1200,
  correlationId: "test",
};

beforeAll(installMantineShims);

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("ParksPage (interaction)", () => {
  it("predicts on load and refetches with the new bat side when the toggle is flipped", async () => {
    const fetchMock = stubFetchRoutes([
      { match: "/v1/predict/batted-ball/all-parks", body: RESPONSE },
    ]);
    const user = userEvent.setup();
    renderWithProviders(<ParksPage />);

    // The debounced prediction fires on load with the default RHB.
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/all-parks"),
        expect.objectContaining({
          body: expect.stringContaining('"stand":"R"'),
        }),
      ),
    );
    // The live caption renders the served model identity once data arrives.
    expect(
      await screen.findByText(/battedball_outcome v2/i),
    ).toBeInTheDocument();

    // Flip the bat side to LHB -> after the 300ms debounce, a refetch carrying stand:"L".
    await user.click(screen.getByRole("radio", { name: "LHB" }));
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/all-parks"),
        expect.objectContaining({
          body: expect.stringContaining('"stand":"L"'),
        }),
      ),
    );
  });
});
