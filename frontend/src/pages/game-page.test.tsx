/**
 * Smoke tests for /games/:id GamePage on the scouting-report identity.
 *
 * The page wires real TanStack Query hooks (`useGame`, `useLivePitches`)
 * against the live backend, so test coverage here is intentionally narrow:
 * confirm the page renders inside the ReportSheet shell with the scouting-
 * report chrome present, and degrades gracefully when the id is invalid.
 * Full behavior of live polling is exercised by the api/games.ts unit
 * tests; this file just makes sure the leaf rebuild didn't regress
 * identity application.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";
import { colors } from "../design/tokens";

import { GamePage } from "./game-page";

function render(node: ReactNode, initialPath: string): string {
  // Disable TanStack retries so the first error settles immediately and
  // the SSR snapshot stays stable.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MantineProvider theme={theme}>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/games/:id" element={node} />
          </Routes>
        </MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

describe("GamePage", () => {
  it("renders the report-sheet shell with scouting chrome at a valid id", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("report-sheet__shell");
    expect(html).toContain("report-sheet__corner");
    expect(html.toLowerCase()).toContain(colors.bgSheet.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("renders the masthead eyebrow + a single h1", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("Live Game");
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders the live pitch log section label", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("Live Pitch Log");
  });

  it("renders the colophon footer at the bottom", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html.toLowerCase()).toContain("the bullpen");
  });

  it("renders an invalid-id error message when :id is non-numeric", () => {
    const html = render(<GamePage />, "/games/not-a-number");
    expect(html).toContain("Invalid game id");
  });
});
