/**
 * PlayerProfilePage (B2) - the live prediction_log-backed sections. Rendered with
 * no route param, so the per-player queries stay disabled (enabled: id != null)
 * and the page falls to its first-class empty state - the common case until the
 * pitch model serves this player live. That state is exactly what we assert.
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import { PlayerProfilePage } from "./players-page";

function render(ui: React.ReactElement): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>{ui}</MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PlayerProfilePage", () => {
  it("renders the first-class empty state when there is no prediction history", () => {
    const html = render(<PlayerProfilePage />);
    // The section scaffolds still render...
    expect(html).toContain("Recent Predictions");
    expect(html).toContain("Calibration");
    // ...and each falls to its designed empty state, not an error or blank table.
    expect(html).toContain("No settled predictions for this player yet");
    expect(html).toContain("No calibration data yet");
  });
});
