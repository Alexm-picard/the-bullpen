#!/usr/bin/env node
/**
 * Generate frontend/public/parks/<park_id>.svg for all 30 parks.
 *
 * Rewritten 2026-05-25 for the parks redesign (was leaf 4c.1). Differences from
 * the v1 generator:
 *   - Outfield wall path uses cubic-bezier segments between sorted polyline
 *     points (control points are perpendicular-offset outward by 5% of segment
 *     length). The hand-drawn-by-an-architect feel matters when the SVG is
 *     also the page's hero illustration; straight polylines read as "graph",
 *     not "park".
 *   - Dashed dirt-infield arc (90 ft radius) drawn BEFORE the fence so the
 *     fence draws over any overlap.
 *   - Foul lines forced to ±45° exactly, terminating at the LF/RF point's
 *     radial distance. This avoids "the LF line is at -43.7°" cosmetic drift
 *     from the underlying geometry data.
 *   - Per-park overrides applied from frontend/scripts/park-svg-overrides.json:
 *     wall-tick draws a perpendicular nub, v-notch inserts a small V into the
 *     bezier, sharp-angle forces straight-line segments either side of the
 *     point (breaks the smooth curve). Captures park signatures the bezier
 *     would over-smooth (Monster, Crawford Boxes, ivy jogs).
 *   - stroke-width tightened 2 → 1; reads sharper at 180×180 tile size.
 *   - park-meta.json gains a `fences` array (one entry per polyline point)
 *     with {angleDeg, label, distanceFt, heightFt, note?} — feeds the
 *     <ParkDetailModal>'s fence-depths table.
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
 */
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const ROOT = resolve(new URL("..", import.meta.url).pathname);
const REPO = resolve(ROOT, "..");
const GEO_DIR = join(REPO, "infra", "park_geometry");
const OUT_DIR = join(ROOT, "public", "parks");
const META_OUT = join(ROOT, "src", "data", "park-meta.json");
const OVERRIDES_PATH = join(ROOT, "scripts", "park-svg-overrides.json");

const VIEW = 500;
const HOME_X = VIEW / 2;
const HOME_Y = VIEW - 20;
const INFIELD_ARC_FT = 90;
const CONTROL_OFFSET = 0.05; // 5% of segment length, perpendicular outward
const STROKE_WIDTH = 1;

/** Convert (angle_from_centerline_deg, distance_ft) to SVG (x, y) in feet. */
function project(angleDeg, distanceFt) {
  const rad = (angleDeg * Math.PI) / 180;
  const x = HOME_X - distanceFt * Math.sin(rad);
  const y = HOME_Y - distanceFt * Math.cos(rad);
  return [x, y];
}

function fmt(n) {
  return n.toFixed(1);
}

/**
 * Derive a short label from an angle. ±45° → LF/RF, ±22.5° → LC/RC, 0 → CF.
 * Non-standard angles surface as their raw value (e.g. "10°").
 */
function labelForAngle(angleDeg) {
  if (angleDeg === -45) return "LF";
  if (angleDeg === -22.5) return "LC";
  if (angleDeg === 0) return "CF";
  if (angleDeg === 22.5) return "RC";
  if (angleDeg === 45) return "RF";
  return `${angleDeg}°`;
}

/**
 * Build the outfield wall path. Walks the (sorted) polyline points and, for
 * each adjacent pair, emits either a cubic bezier C or a straight L depending
 * on whether the segment touches a sharp-angle override.
 *
 * Sort order: -45° first (LF line) through +45° last (RF line). Note the
 * upstream sort in main() is descending in `angle_from_centerline_deg`, which
 * is wrong direction; we re-sort here ascending to keep the path traversal
 * obvious.
 */
