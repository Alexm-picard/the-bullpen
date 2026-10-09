/**
 * WCAG contrast of the editorial palettes, computed from the values actually shipped in
 * editorial.css (parsed, not restated), for BOTH editions. Text needs 4.5:1 (1.4.3); the
 * probability bars use ink2 and need 3:1 as meaningful graphics (1.4.11).
 */
/// <reference types="node" />
// Read from disk: vitest does not process CSS, so a `?raw` import arrives empty here.
import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const css = readFileSync(new URL("./editorial.css", import.meta.url), "utf8");

function block(selectorStart: string): Record<string, string> {
  const at = css.indexOf(selectorStart);
  if (at < 0) throw new Error(`no block ${selectorStart}`);
  const body = css.slice(css.indexOf("{", at) + 1, css.indexOf("}", at));
  return Object.fromEntries(
    [...body.matchAll(/--(ed-[\w-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [
      m[1],
      m[2],
    ]),
  );
}

function luminance(hex: string): number {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r = 0, g = 0, b = 0] = c.map((v) =>
    v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string | undefined, b: string | undefined): number {
  if (!a || !b) throw new Error("missing palette token");
  const [hi = 0, lo = 0] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const editions = {
  paper: block(':root[data-theme="light"]'),
  night: block(':root[data-theme="dark"]'),
};

describe.each(Object.entries(editions))("%s edition", (_name, p) => {
  it.each(["ed-ink", "ed-ink2", "ed-ink3", "ed-accent"])(
    "%s text passes AA on the ground",
    (t) => {
      expect(contrast(p[t], p["ed-ground"])).toBeGreaterThanOrEqual(4.5);
    },
  );
  it("bars (ink2) pass the 3:1 graphics bar on their track", () => {
    expect(contrast(p["ed-ink2"], p["ed-track"])).toBeGreaterThanOrEqual(3);
  });
});

describe("first-paint dark preference", () => {
  it("mirrors the night edition exactly", () => {
    const media = css.slice(css.indexOf("@media (prefers-color-scheme: dark)"));
    expect(block.call(null, ':root[data-theme="dark"]')).toEqual(
      Object.fromEntries(
        [
          ...media
            .slice(0, media.indexOf("}"))
            .matchAll(/--(ed-[\w-]+):\s*(#[0-9a-f]{6})/gi),
        ].map((m) => [m[1], m[2]]),
      ),
    );
  });
});
