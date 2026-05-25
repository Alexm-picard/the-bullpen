#!/usr/bin/env node
/**
 * Generate frontend/public/parks/<park_id>.svg for all 30 parks (leaf 4c.1).
 *
 * Reads the canonical fence polylines from infra/park_geometry/<park_id>.json
 * (Phase 2c.3) and emits a clean line drawing — foul lines (LF + RF), the
 * outfield wall polyline, and home plate as a single small chevron. No
 * gradients, no drop shadows (decision [107]).
 *
 * Geometry convention from infra/park_geometry/*.json:
 *   - `angle_from_centerline_deg`: + = LF (3B side), 0 = CF, - = RF.
 *   - `distance_ft`: feet from home plate.
 *
 * SVG convention:
 *   - viewBox 0 0 500 500, home plate at (250, 480), CF straight up.
 *   - 1 SVG unit = 1 foot. Coors' 415 ft CF fits with 65 px headroom.
 *   - stroke="currentColor" so consumers theme the line via CSS color.
 *   - `<symbol id="field">` wrapper lets pages `<use href="/parks/NYY.svg#field" />`.
 *
 * Also writes frontend/src/data/park-meta.json with the per-park summary
 * (name, indoor flag, short / center fence distance, svgPath).
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const ROOT = resolve(new URL("..", import.meta.url).pathname);
const REPO = resolve(ROOT, "..");
const GEO_DIR = join(REPO, "infra", "park_geometry");
const OUT_DIR = join(ROOT, "public", "parks");
const META_OUT = join(ROOT, "src", "data", "park-meta.json");

const VIEW = 500;
const HOME_X = VIEW / 2;
const HOME_Y = VIEW - 20;

/** Convert (angle_from_centerline_deg, distance_ft) to SVG (x, y) in feet. */
function project(angleDeg, distanceFt) {
  const rad = (angleDeg * Math.PI) / 180;
  const x = HOME_X - distanceFt * Math.sin(rad);
  const y = HOME_Y - distanceFt * Math.cos(rad);
  return [x, y];
}

function buildSvg(parkId, geo) {
  const fence = geo.fence_polyline.slice().sort(
    (a, b) => b.angle_from_centerline_deg - a.angle_from_centerline_deg,
  );
  // Fence polyline path
  const fenceD = fence
    .map((p, i) => {
      const [x, y] = project(p.angle_from_centerline_deg, p.distance_ft);
      return `${i === 0 ? "M" : "L"} ${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(" ");

  // Foul lines: home → fence end on LF / RF
  const lf = fence[0];
  const rf = fence[fence.length - 1];
  const [lfX, lfY] = project(lf.angle_from_centerline_deg, lf.distance_ft);
  const [rfX, rfY] = project(rf.angle_from_centerline_deg, rf.distance_ft);

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${VIEW} ${VIEW}"
     role="img" aria-label="${geo.name} field outline"
     fill="none" stroke="currentColor" stroke-width="2"
     stroke-linecap="round" stroke-linejoin="round">
  <symbol id="field" viewBox="0 0 ${VIEW} ${VIEW}">
    <!-- foul lines -->
    <line x1="${HOME_X}" y1="${HOME_Y}" x2="${lfX.toFixed(1)}" y2="${lfY.toFixed(1)}"/>
    <line x1="${HOME_X}" y1="${HOME_Y}" x2="${rfX.toFixed(1)}" y2="${rfY.toFixed(1)}"/>
    <!-- outfield wall -->
    <path d="${fenceD}"/>
    <!-- home plate (small chevron) -->
    <path d="M ${HOME_X - 6} ${HOME_Y} L ${HOME_X} ${HOME_Y + 8} L ${HOME_X + 6} ${HOME_Y} L ${HOME_X + 6} ${HOME_Y - 4} L ${HOME_X - 6} ${HOME_Y - 4} Z"/>
  </symbol>
  <use href="#field"/>
</svg>
`;
}

function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  mkdirSync(dirname(META_OUT), { recursive: true });

  const files = readdirSync(GEO_DIR)
    .filter((f) => f.endsWith(".json"))
    .filter((f) => !f.startsWith("_")); // skip _schema.json
  const meta = {};
  for (const f of files) {
    const parkId = f.replace(/\.json$/, "");
    const geo = JSON.parse(readFileSync(join(GEO_DIR, f), "utf8"));
    const svg = buildSvg(parkId, geo);
    writeFileSync(join(OUT_DIR, `${parkId}.svg`), svg);

    const fence = geo.fence_polyline;
    const distances = fence.map((p) => p.distance_ft);
    const cfPoint = fence.find((p) => p.angle_from_centerline_deg === 0);
    meta[parkId] = {
      name: geo.name,
      svgPath: `/parks/${parkId}.svg`,
      altitudeM: geo.altitude_m ?? null,
      shortFenceFt: Math.min(...distances),
      centerFenceFt: cfPoint ? cfPoint.distance_ft : null,
      deepestFenceFt: Math.max(...distances),
    };
  }
  writeFileSync(META_OUT, JSON.stringify(meta, null, 2) + "\n");
  console.log(`Wrote ${files.length} SVGs to ${OUT_DIR} and meta to ${META_OUT}`);
}

main();
