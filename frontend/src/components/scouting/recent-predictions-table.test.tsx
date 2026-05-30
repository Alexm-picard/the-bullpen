/**
 * Unit tests for <RecentPredictionsTable>.
 *
 * Covers header rendering, row rendering, the ✓ / ✗ glyph + <abbr title>
 * a11y pair, the scarlet color on ✗ rows, navy + silver chrome, and the
 * empty-state placeholder.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { MatchupPrediction } from "../../data/matchup-fixtures";
import { colors } from "../../design/tokens";
import { theme } from "../../design/theme";

import { RecentPredictionsTable } from "./recent-predictions-table";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

const SAMPLE: MatchupPrediction[] = [
  {
    when: "2026-05-22T19:14",
    predicted: "K-swinging",
    prob: 0.34,
    actual: "K-swinging",
    agreed: true,
  },
  {
    when: "2026-05-22T19:23",
    predicted: "Single",
    prob: 0.21,
    actual: "HR",
    agreed: false,
  },
];

describe("RecentPredictionsTable", () => {
  it("renders column headers", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html).toContain("When");
    expect(html).toContain("Predicted");
    expect(html).toContain("Actual");
    expect(html).toContain("Agreed");
  });

  it("renders the predicted + actual values", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html).toContain("K-swinging");
    expect(html).toContain("HR");
    expect(html).toContain("Single");
  });

  it("renders ✓ for agreed=true and ✗ for agreed=false", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html).toContain("✓");
    expect(html).toContain("✗");
  });

  it("pairs glyph with an abbr title for a11y", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html).toContain("<abbr");
    expect(html).toContain("Model prediction matched");
    expect(html).toContain("Model prediction did not match");
  });

  it("uses scarlet color on the ✗ glyph", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("uses navy in the header row", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
  });

  it("uses silver in the timestamp column", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html.toLowerCase()).toContain(colors.silver.toLowerCase());
  });

  it("formats the timestamp into a readable string", () => {
    const html = render(<RecentPredictionsTable rows={SAMPLE} />);
    expect(html).toContain("May 22");
  });

  it("renders an empty-state placeholder when no rows", () => {
    const html = render(<RecentPredictionsTable rows={[]} />);
    expect(html).toContain("No recent predictions");
  });

  it("renders the caption when provided", () => {
    const html = render(
      <RecentPredictionsTable rows={SAMPLE} caption="hello caption" />,
    );
    expect(html).toContain("hello caption");
  });
});
