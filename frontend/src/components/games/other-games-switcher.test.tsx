/**
 * Unit tests for <OtherGamesSwitcher>.
 *
 * Covers chip rendering (team abbrs, state line, href), empty-state
 * placeholder, navy header chrome on each chip, and the aria-label that
 * marks the strip as a navigation landmark.
 *
 * The Link component requires a Router context; renderToStaticMarkup is
 * happy with MemoryRouter, mirroring the matchup-header test's strategy
 * for components that touch react-router-dom.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import type { OtherGameChip } from "../../data/games-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { OtherGamesSwitcher } from "./other-games-switcher";

const CHIPS: OtherGameChip[] = [
  {
    id: "laa-hou-2026-05-30",
    away: "LAA",
    home: "HOU",
    state: "TOP 8TH · 2–1",
    href: "/games/laa-hou-2026-05-30",
  },
  {
    id: "nym-phi-2026-05-30",
    away: "NYM",
    home: "PHI",
    state: "BOT 6TH · 3–3",
    href: "/games/nym-phi-2026-05-30",
  },
];

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>
      <MemoryRouter>{ui}</MemoryRouter>
    </MantineProvider>,
  );
}

describe("OtherGamesSwitcher", () => {
  it("renders one chip per game with team abbrs and state line", () => {
    const html = render(<OtherGamesSwitcher chips={CHIPS} />);
    expect(html).toContain("LAA @ HOU");
    expect(html).toContain("TOP 8TH · 2–1");
    expect(html).toContain("NYM @ PHI");
    expect(html).toContain("BOT 6TH · 3–3");
  });

  it("renders chip hrefs that route to /games/{id}", () => {
    const html = render(<OtherGamesSwitcher chips={CHIPS} />);
    expect(html).toContain('href="/games/laa-hou-2026-05-30"');
    expect(html).toContain('href="/games/nym-phi-2026-05-30"');
  });

  it("uses navy on the chip headers", () => {
    const html = render(<OtherGamesSwitcher chips={CHIPS} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("marks the strip as a navigation landmark", () => {
    const html = render(<OtherGamesSwitcher chips={CHIPS} />);
    expect(html).toContain('aria-label="Other live games tonight"');
  });

  it("renders an empty-state placeholder when there are no chips", () => {
    const html = render(<OtherGamesSwitcher chips={[]} />);
    expect(html).toContain("No other live games");
  });
});
