/**
 * Static-render smoke for /games ([195], SPEC-games-404). A static render leaves both queries in
 * flight, so this pins the loading-first contract: the editorial ground, one h1, the facets, the
 * pre-sized shell, and no showcase flash. The settled postures run against stubbed fetches in
 * games-page.interaction.test.tsx.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { RATING_CLAIM } from "../data/games-claims";
import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

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

describe("GamesPage (scoreboard, loading-first)", () => {
  it("renders on the editorial ground, not the broadcast field", () => {
    const html = render(<GamesPage />);
    expect(html).toContain('class="ed-page"');
    // Whole document, Mantine's injected theme <style> included: the broadcast palette is retired.
    expect(html).not.toContain("--bp-");
  });

  it("renders exactly one h1", () => {
    const html = render(<GamesPage />);
    expect((html.match(/<h1\b/g) ?? []).length).toBe(1);
  });

  it("shows the pre-sized shell, and no showcase, while the slate is in flight", () => {
    const html = render(<GamesPage />);
    const text = visibleText(html);
    expect(html).toContain('aria-busy="true"');
    expect(text).toContain("Slate loading");
    expect(text).not.toContain("Showcase");
    expect(text).not.toContain("Skubal");
  });

  it("names the status facets All, Live, Scheduled and Final", () => {
    const html = render(<GamesPage />);
    const text = visibleText(html);
    expect(html).toContain('aria-label="Filter games by status"');
    for (const label of ["All", "Live", "Scheduled", "Final"]) {
      expect(text).toContain(label);
    }
    expect(text).not.toContain("Completed");
  });

  it("prints the rating claim verbatim from its SHELF-marked source, in both emitters", () => {
    const html = render(<GamesPage />);
    // Scoped per emitter: the desktop aside and the phone disclosure BOTH print the claim, so a
    // page-wide toContain would stay green if either one lost it (pins decay by accretion).
    const between = (open: string, close: string) => {
      const at = html.indexOf(open);
      expect(at, `missing ${open}`).toBeGreaterThanOrEqual(0);
      return visibleText(html.slice(at, html.indexOf(close, at)));
    };
    expect(between('<aside class="gm-holds gm-aside"', "</aside>")).toContain(
      RATING_CLAIM,
    );
    expect(between('<details class="gm-key-sum"', "</details>")).toContain(
      RATING_CLAIM,
    );
  });

  it("renders the colophon with the build stamp", () => {
    const text = visibleText(render(<GamesPage />));
    expect(text).toContain("The Bullpen. Self-hosted, honestly scored.");
    expect(text).toMatch(/build \S+/);
  });
});
