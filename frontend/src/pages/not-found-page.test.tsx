/**
 * The catch-all 404 ([195], SPEC-games-404 §7). The route wiring (path="*" -> NotFoundPage) is
 * asserted end-to-end in e2e/smoke.spec.ts.
 */
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { describe, expect, it } from "vitest";

import { visibleText } from "../test-support/visible-text";

import NotFoundPage from "./not-found-page";
import { echoPath, PATH_ECHO_MAX } from "./not-found-path";

function render(path: string): string {
  return renderToStaticMarkup(
    <MemoryRouter initialEntries={[path]}>
      <NotFoundPage />
    </MemoryRouter>,
  );
}

describe("NotFoundPage", () => {
  it("renders the code, the line, and a way home on the editorial ground", () => {
    const html = render("/nope");
    const text = visibleText(html);
    expect(html).toContain('class="ed-page"');
    expect(text).toContain("404");
    expect(text).toContain("No play at this base.");
    expect(text).toContain("Back to home");
    expect(html).toMatch(/<a[^>]*class="ed-link"[^>]*href="\/"/);
    expect((html.match(/<h1\b/g) ?? []).length).toBe(1);
  });

  it("echoes the path the reader asked for", () => {
    expect(visibleText(render("/players/ohtani-shohei"))).toContain(
      "/players/ohtani-shohei",
    );
  });

  it("clips a very long path", () => {
    const long = `/${"x".repeat(200)}`;
    expect(echoPath(long)).toBe(`${long.slice(0, PATH_ECHO_MAX)}...`);
    expect(echoPath("/short")).toBe("/short");
  });

  it("offers four places to go instead", () => {
    const html = render("/nope");
    for (const href of ["/", "/games", "/players", "/accuracy"]) {
      expect(html).toContain(`href="${href}"`);
    }
    expect(visibleText(html)).toContain("Where to go instead");
  });
});
