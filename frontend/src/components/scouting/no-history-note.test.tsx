import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { NoHistoryNote } from "./no-history-note";

describe("NoHistoryNote", () => {
  it("renders the default title and the explanation", () => {
    const html = renderToStaticMarkup(
      <NoHistoryNote>
        Predictions accrue here as the model serves live.
      </NoHistoryNote>,
    );
    expect(html).toContain("No prediction history yet");
    expect(html).toContain("Predictions accrue here as the model serves live.");
  });

  it("accepts a custom title", () => {
    const html = renderToStaticMarkup(
      <NoHistoryNote title="No calibration data yet">
        bins appear later
      </NoHistoryNote>,
    );
    expect(html).toContain("No calibration data yet");
    expect(html).toContain("bins appear later");
  });
});
