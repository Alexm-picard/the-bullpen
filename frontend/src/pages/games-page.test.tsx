/**
 * Page-level smoke test for /games - the LIVE slate (FE-H1).
 *
 * The page wires `useTodaysGames()` against the live backend, so coverage
 * here is intentionally narrow (same posture as game-page.test.tsx): the
 * report-sheet shell + header render, the one-h1 constraint holds, and the
 * initial query state shows the loading copy. Data-driven states (rows /
 * empty) are covered by todays-slate-table.test.tsx as a pure component and
 * by the Playwright e2e at the network layer.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import GamesPage from "./games-page";

function render(node: ReactNode): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MantineProvider theme={theme}>
        <MemoryRouter initialEntries={["/games"]}>{node}</MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

describe("GamesPage (live slate)", () => {
  it("renders the report-sheet shell with the live-slate masthead", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("report-sheet__shell");
    expect(html).toContain("Live Slate");
    expect(html).toContain("Games");
  });

  it("renders exactly one <h1>", () => {
    const html = render(<GamesPage />);
    const matches = html.match(/<h1\b/g) ?? [];
    expect(matches.length).toBe(1);
  });

  it("renders the slate section label and the loading state initially", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Slate");
    expect(html).toContain("Loading today");
  });

  it("renders the colophon footer", () => {
    const html = render(<GamesPage />);
    expect(html.toLowerCase()).toContain("the bullpen");
  });
});
