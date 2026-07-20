/**
 * Page-level smoke test for /home on the BROADCAST identity (redesign PR-4,
 * decision [160]). Narrow: the fleet strip wires live registry hooks, so we
 * assert chrome, the one-h1 rule, the fixture sections, and the honest
 * showcase captions. Component behavior lives in the per-component tests.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { colors } from "../design/broadcast";
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

describe("HomePage (broadcast)", () => {
  it("renders the light field under broadcast chrome", () => {
    const html = render(<HomePage />);
    expect(html.toLowerCase()).toContain(colors.field.toLowerCase());
    expect(html.toLowerCase()).toContain(colors.chrome.toLowerCase());
  });

  it("renders exactly one h1 (the slate masthead)", () => {
    const html = render(<HomePage />);
    const matches = html.match(/<h1\b/g) ?? [];
    expect(matches.length).toBe(1);
    expect(html).toContain("Slate");
  });

  it("renders loading-first on a cold load - never the showcase flash (D3)", () => {
    // A static render leaves every query in-flight: the fleet strip and the matchups board must
    // read as LOADING, and no section may claim "showcase data (backend unreachable)" yet. The
    // settled-empty showcase fallback (with its honest captions) is unchanged and exercised by the
    // interaction path once queries resolve empty.
    const html = render(<HomePage />);
    expect(html).toContain("Matchups");
    expect(html).toContain("Model fleet · loading");
    expect(html).toMatch(/Loading tonight.s matchups/);
    expect(html).toContain("LOADING");
    expect(html).not.toContain("showcase data");
    expect(html).not.toContain("Featured Matchup"); // no fixture flash mid-load
  });

  it("renders the chrome footer", () => {
    const html = render(<HomePage />);
    expect(html).toContain("THE BULLPEN");
  });
});
