/**
 * Smoke test for /accuracy (Phase 3 PR-gamma).
 *
 * Renders the full page inside MemoryRouter + MantineProvider +
 * QueryClientProvider (cloning the about-page harness) and asserts:
 *   - exactly one <h1>;
 *   - both LowerThird section labels appear;
 *   - the OFFLINE honesty line is in the markup;
 *   - when both queries resolve EMPTY (no scorecard rows, backfill 204 -> null),
 *     BOTH honest empty states render and NO fabricated number appears.
 *
 * The cache is seeded synchronously via setQueryData so the queries are NOT in
 * the loading state during the static render: data is present-but-empty, which
 * drives the page's honest empty-state branches (the same shape the live
 * endpoints take before any evidence/artifact exists).
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import AccuracyPage from "./accuracy-page";

function renderEmpty(): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // Seed both queries as resolved-empty (the pre-evidence / 204 reality).
  client.setQueryData(["ops", "accuracy"], []);
  client.setQueryData(["ops", "backfill-accuracy"], null);
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>
          <AccuracyPage />
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AccuracyPage", () => {
  it("has exactly one <h1> on the page", () => {
    const html = renderEmpty();
    const h1Count = (html.match(/<h1/g) ?? []).length;
    expect(h1Count).toBe(1);
  });

  it("renders both LowerThird section labels", () => {
    const html = renderEmpty();
    expect(html).toContain("Held-Out Scorecard");
    expect(html).toContain("Batted-Ball Backfill");
  });

  it("renders the OFFLINE honesty sub-line", () => {
    const html = renderEmpty();
    expect(html).toContain(
      "Offline rolling-origin CV on held-out folds - not live game accuracy.",
    );
  });

  it("renders both honest empty states when the data is empty", () => {
    const html = renderEmpty();
    expect(html).toContain("No held-out scorecard yet");
    expect(html).toContain("Backfill not served yet");
  });

  it("shows no fabricated metric numbers in the empty state", () => {
    const html = renderEmpty();
    // Assert against VISIBLE TEXT only: strip Mantine's injected <style> blocks
    // and every inline style="..." attribute (both carry token decimals like
    // letter-spacing 0.06em and rgba opacities that are not page data), then
    // strip remaining tags. The empty state must never invent metric values, so
    // no metric-shaped decimal (Brier/ECE 0.xxx) and no percentage may survive.
    const text = html
      .replace(/<style[\s\S]*?<\/style>/g, "")
      .replace(/style="[^"]*"/g, "")
      .replace(/<[^>]+>/g, " ");
    expect(text).not.toMatch(/\b0\.\d{2,3}\b/);
    expect(text).not.toMatch(/\d+\.\d+%/);
  });
});
