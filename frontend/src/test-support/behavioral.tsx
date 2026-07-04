/**
 * Shared scaffolding for behavioral (render + userEvent) frontend tests (C-34).
 *
 * The vitest suite default environment is node, so each behavioral test file must still declare
 * `// @vitest-environment jsdom` at the top. This module provides the three repetitive, error-prone
 * pieces: the Mantine jsdom shims, a provider-wrapped render (the app's real client-state stack),
 * and a fetch-boundary stub. Per the C-34 rule we mock at the FETCH boundary (every api/*.ts bottoms
 * out at `fetch(API_BASE + path)`), not at the hook, so the query + debounce + fixture-fallback
 * logic runs for real.
 *
 * Not a test file (no `.test` suffix) so vitest does not collect it; it is imported only by test
 * files, so the app build tree-shakes it out.
 */
import "@testing-library/jest-dom/vitest";

import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";
import { MemoryRouter } from "react-router-dom";
import { vi } from "vitest";

import { theme } from "../design/theme";

/** Mantine (Autocomplete / Select / SegmentedControl) reaches for browser APIs jsdom omits. */
export function installMantineShims(): void {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}

/** Fresh QueryClient (retry off, so an error state resolves in one tick) + the app Mantine theme. */
export function renderWithProviders(ui: ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <MantineProvider theme={theme}>{ui}</MantineProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

export type StubRoute = { match: string; status?: number; body: unknown };

/**
 * Stub the global fetch, routing by URL substring (first match wins). Returns the mock so a test can
 * assert call count / args. Call `vi.unstubAllGlobals()` in afterEach. An unmatched URL throws, so a
 * test that forgets to stub a route fails loudly rather than hanging.
 */
export function stubFetchRoutes(routes: StubRoute[]) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = typeof input === "string" ? input : input.toString();
    const route = routes.find((r) => url.includes(r.match));
    if (!route) {
      throw new Error(`stubFetchRoutes: no stubbed route for ${url}`);
    }
    const status = route.status ?? 200;
    return {
      ok: status < 400,
      status,
      json: async () => route.body,
    } as Response;
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}
