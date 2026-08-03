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
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { theme } from "../design/theme";

import AccuracyPage from "./accuracy-page";

function renderEmpty(): string {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  // Seed all three queries as resolved-empty (the pre-evidence / 204 reality).
  // The live scorecard seeds ALL-no-truth: the honest pre-volume state, which
  // must render reasons and never a fabricated %.
  client.setQueryData(["ops", "accuracy"], []);
  client.setQueryData(["ops", "backfill-accuracy"], null);
  client.setQueryData(["ops", "rolling-accuracy"], {
    windowDays: 7,
    generatedAt: "2026-08-03T00:00:00Z",
    models: [
      "pitch_outcome_pre",
      "pitch_outcome_post",
      "battedball_outcome",
      "pitch_type_pre",
    ].map((modelName) => ({
      modelName,
      status: "no_live_truth",
      reason: "no truth-joined volume in this environment",
      top1: null,
      n: null,
      buckets: null,
      note: null,
    })),
  });
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

  it("renders all three LowerThird section labels", () => {
    const html = renderEmpty();
    expect(html).toContain("Live Scorecard (rolling 7d)");
    expect(html).toContain("Held-Out Scorecard");
    expect(html).toContain("Batted-Ball Backfill");
  });

  it("keeps the live and OFFLINE surfaces as separate sections", () => {
    // The page's whole integrity is the hard separation: the live section is
    // its own <section> ABOVE the offline one, never one table mixing both.
    const html = renderEmpty();
    const liveIdx = html.indexOf("Live Scorecard (rolling 7d)");
    const offlineIdx = html.indexOf("Held-Out Scorecard");
    expect(liveIdx).toBeGreaterThan(-1);
    expect(offlineIdx).toBeGreaterThan(liveIdx);
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
