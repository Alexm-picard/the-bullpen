/**
 * motion.ts mirrors the --duration-* / --ease-* custom properties declared in editorial.css
 * :root. The CSS file is the oracle (parsed, not restated); the TS object is the subject, so a
 * value changed on one side only reds here.
 */
/// <reference types="node" />
// Read from disk: vitest does not process CSS, so a `?raw` import arrives empty here.
import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { motion } from "./motion";

const css = readFileSync(new URL("./editorial.css", import.meta.url), "utf8");

function cssVar(name: string): string | undefined {
  const m = new RegExp(`--${name}:\\s*([^;]+);`).exec(css);
  return m?.[1]?.trim();
}

describe("motion tokens", () => {
  it.each(Object.entries(motion.durationsMs))(
    "duration %s matches editorial.css",
    (key, ms) => {
      expect(cssVar(`duration-${key}`)).toBe(`${ms}ms`);
    },
  );

  it.each([
    ["standard", "ease-standard"],
    ["out", "ease-out"],
    ["inOut", "ease-in-out"],
    ["drawer", "ease-drawer"],
    ["color", "ease-color"],
  ] as const)("easing %s matches --%s", (key, name) => {
    expect(cssVar(name)).toBe(motion.easing[key]);
  });

  it("keeps [112]'s 150-300ms functional band", () => {
    expect(motion.durationsMs.fast).toBe(150);
    expect(motion.durationsMs.slow).toBe(300);
  });
});
