import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { theme } from "../../design/theme";

import { LeagueLeaderStrip, type LeagueLeader } from "./league-leader-strip";

const LEADER: LeagueLeader = {
  parkId: "COL",
  name: "Coors Field",
  probHr: 0.512,
  shortFenceFt: 347,
  centerFenceFt: 415,
  deepestFenceFt: 415,
};

function render(node: ReactNode): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{node}</MantineProvider>,
  );
}

describe("LeagueLeaderStrip", () => {
  it("renders the leader park name and id", () => {
    const html = render(<LeagueLeaderStrip leader={LEADER} />);
    expect(html).toContain("Coors Field");
    expect(html).toContain("COL");
  });

  it("renders the probability as a percentage with one decimal", () => {
    const html = render(<LeagueLeaderStrip leader={LEADER} />);
    expect(html).toContain("51.2%");
  });

  it("renders the fence depth signature", () => {
    const html = render(<LeagueLeaderStrip leader={LEADER} />);
    expect(html).toContain("LF 347");
    expect(html).toContain("CF 415");
    expect(html).toContain("RF 415");
  });

  it("renders a thin probability bar", () => {
    const html = render(<LeagueLeaderStrip leader={LEADER} />);
    expect(html).toContain('role="progressbar"');
  });

  it("renders an em-dash placeholder when leader is null and not loading", () => {
    const html = render(<LeagueLeaderStrip leader={null} />);
    expect(html).toContain("—");
    expect(html).toContain("no realistic HR scenario");
  });

  it("renders a computing message when leader is null and loading", () => {
    const html = render(<LeagueLeaderStrip leader={null} isLoading />);
    expect(html).toContain("computing leader");
  });

  it("includes a labelled region landmark", () => {
    const html = render(<LeagueLeaderStrip leader={LEADER} />);
    expect(html).toContain('role="region"');
    expect(html).toContain("aria-label=");
  });
});
