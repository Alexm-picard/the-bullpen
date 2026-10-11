/**
 * /about static render (SPEC-guide-about). The registry query is left loading (static render), so
 * the champion figure shows its fallback; the decision and ADR counts come from the vite define the
 * build injects. Assertions run on visibleText().
 */
import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ADR_COUNT, BUILD_SHA, DECISION_COUNT } from "../build-info";
import { REPO_URL, REVERSED, SET_ASIDE, STACK_ROWS } from "../data/about-copy";
import { theme } from "../design/theme";
import { visibleText } from "../test-support/visible-text";

async function render(): Promise<string> {
  const { default: AboutPage } = await import("./about-page");
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <MantineProvider theme={theme}>
          <AboutPage />
        </MantineProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function section(html: string, labelledBy: string): string {
  const at = html.indexOf(`aria-labelledby="${labelledBy}"`);
  expect(at).toBeGreaterThan(-1);
  return html.slice(at, html.indexOf("</section>", at));
}

describe("AboutPage", () => {
  it("is the back page: one h1, the colophon dateline, the build", async () => {
    const html = await render();
    const text = visibleText(html);
    expect((html.match(/<h1/g) ?? []).length).toBe(1);
    expect(text).toContain("How this was built, and what was left out");
    expect(text).toContain("Colophon");
    expect(text).toContain(BUILD_SHA);
  });

  it("prints the build-time decision and ADR counts with where they were counted", async () => {
    expect(DECISION_COUNT).not.toBeNull();
    expect(ADR_COUNT).not.toBeNull();
    const record = visibleText(section(await render(), "ab-record-label"));
    expect(record).toContain(String(DECISION_COUNT));
    expect(record).toContain("Counted from docs/decisions.md");
    expect(record).toContain(String(ADR_COUNT));
    expect(record).toContain("Counted from docs/adr");
    expect(record).not.toMatch(/CV folds/i);
  });

  it("the stack is current: React 19, Mantine 9 + Tailwind 4, the Front Page identity", async () => {
    const stack = visibleText(section(await render(), "ab-stack-title"));
    expect(stack).toContain("React 19");
    expect(stack).not.toContain("React 18");
    expect(stack).toContain("Mantine 9, Tailwind 4");
    expect(stack).toContain("Front Page identity");
    expect(stack).not.toMatch(/scouting-report/i);
    for (const r of STACK_ROWS) expect(stack).toContain(r.decidedIn);
  });

  it("a reversed refusal is listed as reversed, never as still rejected", async () => {
    const html = await render();
    const kept = SET_ASIDE.map((k) => k.what.toLowerCase()).join(" | ");
    expect(kept).not.toMatch(/dark mode|night edition|editorial/);
    const reversed = visibleText(section(html, "ab-rev-label"));
    for (const r of REVERSED) expect(reversed).toContain(r.what);
    expect(reversed).toContain("[192]");
  });

  it("links the real repository and the season's postmortem, with no arrow glyph", async () => {
    const html = await render();
    expect(html).toContain(`href="${REPO_URL}"`);
    expect(html).toContain(
      "docs/postmortems/2026-08-03_pitcher-form-silent-staleness.md",
    );
    expect(html).not.toContain("placeholder");
    expect(visibleText(html)).not.toMatch(/[→↗]/);
  });

  it("never shows the retired showcase ribbon or fixture fleet", async () => {
    const text = visibleText(await render());
    expect(text).not.toMatch(/not a live count|Showcase data/i);
    expect(text).not.toMatch(/v3\.2|Edition v0\.4/);
  });
});

describe("AboutPage, when the build could not read docs/", () => {
  afterEach(() => {
    vi.doUnmock("../build-info");
    vi.resetModules();
  });

  it("prints an em-dash and says it was not counted, never a stale number", async () => {
    vi.resetModules();
    vi.doMock("../build-info", () => ({
      BUILD_SHA: "abc1234",
      BUILD_DATE: "2026-10-11",
      DECISION_COUNT: null,
      ADR_COUNT: null,
    }));
    const record = visibleText(section(await render(), "ab-record-label"));
    expect(record).toContain("—");
    expect(record).toContain("Not counted in this build");
    expect(record).not.toMatch(/\b20\d\b/);
  });
});
