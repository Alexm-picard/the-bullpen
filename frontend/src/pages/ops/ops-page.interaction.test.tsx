// @vitest-environment jsdom
/**
 * Behavioral test for <OpsPage> (C-34). The dashboard is live-with-fixture-fallback: each section
 * derives from a /v1/ops/* query and falls back to a showcase fixture (with an honest
 * "backend unreachable" caption) when the live query returns empty. This asserts that real
 * derivation by driving the fetch boundary to empty and checking the fallback captions render -
 * not the hooks, so the live-vs-fixture logic runs for real.
 */
import "@testing-library/jest-dom/vitest";

import { cleanup, screen } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

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

describe("OpsPage (fixture fallback)", () => {
  it("falls back to showcase data with an honest caption when the live ops endpoints return empty", async () => {
    // One substring catch-all: every /v1/ops/* GET resolves to an empty list -> every section
    // takes its fixture fallback.
    stubFetchRoutes([{ match: "/v1/ops", body: [] }]);
    renderWithProviders(<OpsPage />);

    const fallbackCaptions = await screen.findAllByText(
      /showcase data \(backend unreachable\)/i,
    );
    expect(fallbackCaptions.length).toBeGreaterThan(0);
  });
});
