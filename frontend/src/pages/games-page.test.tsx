/**
 * Page-level smoke test for /games (the Live Game variant of the Matchup
 * Report, Stage 3d).
 *
 * Strategy mirrors the prior stages: render to static markup with the
 * MantineProvider + MemoryRouter; assert presence of the six section labels
 * and the masthead h1, plus the one-h1-only constraint that the cover-sheet
 * pattern requires.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import GamesPage from "./games-page";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("GamesPage", () => {
  it("renders the masthead nameplate", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Live Game");
    expect(html).toContain("NYY @ DET");
  });

  it("renders exactly one <h1> (the masthead)", () => {
    const html = render(<GamesPage />);
    const matches = html.match(/<h1\b/g) ?? [];
    expect(matches.length).toBe(1);
  });

  it("renders all five primary section labels", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Pitch Log");
    expect(html).toContain("Now Batting");
    expect(html).toContain("Agreement By Inning");
    expect(html).toContain("Other Games");
  });

  it("renders the game state strip with all five cells", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Inning");
    expect(html).toContain("Score");
    expect(html).toContain("Count");
    expect(html).toContain("Runners");
    expect(html).toContain("Model Agr");
  });

  it("renders the navy footer with build SHA + date", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("stage3d");
    expect(html).toContain("2026.05.30");
  });

  it("renders the now-batting pair with both players", () => {
    const html = render(<GamesPage />);
    expect(html).toContain("Aaron Judge");
    expect(html).toContain("Tarik Skubal");
  });

  it("renders the other-games switcher with at least one other game", () => {
    const html = render(<GamesPage />);
    // The first chip in the fixture is LAA @ HOU.
    expect(html).toContain("LAA @ HOU");
  });
});
