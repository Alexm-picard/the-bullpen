/**
 * Static-render smoke for the FRONT PAGE ([195]). A static render leaves every query in flight, so
 * this pins the loading-first contract (D3): one h1, no showcase flash, the colophon. The settled
 * states (pre-game, live prior, no games, offline showcase) run against stubbed fetches in
 * home-page.interaction.test.tsx.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import HomePage from "./home-page";

function render(node: ReactNode): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MantineProvider theme={theme}>
        <MemoryRouter initialEntries={["/"]}>{node}</MemoryRouter>
      </MantineProvider>
    </QueryClientProvider>,
  );
}

describe("HomePage (front page, loading-first)", () => {
  it("renders on the editorial ground, not the broadcast field", () => {
    const html = render(<HomePage />);
    expect(html).toContain('class="ed-page"');
    expect(html).not.toContain("var(--bp-field)");
  });

  it("renders exactly one h1", () => {
    const html = render(<HomePage />);
    expect((html.match(/<h1\b/g) ?? []).length).toBe(1);
  });

  it("never flashes showcase content while the slate is still in flight (D3)", () => {
    const html = render(<HomePage />);
    expect(html).toContain("Slate loading");
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain("Showcase data");
    expect(html).not.toContain("Skubal"); // the showcase featured matchup
  });

  it("does not put the prior above the figures before there is a live prior", () => {
    const html = render(<HomePage />);
    expect(html).toContain('data-live="false"');
  });

  it("renders the colophon with the build stamp", () => {
    const html = render(<HomePage />);
    expect(html).toContain("The Bullpen. Self-hosted, honestly scored.");
    expect(html).toMatch(/build [^<]+/);
  });
});
