/**
 * <BrowsePlayers> - static render covers the pills + the quiet initial state
 * (no facet picked = no roster fetch, no results block). The pick -> roster
 * fetch -> results interaction is covered by the e2e pass (no DOM-interaction
 * lib here).
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { TEAM_ABBREVIATIONS, teamColor } from "../../design/teamColors";

import { BrowsePlayers } from "./browse-players";

function render(node: ReactNode): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("BrowsePlayers", () => {
  it("renders both facets with position + all-30 team pills", () => {
    const html = render(<BrowsePlayers />);
    expect(html).toContain("Browse");
    expect(html).toContain("By position");
    expect(html).toContain("By team");
    // MLB primary positions actually stored (P, not SP/RP)
    expect(html).toContain(">1B</button>");
    expect(html).toContain(">SS</button>");
    expect(html).toContain(">P</button>");
    // every team renders, with team color as a dot fill (never text)
    for (const t of TEAM_ABBREVIATIONS) {
      expect(html).toContain(t);
    }
    expect(html).toContain(teamColor("NYY"));
  });

  it("is quiet until a facet is picked (no fetch, no results block)", () => {
    const html = render(<BrowsePlayers />);
    expect(html).not.toContain("No active players");
    expect(html).not.toContain("Loading");
    expect(html).not.toContain("unavailable");
  });
});
