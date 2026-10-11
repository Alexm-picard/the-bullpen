/**
 * <ParkStrip> - all 30 parks on one fixed 0..1 axis of estimated P(HR) (SPEC-parks section 6.5).
 *
 * Dots stack in .04 bins; a dot is filled at or above HR_THRESHOLD (the game page's own cut) and
 * ringed below it, so "how many parks" is read by fill weight, never colour. The axis never
 * rescales to the data: a flat answer looks flat. End labels name the parks in the lowest and
 * highest occupied bins.
 *
 * One labelled image (role="img"): its label summarises the strip (how many parks, how many at or
 * above .50, the lowest and highest); the Every park list beside it carries every value. Dots move with a transform transition on a new scored result (the reader sees
 * the thirty shift together); reduced motion drops it (editorial.css).
 */
import { useElementSize } from "@mantine/hooks";

import { HR_THRESHOLD, type ParkLine } from "../games/game-account";

const BIN = 0.04;
const BINS = Math.round(1 / BIN); // 25
const H = 132;
const M = { l: 6, r: 6, b: 24 };
const STEP = 11; // vertical spacing of stacked dots
const R = 5;

export function ParkStrip({ lines }: { lines: readonly ParkLine[] }) {
  const { ref, width } = useElementSize();
  const W = width > 0 ? width : 460;
  const S = W - M.l - M.r;
  const base = H - M.b;
  const x = (p: number) => M.l + p * S;

  // Lowest P(HR) first inside a bin, so a bin's stack reads bottom-up in rank order.
  const sorted = [...lines].sort(
    (a, b) => a.prob - b.prob || a.id.localeCompare(b.id),
  );
  const counts = new Map<number, number>();
  const dots = sorted.map((l) => {
    const bin = Math.min(BINS - 1, Math.max(0, Math.floor(l.prob / BIN)));
    const n = counts.get(bin) ?? 0;
    counts.set(bin, n + 1);
    return { l, cx: x((bin + 0.5) * BIN), cy: base - 7 - n * STEP };
  });
  const occupied = [...counts.keys()].sort((a, b) => a - b);
  const lowBin = occupied[0];
  const highBin = occupied[occupied.length - 1];
  const binIds = (bin: number | undefined) =>
    bin == null
      ? []
      : sorted
          .filter((l) => Math.min(BINS - 1, Math.floor(l.prob / BIN)) === bin)
          .map((l) => l.id)
          .slice(0, 4);
  const lowIds = binIds(lowBin);
  const highIds = lowBin === highBin ? [] : binIds(highBin);
  const stackTop = (bin: number | undefined) =>
    base - 7 - ((bin != null ? (counts.get(bin) ?? 1) : 1) - 1) * STEP - R - 8;
  const hrCount = lines.filter((l) => l.hr).length;
  const lowest = sorted[0];
  const highest = sorted[sorted.length - 1];
  const summary =
    lowest && highest
      ? `${lines.length} parks on a 0 to 1 scale of estimated home-run probability; ${hrCount} at .50 or higher. Lowest ${lowest.parkName}, highest ${highest.parkName}.`
      : "No parks to show.";
  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const tickText = (t: number) =>
    t === 0 ? "0" : t === 1 ? "1.0" : String(t).slice(1);

  return (
    <div ref={ref} className="pk-strip">
      <svg
        width={W}
        height={H}
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={summary}
      >
        <line
          className="pk-strip__axis"
          x1={M.l}
          x2={W - M.r}
          y1={base}
          y2={base}
        />
        <line
          className="pk-strip__half"
          x1={x(HR_THRESHOLD)}
          x2={x(HR_THRESHOLD)}
          y1={base}
          y2={8}
        />
        <text className="pk-strip__t" x={x(HR_THRESHOLD) + 4} y={14}>
          .50
        </text>
        {dots.map(({ l, cx, cy }) => (
          <circle
            key={l.id}
            className={
              l.hr ? "pk-strip__dot pk-strip__dot--hr" : "pk-strip__dot"
            }
            cx={0}
            cy={0}
            r={R}
            style={{
              transform: `translate(${cx.toFixed(1)}px, ${cy.toFixed(1)}px)`,
            }}
          />
        ))}
        {ticks.map((t) => (
          <text
            key={t}
            className="pk-strip__t"
            x={x(t)}
            y={H - 8}
            textAnchor={t === 0 ? "start" : t === 1 ? "end" : "middle"}
          >
            {tickText(t)}
          </text>
        ))}
        {lowIds.length > 0 && lowBin != null ? (
          <text
            className="pk-strip__l"
            x={Math.max(M.l, x(lowBin * BIN))}
            y={stackTop(lowBin)}
            textAnchor="start"
          >
            {lowIds.join(", ")}
          </text>
        ) : null}
        {highIds.length > 0 && highBin != null ? (
          <text
            className="pk-strip__l"
            x={Math.min(W - M.r, x((highBin + 1) * BIN))}
            y={stackTop(highBin)}
            textAnchor="end"
          >
            {highIds.join(", ")}
          </text>
        ) : null}
      </svg>
    </div>
  );
}
