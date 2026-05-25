#!/usr/bin/env node
/**
 * Bundle-budget gate (leaf 5.3).
 *
 * Reads frontend/dist/assets/ after a production build and rejects any chunk
 * that exceeds its gzipped budget. Exits 1 on violation, prints a sorted
 * report on success so the operator can eyeball the cost over time.
 *
 * Budgets (gzipped, decision: design.md §7):
 *   - initial JS (index entry + every chunk it imports synchronously) < 300 kB
 *   - any single chunk                                               < 250 kB
 *
 * "Initial JS" = entry chunk + all `mantine`, `tanstack`, `router` vendor
 * splits, since they're statically imported. Per-route page chunks loaded via
 * React.lazy are NOT counted against the initial budget — they fetch on
 * navigation.
 */
import { gzipSync } from "node:zlib";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(new URL("..", import.meta.url).pathname);
const ASSETS = join(ROOT, "dist", "assets");

const INITIAL_BUDGET_BYTES = 300 * 1024;
const PER_CHUNK_BUDGET_BYTES = 250 * 1024;

const STATIC_VENDOR_CHUNKS = ["mantine", "tanstack", "router"];

function gzippedSize(path) {
  const buf = readFileSync(path);
  return gzipSync(buf).length;
}

function fmt(bytes) {
  const k = bytes / 1024;
  return `${k.toFixed(1)} kB`;
}

let exitCode = 0;
let initialJsBytes = 0;

let entries;
try {
  entries = readdirSync(ASSETS);
} catch (err) {
  console.error(`bundle-budget — could not read ${ASSETS}: ${err.message}`);
  console.error("Run `npm run build` first.");
  process.exit(1);
}

const jsChunks = entries
  .filter((name) => name.endsWith(".js"))
  .map((name) => {
    const path = join(ASSETS, name);
    return {
      name,
      bytes: statSync(path).size,
      gzipBytes: gzippedSize(path),
    };
  })
  .sort((a, b) => b.gzipBytes - a.gzipBytes);

console.log("bundle-budget — chunk report");
for (const chunk of jsChunks) {
  const isInitial =
    chunk.name.startsWith("index-") ||
    STATIC_VENDOR_CHUNKS.some((v) => chunk.name.startsWith(`${v}-`));
  const marker = isInitial ? "[initial]" : "[lazy]";
  console.log(`  ${marker.padEnd(11)} ${chunk.name.padEnd(40)} ${fmt(chunk.gzipBytes)} gz`);
  if (chunk.gzipBytes > PER_CHUNK_BUDGET_BYTES) {
    console.error(
      `    FAIL: chunk exceeds per-chunk budget ${fmt(PER_CHUNK_BUDGET_BYTES)}`,
    );
    exitCode = 1;
  }
  if (isInitial) {
    initialJsBytes += chunk.gzipBytes;
  }
}

console.log(
  `\ninitial JS total: ${fmt(initialJsBytes)} gz (budget ${fmt(INITIAL_BUDGET_BYTES)})`,
);
if (initialJsBytes > INITIAL_BUDGET_BYTES) {
  console.error(
    `FAIL: initial JS exceeds ${fmt(INITIAL_BUDGET_BYTES)} — split heavier vendors or lazy-load more pages.`,
  );
  exitCode = 1;
}

if (exitCode === 0) {
  console.log("bundle-budget — clean");
}
process.exit(exitCode);
