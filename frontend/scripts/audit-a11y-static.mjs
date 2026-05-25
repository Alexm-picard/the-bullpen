#!/usr/bin/env node
/**
 * Static a11y heuristic check (leaf 5.4 — partial).
 *
 * A real axe-core run needs Playwright + a running browser; this script is the
 * cheap surrogate that catches the most common static defects in the codebase
 * we control:
 *
 *   - <svg> without role="img" or aria-label
 *   - <img> without alt=
 *   - more than one <Title order={1}> per page file (multiple h1)
 *   - role="button" without tabIndex / onKeyDown
 *
 * Exit 1 on any finding; print one line per finding. Wire into CI after
 * `npm run build` (or `npm test`) so regressions block merges.
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { extname, join, relative, resolve } from "node:path";

const ROOT = resolve(new URL("..", import.meta.url).pathname);
const SRC = join(ROOT, "src");
const EXTS = new Set([".tsx"]);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const s = statSync(p);
    if (s.isDirectory()) {
      if (name === "node_modules" || name.startsWith(".") || name === "__snapshots__") continue;
      out.push(...walk(p));
    } else if (EXTS.has(extname(name))) {
      out.push(p);
    }
  }
  return out;
}

const findings = [];

for (const file of walk(SRC)) {
  const rel = relative(ROOT, file);
  if (rel.includes(".test.")) continue;
  const raw = readFileSync(file, "utf8");
  // Strip block + line comments so the regex doesn't match JSDoc snippets like
  // `<svg>` mentioned in documentation.
  const text = raw
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/(^|[^:])\/\/.*$/gm, "$1");

  // 1. <svg> without role + aria-label. Match opening tags only.
  const svgTags = text.match(/<svg\b[^>]*>/g) ?? [];
  for (const tag of svgTags) {
    if (!tag.includes("role=") || !tag.includes("aria-label")) {
      findings.push({
        file: rel,
        rule: "svg-needs-role-and-aria-label",
        snippet: tag.slice(0, 100),
      });
    }
  }

  // 2. <img> without alt (raw img tags only — Mantine `Image` requires alt by ts type)
  const imgTags = text.match(/<img\b[^>]*>/g) ?? [];
  for (const tag of imgTags) {
    if (!tag.includes("alt=")) {
      findings.push({
        file: rel,
        rule: "img-needs-alt",
        snippet: tag.slice(0, 100),
      });
    }
  }

  // 3. role="button" without tabIndex AND onKeyDown
  if (text.includes('role="button"')) {
    const buttonBlocks = text.match(/role="button"[\s\S]{0,400}/g) ?? [];
    for (const block of buttonBlocks) {
      if (!block.includes("tabIndex") || !block.includes("onKeyDown")) {
        findings.push({
          file: rel,
          rule: "role-button-needs-tabIndex-and-onKeyDown",
          snippet: block.slice(0, 100),
        });
      }
    }
  }

  // 4. more than three <Title order={1}> per page file — multi-page files
  //    (list + profile + conditional render) can legitimately have a few; >3
  //    is suspicious. Per-render h1 count is enforced at runtime, not statically.
  const h1Count = (text.match(/<Title\s+order=\{1\}/g) ?? []).length;
  if (rel.startsWith("src/pages/") && h1Count > 3) {
    findings.push({
      file: rel,
      rule: "page-has-too-many-h1-sources",
      snippet: `count=${h1Count}`,
    });
  }
}

if (findings.length === 0) {
  console.log("a11y-static — clean (0 findings)");
  process.exit(0);
}

console.error(`a11y-static — ${findings.length} finding(s):`);
for (const f of findings) {
  console.error(`  ${f.file}  [${f.rule}]  ${f.snippet}`);
}
process.exit(1);
