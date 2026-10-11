import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { afterEach, describe, expect, it } from "vitest";

import { readDocCounts } from "./doc-counts";

const made: string[] = [];
function docsDir(): string {
  const dir = mkdtempSync(join(tmpdir(), "doc-counts-"));
  made.push(dir);
  return dir;
}
afterEach(() => {
  for (const d of made.splice(0)) rmSync(d, { recursive: true, force: true });
});

describe("readDocCounts", () => {
  it("counts numbered decision entries and NNNN- ADR files only", () => {
    const dir = docsDir();
    writeFileSync(
      join(dir, "decisions.md"),
      [
        "# Decisions",
        "",
        "`[1]` 2026-05-09 - first",
        "",
        "`[2]` 2026-05-10 - second, which mentions `[1]` mid-line",
        "  `[9]` indented, not an entry",
        "`[3]` 2026-05-11 - third",
      ].join("\n"),
    );
    mkdirSync(join(dir, "adr"));
    writeFileSync(join(dir, "adr", "0001-java-not-kotlin.md"), "");
    writeFileSync(join(dir, "adr", "0002-onnx.md"), "");
    writeFileSync(join(dir, "adr", "TEMPLATE.md"), "");
    writeFileSync(join(dir, "adr", "notes.txt"), "");

    expect(readDocCounts(dir)).toEqual({ decisions: 3, adrs: 2 });
  });

  it("returns null for anything it cannot read, never a guess", () => {
    expect(readDocCounts(join(docsDir(), "missing"))).toEqual({
      decisions: null,
      adrs: null,
    });
  });

  it("returns null for an empty log or an empty adr directory", () => {
    const dir = docsDir();
    writeFileSync(join(dir, "decisions.md"), "# Decisions\n");
    mkdirSync(join(dir, "adr"));
    writeFileSync(join(dir, "adr", "TEMPLATE.md"), "");
    expect(readDocCounts(dir)).toEqual({ decisions: null, adrs: null });
  });

  it("counts this repository's docs/ (the real input the build reads)", () => {
    const counts = readDocCounts(
      fileURLToPath(new URL("../../docs", import.meta.url)),
    );
    expect(counts.decisions).toBeGreaterThan(200);
    expect(counts.adrs).toBeGreaterThanOrEqual(17);
  });
});