function buildFencePath(points, kinks) {
  const sorted = points
    .slice()
    .sort(
      (a, b) => a.angle_from_centerline_deg - b.angle_from_centerline_deg,
    );

  const sharpAngles = new Set(
    kinks.filter((k) => k.kind === "sharp-angle").map((k) => k.angleDeg),
  );
  const vNotchAngles = new Set(
    kinks.filter((k) => k.kind === "v-notch").map((k) => k.angleDeg),
  );

  const parts = [];
  let started = false;
  for (let i = 0; i < sorted.length; i++) {
    const p = sorted[i];
    const [x, y] = project(p.angle_from_centerline_deg, p.distance_ft);
    if (!started) {
      parts.push(`M ${fmt(x)} ${fmt(y)}`);
      started = true;
      continue;
    }
    const prev = sorted[i - 1];
    const [px, py] = project(prev.angle_from_centerline_deg, prev.distance_ft);

    const segmentTouchesSharp =
      sharpAngles.has(p.angle_from_centerline_deg) ||
      sharpAngles.has(prev.angle_from_centerline_deg);

    if (segmentTouchesSharp) {
      // Straight line preserves the kink — the bezier would round it off.
      parts.push(`L ${fmt(x)} ${fmt(y)}`);
      continue;
    }

    // Cubic bezier — control points perpendicular-offset outward from the
    // segment's midpoint, bowed away from home plate. Offset magnitude is
    // CONTROL_OFFSET × segment length.
    const dx = x - px;
    const dy = y - py;
    const segLen = Math.hypot(dx, dy);
    const offset = segLen * CONTROL_OFFSET;
    // Outward normal: rotate (dx, dy) by -90° in screen coords; flip sign if
    // the resulting normal points toward home plate.
    let nx = dy;
    let ny = -dx;
    const len = Math.hypot(nx, ny) || 1;
    nx /= len;
    ny /= len;
    // Outward = away from home plate. Check via dot with (midpoint - home).
    const mx = (px + x) / 2;
    const my = (py + y) / 2;
    const toMidX = mx - HOME_X;
    const toMidY = my - HOME_Y;
    if (nx * toMidX + ny * toMidY < 0) {
      nx = -nx;
      ny = -ny;
    }
    // Two control points: each one-third of the way along the segment from
    // its endpoint, offset perpendicular by `offset`.
    const c1x = px + dx / 3 + nx * offset;
    const c1y = py + dy / 3 + ny * offset;
    const c2x = px + (2 * dx) / 3 + nx * offset;
    const c2y = py + (2 * dy) / 3 + ny * offset;

    if (vNotchAngles.has(p.angle_from_centerline_deg)) {
      // Bezier to a slightly-inward point first, then a short stroke out to
      // the canonical point — reads as a small V notch in the curve.
      const notchDepth = 8; // ft
      const notchX = x + nx * -notchDepth;
      const notchY = y + ny * -notchDepth;
      parts.push(
        `C ${fmt(c1x)} ${fmt(c1y)} ${fmt(c2x)} ${fmt(c2y)} ${fmt(notchX)} ${fmt(notchY)}`,
      );
      parts.push(`L ${fmt(x)} ${fmt(y)}`);
    } else {
      parts.push(
        `C ${fmt(c1x)} ${fmt(c1y)} ${fmt(c2x)} ${fmt(c2y)} ${fmt(x)} ${fmt(y)}`,
      );
    }
  }
  return { d: parts.join(" "), sorted };
}

/** Render perpendicular wall-tick markers for parks with abrupt height steps. */
function buildWallTicks(points, kinks) {
  const tickAngles = new Set(
    kinks.filter((k) => k.kind === "wall-tick").map((k) => k.angleDeg),
  );
  const out = [];
  for (const p of points) {
    if (!tickAngles.has(p.angle_from_centerline_deg)) continue;
    const [x, y] = project(p.angle_from_centerline_deg, p.distance_ft);
    // Tick along the radial direction — perpendicular to the wall in the
    // common case. Length 12 ft, half outside / half inside the wall.
    const rad = (p.angle_from_centerline_deg * Math.PI) / 180;
    const dx = -Math.sin(rad);
    const dy = -Math.cos(rad);
    const tickHalf = 6;
    const x1 = x - dx * tickHalf;
    const y1 = y - dy * tickHalf;
    const x2 = x + dx * tickHalf;
    const y2 = y + dy * tickHalf;
    out.push(`<line x1="${fmt(x1)}" y1="${fmt(y1)}" x2="${fmt(x2)}" y2="${fmt(y2)}"/>`);
  }
  return out.join("\n    ");
}

/** Find the LF / RF terminal points (the polyline's first and last entries
 *  after ascending sort). Foul lines run from home plate to these points along
 *  exactly ±45° — the radial distance comes from the data. */
function buildFoulLines(sortedPoints) {
  const lf = sortedPoints[0]; // smallest angle (most negative — LF? No: -45° is LF? Convention says + = LF).
  const rf = sortedPoints[sortedPoints.length - 1];
  // Convention: + = LF, - = RF. Smallest-angle entry is the most-negative
  // angle which is RF; largest-angle entry is the most-positive which is LF.
  // Force the foul lines to ±45° exactly regardless of the polyline's actual
  // terminal angle.
  const lfRadialFt = sortedPoints[sortedPoints.length - 1].distance_ft;
  const rfRadialFt = sortedPoints[0].distance_ft;
  const [lfX, lfY] = project(45, lfRadialFt);
  const [rfX, rfY] = project(-45, rfRadialFt);
  return { lfX, lfY, rfX, rfY, lf, rf };
}

