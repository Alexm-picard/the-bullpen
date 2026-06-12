/**
 * Unit tests for <DriftSnapshotGrid>.
 *
 * Covers: the grid is HEADERLESS (the page owns the single "Drift Snapshot"
 * heading; the grid must not duplicate it or its DOM id), both PSI feature
 * rows and ECE output rows render, em-dash for null ECE on non-`in_play`
 * batted_ball cells, only LIVE models get columns (SHADOW / AWAITING are
 * filtered out).
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import {
  ECE_BY_OUTPUT,
  MODEL_FLEET,
  PSI_BY_FEATURE,
} from "../../data/ops-fixtures";
import { theme } from "../../design/theme";

import { DriftSnapshotGrid } from "./drift-snapshot-grid";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("DriftSnapshotGrid", () => {
  it("is headerless - the page owns the section heading and its DOM id", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    expect(html).not.toContain("Drift Snapshot");
    expect(html).not.toContain("ops-drift-section-label");
  });

  it("renders all PSI feature row labels", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    expect(html).toContain("release_speed");
    expect(html).toContain("release_spin");
    expect(html).toContain("pfx_x");
    expect(html).toContain("plate_z");
  });

  it("renders all ECE output row labels", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    expect(html).toContain("ball");
    expect(html).toContain("called_strike");
    expect(html).toContain("swinging_strike");
    expect(html).toContain("foul");
    expect(html).toContain("in_play");
  });

  it("renders an em-dash for null batted_ball ECE cells", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    // ball / called_strike / swinging_strike / foul rows have null batted_ball ECE.
    expect(html).toContain("—");
  });

  it("only shows columns for LIVE models (filters SHADOW/AWAITING)", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    // lr_baseline is SHADOW; should not appear as a drift column.
    expect(html).not.toMatch(/<span[^>]*>lr_baseline<\/span>/);
  });

  it("renders both table captions", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    expect(html).toContain("PSI by feature");
    expect(html).toContain("ECE Δ by output");
  });

  it("uses the ops-drift__pair grid class so CSS can stack at <900px", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    expect(html).toContain('class="ops-drift__pair"');
  });
});
