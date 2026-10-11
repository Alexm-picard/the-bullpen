/**
 * Build-time counts for the /about record (SPEC-guide-about §6.2): the numbered entries in
 * docs/decisions.md and the ADR files in docs/adr. Read once by vite.config.ts and injected through
 * `define`, the same way the build SHA is.
 *
 * Either count is `null` when docs/ cannot be read (for example a Vercel build that does not include
 * files outside the frontend root). The page then prints an em-dash and says the build did not count
 * it, rather than falling back to a number that would go stale.
 */
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

export type DocCounts = { decisions: number | null; adrs: number | null };

/** A decision entry is a line that starts with `[N]` in backticks; the log's own entry shape. */
const DECISION_LINE = /^`\[\d+\]`/;
/** An ADR is `NNNN-title.md`; TEMPLATE.md and anything else is not counted. */
const ADR_FILE = /^\d{4}-.+\.md$/;

function countDecisions(docsDir: string): number | null {
  try {
    const log = readFileSync(join(docsDir, "decisions.md"), "utf8");
    const n = log.split("\n").filter((line) => DECISION_LINE.test(line)).length;
    return n > 0 ? n : null;
  } catch {
    return null;
  }
}

function countAdrs(docsDir: string): number | null {
  try {
    const n = readdirSync(join(docsDir, "adr")).filter((f) =>
      ADR_FILE.test(f),
    ).length;
    return n > 0 ? n : null;
  } catch {
    return null;
  }
}

export function readDocCounts(docsDir: string): DocCounts {
  return { decisions: countDecisions(docsDir), adrs: countAdrs(docsDir) };
}
