/**
 * Unit tests for <DriftSnapshotGrid>.
 *
 * Covers: the grid is HEADERLESS (the page owns the single "Drift Snapshot"
 * heading; the grid must not duplicate it or its DOM id), the PSI feature
 * rows render the REAL request-key watchlist (E-4: camelCase request-DTO
 * names - what the PSI job actually writes), the ECE table renders the "all"
 * segment (what CalibrationJob actually writes) with an em-dash for the
 * offline-calibrated battedball cell, only LIVE models get columns
 * (SHADOW / AWAITING are filtered out).
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
    // The E-1 request keys (camelCase, exactly what the live PSI rows carry).
    expect(html).toContain("launchSpeedMph");
    expect(html).toContain("hitDistanceFt");
    expect(html).toContain("releaseSpeedMph");
    expect(html).toContain("spinRateRpm");
  });

  it("renders the ECE segment row CalibrationJob actually writes", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    // One CALIBRATION_ERROR row per model at segment "all" - not per-outcome.
    expect(html).toContain(">all<");
  });

  it("renders an em-dash for the offline-calibrated battedball ECE cell", () => {
    const html = render(
      <DriftSnapshotGrid
        models={MODEL_FLEET}
        psiByFeature={PSI_BY_FEATURE}
        eceByOutput={ECE_BY_OUTPUT}
      />,
    );
    // battedball_outcome has no live calibration lane (offline by design).
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
    expect(html).toContain("ECE by segment");
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
