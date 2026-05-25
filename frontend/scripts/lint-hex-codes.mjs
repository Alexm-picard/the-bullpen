#!/usr/bin/env node
/**
 * CI lint: refuses hex color literals in src/** outside of src/design/.
 *
 * Enforces CLAUDE.md rule 2 ("hex codes in component files are defects — reach
 * for tokens"). The token module under src/design/ is the single source of
 * truth; every other file should reach for `tokens.colors.*` or a Tailwind
 * utility (which itself derives from the @theme block in index.css).
 *
 * Exits 0 on a clean tree, 1 on any offending hit. Prints `file:line:col   hex`
 * for each match so the report drops straight into a code reviewer's eye-line.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative, resolve, sep } from "node:path";

const ROOT = resolve(new URL("..", import.meta.url).pathname);
const SRC = join(ROOT, "src");
const ALLOW_PREFIX = join("src", "design") + sep;
const EXTS = new Set([".ts", ".tsx", ".js", ".jsx", ".css"]);
const HEX = /#[0-9a-fA-F]{3,8}\b/g;

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const s = statSync(p);
    if (s.isDirectory()) {
      if (name === "node_modules" || name.startsWith(".")) continue;
      out.push(...walk(p));
    } else if (EXTS.has(extname(name))) {
      out.push(p);
    }
  }
  return out;
}

const hits = [];
for (const file of walk(SRC)) {
  const rel = relative(ROOT, file);
  if (rel.startsWith(ALLOW_PREFIX)) continue;
  const text = readFileSync(file, "utf8");
  const lines = text.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let m;
    HEX.lastIndex = 0;
    while ((m = HEX.exec(line)) !== null) {
      hits.push({ file: rel, line: i + 1, col: m.index + 1, hex: m[0] });
    }
  }
}

if (hits.length === 0) {
  console.log("lint:hex-codes — clean (0 hits outside src/design/)");
  process.exit(0);
}

console.error(
  `lint:hex-codes — FAIL: ${hits.length} hex literal(s) outside src/design/`,
);
for (const h of hits) {
  console.error(`  ${h.file}:${h.line}:${h.col}   ${h.hex}`);
}
console.error(
  "\nFix: replace each with `tokens.colors.*` (TS/TSX) or a Tailwind utility (e.g., `bg-bgBase`, `text-textStrong`).",
);
process.exit(1);