function buildSvg(parkId, geo, override) {
  const kinks = override.kinks ?? [];
  const { d: fenceD, sorted } = buildFencePath(geo.fence_polyline, kinks);
  const { lfX, lfY, rfX, rfY } = buildFoulLines(sorted);
  const wallTicks = buildWallTicks(sorted, kinks);

  // Infield arc — quarter circle from 1B line (-45°) to 3B line (+45°) at 90 ft.
  const [arcStartX, arcStartY] = project(-45, INFIELD_ARC_FT);
  const [arcEndX, arcEndY] = project(45, INFIELD_ARC_FT);
  // SVG arc: A rx ry x-axis-rotation large-arc-flag sweep-flag x y
  const arcD = `M ${fmt(arcStartX)} ${fmt(arcStartY)} A ${INFIELD_ARC_FT} ${INFIELD_ARC_FT} 0 0 0 ${fmt(arcEndX)} ${fmt(arcEndY)}`;

  return `<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${VIEW} ${VIEW}"
     role="img" aria-label="${geo.name} field outline"
     fill="none" stroke="currentColor" stroke-width="${STROKE_WIDTH}"
     stroke-linecap="round" stroke-linejoin="round">
  <symbol id="field" viewBox="0 0 ${VIEW} ${VIEW}">
    <!-- dirt infield arc (drawn first; fence overdraws if needed) -->
    <path d="${arcD}" stroke-dasharray="2 2" opacity="0.55"/>
    <!-- foul lines forced to exactly +/- 45 deg -->
    <line x1="${HOME_X}" y1="${HOME_Y}" x2="${fmt(lfX)}" y2="${fmt(lfY)}"/>
    <line x1="${HOME_X}" y1="${HOME_Y}" x2="${fmt(rfX)}" y2="${fmt(rfY)}"/>
    <!-- outfield wall -->
    <path d="${fenceD}"/>${wallTicks ? `\n    <!-- wall ticks (per-park overrides) -->\n    ${wallTicks}` : ""}
    <!-- home plate (small chevron) -->
    <path d="M ${HOME_X - 6} ${HOME_Y} L ${HOME_X} ${HOME_Y + 8} L ${HOME_X + 6} ${HOME_Y} L ${HOME_X + 6} ${HOME_Y - 4} L ${HOME_X - 6} ${HOME_Y - 4} Z"/>
  </symbol>
  <use href="#field"/>
</svg>
`;
}

function noteFor(overrides, parkId, angleDeg) {
  const entry = overrides[parkId];
  if (!entry) return undefined;
  const kink = (entry.kinks ?? []).find((k) => k.angleDeg === angleDeg);
  return kink?.note;
}

function main() {
  mkdirSync(OUT_DIR, { recursive: true });
  mkdirSync(dirname(META_OUT), { recursive: true });

  const overridesRaw = JSON.parse(readFileSync(OVERRIDES_PATH, "utf8"));
  // Strip the _comment top-level field so it isn't treated as a park id.
  const overrides = Object.fromEntries(
    Object.entries(overridesRaw).filter(([k]) => !k.startsWith("_")),
  );

  const files = readdirSync(GEO_DIR)
    .filter((f) => f.endsWith(".json"))
    .filter((f) => !f.startsWith("_")); // skip _schema.json
  const meta = {};
  for (const f of files) {
    const parkId = f.replace(/\.json$/, "");
    const geo = JSON.parse(readFileSync(join(GEO_DIR, f), "utf8"));
    const parkOverride = overrides[parkId] ?? { kinks: [] };
    const svg = buildSvg(parkId, geo, parkOverride);
    writeFileSync(join(OUT_DIR, `${parkId}.svg`), svg);

    const fence = geo.fence_polyline;
    const distances = fence.map((p) => p.distance_ft);
    const cfPoint = fence.find((p) => p.angle_from_centerline_deg === 0);
    const fences = fence
      .slice()
      .sort(
        (a, b) => a.angle_from_centerline_deg - b.angle_from_centerline_deg,
      )
      .map((p) => {
        const entry = {
          angleDeg: p.angle_from_centerline_deg,
          label: labelForAngle(p.angle_from_centerline_deg),
          distanceFt: p.distance_ft,
          heightFt: p.height_ft,
        };
        const note = noteFor(overrides, parkId, p.angle_from_centerline_deg);
        if (note) entry.note = note;
        return entry;
      });

    meta[parkId] = {
      name: geo.name,
      svgPath: `/parks/${parkId}.svg`,
      altitudeM: geo.altitude_m ?? null,
      shortFenceFt: Math.min(...distances),
      centerFenceFt: cfPoint ? cfPoint.distance_ft : null,
      deepestFenceFt: Math.max(...distances),
      fences,
    };

    const kinkCount = parkOverride.kinks?.length ?? 0;
    console.log(
      `  ${parkId.padEnd(4)}  ${fence.length} points, ${kinkCount} override${kinkCount === 1 ? "" : "s"}  →  ${parkId}.svg`,
    );
  }
  writeFileSync(META_OUT, JSON.stringify(meta, null, 2) + "\n");
  console.log(
    `Wrote ${files.length} SVGs to ${OUT_DIR} and meta to ${META_OUT}`,
  );
}

main();
