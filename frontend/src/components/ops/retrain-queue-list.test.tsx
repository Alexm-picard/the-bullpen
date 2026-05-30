/**
 * Unit tests for <RetrainQueueList>.
 *
 * Covers: 3 entries render, AWAITING-PROMOTION wraps in <abbr> with title
 * (color is not the sole carrier), trigger labels appear, the navy header bar
 * is in effect, empty-state path renders the placeholder line.
 */
import { MantineProvider } from "@mantine/core";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { RETRAIN_QUEUE } from "../../data/ops-fixtures";
import { theme } from "../../design/theme";
import { colors } from "../../design/tokens";

import { RetrainQueueList } from "./retrain-queue-list";

function render(ui: React.ReactElement): string {
  return renderToStaticMarkup(
    <MantineProvider theme={theme}>{ui}</MantineProvider>,
  );
}

describe("RetrainQueueList", () => {
  it("renders all three queue entries", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toContain("pitch_outcome_pre v3.3 candidate");
    expect(html).toContain("PSI release_spin = 0.22 (threshold 0.20)");
    expect(html).toContain("weekly cadence");
  });

  it("renders all three trigger labels", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toContain("MANUAL");
    expect(html).toContain("DRIFT");
    expect(html).toContain("SCHEDULE");
  });

  it("renders all three status values", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toContain("AWAITING-PROMOTION");
    expect(html).toContain("RUNNING");
    expect(html).toContain("QUEUED");
  });

  it("wraps AWAITING-PROMOTION in an <abbr> with the rule-6 title", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toMatch(/<abbr title="[^"]*rule 6[^"]*"/);
    expect(html).toContain("Awaiting human promotion gate");
  });

  it("renders the navy header bar", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html.toLowerCase()).toContain(colors.navy.toLowerCase());
    expect(html).toContain("Retrain Queue");
  });

  it("uses scarlet for AWAITING-PROMOTION", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html.toLowerCase()).toContain(colors.scarlet.toLowerCase());
  });

  it("renders queued and scheduled timestamps in mono", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toContain("QUEUED 14:00 ET");
    expect(html).toContain("SCHEDULED 02:00 ET");
  });

  it("renders an empty-state line when entries is empty", () => {
    const html = render(<RetrainQueueList entries={[]} />);
    expect(html).toContain("No retrain jobs in queue");
  });

  it("renders the labelled section landmark", () => {
    const html = render(<RetrainQueueList entries={RETRAIN_QUEUE} />);
    expect(html).toContain('aria-labelledby="retrain-queue-header"');
  });
});
