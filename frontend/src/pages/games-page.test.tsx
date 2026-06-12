/**
 * Page-level smoke test for /games on the BROADCAST identity (redesign PR-3,
 * decision [160]). Narrow by design: the page wires `useTodaysGames()` live,
 * so we assert the chrome renders, the one-h1 rule holds, and the loading
 * state shows. Data states live in todays-slate-table.test.tsx; the e2e suite
 * covers the network-mocked empty + populated flows.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { colors } from "../design/broadcast";
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

describe("GamesPage (broadcast slate)", () => {
  it("renders the light field under broadcast chrome", () => {
    const html = render(<GamesPage />);
    expect(html.toLowerCase()).toContain(colors.field.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders exactly one h1 (the slate masthead)", () => {
    const html = render(<GamesPage />);
    const matches = html.match(/<h1\b/g) ?? [];
    expect(matches.length).toBe(1);
    expect(html).toContain("Games");
  });

  it("renders the slate lower-third and the loading state initially", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Slate");
    expect(html).toContain("Loading today");
  });

  it("renders the chrome footer", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("THE BULLPEN · LIVE SLATE");
  });
});
