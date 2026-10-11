/**
 * <ConfidenceHistogram> - "Where his calls landed" (SPEC-players 6.3): how many of a player's
 * logged calls fell in each predicted-confidence bin. CONFIDENCE ONLY, by design.
 *
 * The per-player calibration endpoint bins predicted probabilities; there is no per-player
 * truth-join yet, so `actual` is null today. This component never reads `actual`, never draws a
 * diagonal or an observed axis, and so cannot imply a measurement that was not made - even if a
 * future response starts carrying observed rates (swapping in the /accuracy ReliabilityChart is a
 * deliberate later change, not an automatic upgrade).
 *
 * Bars in ink2, n printed over each bar, ticks at 0 / .25 / .5 / .75 / 1.0. Rendered at the
 * container's measured width so tick text stays 10 CSS px on a phone; a hidden table carries
 * every bin for screen readers.
 */
import { VisuallyHidden } from "@mantine/core";
import { useElementSize } from "@mantine/hooks";
import { useState } from "react";

import type { CalibrationBin } from "../../api/players";

const COUNT = new Intl.NumberFormat("en-US");
const H = 132;
const M = { l: 4, r: 4, t: 18, b: 22 };
const GAP = 3;
const MAX_W = 480;

function tick(t: number): string {
  if (t === 0) return "0";
  if (t === 1) return "1.0";
  return String(t).slice(1);
}

export function ConfidenceHistogram({ bins }: { bins: CalibrationBin[] }) {
  const { ref, width: measured } = useElementSize();
  // Keep the last real measurement: a transient 0 (an element briefly out of layout, a
  // beyond-viewport capture) must not snap the chart back to its desktop width.
  const [width, setWidth] = useState(0);
  if (measured > 0 && measured !== width) setWidth(measured);
  const W = Math.max(200, Math.min(MAX_W, width > 0 ? width : MAX_W));
  const S = W - M.l - M.r;
  const plotH = H - M.t - M.b;
  const x = (p: number) => M.l + Math.max(0, Math.min(1, p)) * S;
  const maxN = Math.max(1, ...bins.map((b) => b.n));
  const total = bins.reduce((s, b) => s + b.n, 0);

  return (
    <div ref={ref} className="pl-chart" style={{ maxWidth: MAX_W }}>
      <svg
        width={W}
        height={H}
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`Calls for this player by confidence, ${COUNT.format(total)} calls; observed rates not yet joined`}
      >
        {bins.map((b) => {
          const bh = Math.max(1, (b.n / maxN) * plotH);
          const x0 = x(b.binStart) + GAP / 2;
          const bw = Math.max(1, x(b.binEnd) - x(b.binStart) - GAP);
          return (
            <g key={b.binStart}>
              <rect
                className="pl-dist__bar"
                x={x0}
                y={M.t + plotH - bh}
                width={bw}
                height={bh}
              />
              <text
                className="pl-dist__n"
                x={x0 + bw / 2}
                y={M.t + plotH - bh - 4}
                textAnchor="middle"
              >
                {COUNT.format(b.n)}
              </text>
            </g>
          );
        })}
        <line
          className="pl-dist__axis"
          x1={M.l}
          x2={W - M.r}
          y1={M.t + plotH}
          y2={M.t + plotH}
        />
        {[0, 0.25, 0.5, 0.75, 1].map((t) => (
          <text
            key={t}
            className="pl-dist__tick"
            x={x(t)}
            y={H - 6}
            textAnchor={t === 0 ? "start" : t === 1 ? "end" : "middle"}
          >
            {tick(t)}
          </text>
        ))}
      </svg>
      <VisuallyHidden>
        <table>
          <caption>Calls by predicted confidence</caption>
          <thead>
            <tr>
              <th scope="col">Confidence</th>
              <th scope="col">Calls</th>
            </tr>
          </thead>
          <tbody>
            {bins.map((b) => (
              <tr key={b.binStart}>
                <td>
                  {b.binStart.toFixed(2)} to {b.binEnd.toFixed(2)}
                </td>
                <td>{COUNT.format(b.n)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </VisuallyHidden>
    </div>
  );
}
