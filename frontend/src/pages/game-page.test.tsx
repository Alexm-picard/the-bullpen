/**
 * Smoke tests for /games/:id on the BROADCAST identity (redesign PR-2,
 * decision [160]). Same narrow posture as before: the page wires real
 * TanStack hooks, so we assert the shell + chrome render, the one-h1 rule
 * holds, and the invalid-id contract survives (the e2e suite depends on its
 * exact text). Data-driven states live in live-pitch-board.test.tsx.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { colors } from "../design/broadcast";
import { theme } from "../design/theme";

import { GamePage } from "./game-page";

function render(node: ReactNode, initialPath: string): string {
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

describe("GamePage (broadcast identity)", () => {
  it("renders the light field under broadcast chrome", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html.toLowerCase()).toContain(colors.field.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders exactly one h1 (the matchup masthead)", () => {
    const html = render(<GamePage />, "/games/12345");
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders the scorebug as a status element and the pitch-log lower third", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain('role="status"');
    expect(html).toContain("Live Pitch Log");
  });

  it("keeps the honest champion-less context line ([154])", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("pitch model pending");
  });

  it("renders the chrome footer", () => {
    const html = render(<GamePage />, "/games/12345");
    expect(html).toContain("THE BULLPEN · LIVE GAME");
  });

  it("renders the invalid-id message when :id is non-numeric (e2e contract text)", () => {
    const html = render(<GamePage />, "/games/not-a-number");
    expect(html).toContain("Invalid game id.");
  });
});
