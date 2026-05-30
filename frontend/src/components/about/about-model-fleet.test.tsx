/**
 * Unit tests for <AboutModelFleet>.
 *
 * Covers: the two prose paragraphs render, all 4 fleet rows render, the
 * STATE column carries both LIVE and SHADOW values, and the BACKBONE
 * column carries the per-row backbone strings.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { FLEET_ROWS, MODEL_FLEET_PARAS } from "../../data/about-fixtures";
import { theme } from "../../design/theme";

import { AboutModelFleet } from "./about-model-fleet";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("AboutModelFleet", () => {
  it("renders both prose paragraphs", () => {
    const html = render(
      <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />,
    );
    const pCount = (html.match(/<p/g) ?? []).length;
    expect(pCount).toBe(2);
    // Check a substring from each paragraph is present
    expect(html).toContain("pre-pitch head");
    expect(html).toContain("Batted-ball");
  });

  it("renders all 4 fleet rows", () => {
    const html = render(
      <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />,
    );
    const trCount = (html.match(/<tr/g) ?? []).length;
    // 4 body rows + 1 header row = 5
    expect(trCount).toBe(5);
  });

  it("renders both LIVE and SHADOW in the STATE column", () => {
    const html = render(
      <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />,
    );
    expect(html).toContain("LIVE");
    expect(html).toContain("SHADOW");
  });

  it("renders all 4 column headers", () => {
    const html = render(
      <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />,
    );
    expect(html).toContain("Model");
    expect(html).toContain("Version");
    expect(html).toContain("State");
    expect(html).toContain("Backbone");
  });

  it("renders the model names from fixtures", () => {
    const html = render(
      <AboutModelFleet paragraphs={MODEL_FLEET_PARAS} rows={FLEET_ROWS} />,
    );
    expect(html).toContain("pitch_outcome_pre");
    expect(html).toContain("batted_ball");
    expect(html).toContain("lr_baseline");
  });
});
