/**
 * Live top-label reliability chart for /accuracy (decision [199]; direction C's treatment
 * re-set in the [195] editorial palette).
 *
 * Observed frequency per predicted-confidence bin as points joined by a line, a dashed diagonal
 * for perfect calibration, quiet per-bin volume bars along the bottom. Data in INK, never the
 * accent (the editorial rule: red is for links). Pure SVG, no chart library.
 *
 *  - Square plot, both axes fixed 0..1 on every chart so the reports compare honestly.
 *  - x = the bin's MEAN predicted confidence (not the bin centre); y = observed.
 *  - Bins below `minReadableN` are drawn hollow and left off the line.
 *  - Rendered at the container's REAL width (measured), so ticks stay 10 CSS px on a phone
 *    instead of shrinking with a scaled viewBox.
 *  - Never the only carrier: a visually hidden table lists every bin.
 *
 * The caller applies the 300-call floor; this component draws whatever bins it is handed.
 */
import { VisuallyHidden } from "@mantine/core";
import { useElementSize } from "@mantine/hooks";

import type { ReliabilityBin } from "../../api/rolling-accuracy";

const MARGIN = { l: 34, r: 8, t: 8, b: 34 };
const VOLUME_SHARE = 0.2;
const BAR_GAP = 3;

const COUNT = new Intl.NumberFormat("en-US");
const PCT = new Intl.NumberFormat("en-US", {
  style: "percent",
  maximumFractionDigits: 0,
});

export type ReliabilityChartProps = {
  bins: ReliabilityBin[];
  minReadableN: number;
  /** Largest plot side in CSS px (the lede is bigger than the pair). */
  maxSide: number;
  /** Accessible name, e.g. "pitch_outcome_post calibration, last 30 days, 60,800 graded". */
  label: string;
  /** Caption of the hidden bin table (the chart's visible title). */
  tableCaption: string;
};

export function ReliabilityChart({
  bins,
  minReadableN,
  maxSide,
  label,
  tableCaption,
}: ReliabilityChartProps) {
  const { ref, width } = useElementSize();
  // Before measurement (first paint, SSR, jsdom) draw at the max side; the box is pre-sized by
  // CSS aspect ratio, so the swap to the measured width causes no layout shift.
  const avail = width > 0 ? width : maxSide + MARGIN.l + MARGIN.r;
  const side = Math.max(120, Math.min(maxSide, avail - MARGIN.l - MARGIN.r));
  const W = side + MARGIN.l + MARGIN.r;
  const H = side + MARGIN.t + MARGIN.b;
  const x = (p: number) => MARGIN.l + p * side;
  const y = (p: number) => MARGIN.t + (1 - p) * side;
  const maxN = Math.max(1, ...bins.map((b) => b.n));
  const r = side > 360 ? 4.5 : 3.5;

  const readable = bins.filter((b) => b.n >= minReadableN);
  const path = readable
    .map(
      (b, i) =>
        `${i === 0 ? "M" : "L"}${x(b.meanConfidence).toFixed(1)},${y(b.observed).toFixed(1)}`,
    )
    .join(" ");
  const ticks = [0, 0.5, 1];
  const tickText = (t: number) => (t === 0 ? "0" : t === 1 ? "1.0" : ".5");

  return (
    <div ref={ref} className="acc-chart" style={{ maxWidth: W }}>
      <svg
        width={W}
        height={H}
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={label}
      >
        {[0.25, 0.5, 0.75].map((t) => (
          <line
            key={t}
            className="acc-chart__grid"
            x1={x(0)}
            x2={x(1)}
            y1={y(t)}
            y2={y(t)}
          />
        ))}
        {bins.map((b) => {
          const h = Math.max(1, (b.n / maxN) * side * VOLUME_SHARE);
          const bw = Math.max(1, (b.upper - b.lower) * side - BAR_GAP);
          return (
            <rect
              key={`v${b.lower}`}
              className="acc-chart__vol"
              x={x(b.lower) + BAR_GAP / 2}
              y={y(0) - h}
              width={bw}
              height={h}
            />
          );
        })}
        <line
          className="acc-chart__axis"
          x1={x(0)}
          x2={x(1)}
          y1={y(0)}
          y2={y(0)}
        />
        <line
          className="acc-chart__axis"
          x1={x(0)}
          x2={x(0)}
          y1={y(0)}
          y2={y(1)}
        />
        <line
          className="acc-chart__diag"
          x1={x(0)}
          y1={y(0)}
          x2={x(1)}
          y2={y(1)}
        />
        {path ? <path className="acc-chart__line" d={path} /> : null}
        {bins.map((b) => (
          <circle
            key={`p${b.lower}`}
            className={
              b.n >= minReadableN ? "acc-chart__pt" : "acc-chart__pt--thin"
            }
            data-thin={b.n >= minReadableN ? "false" : "true"}
            cx={x(b.meanConfidence)}
            cy={y(b.observed)}
            r={r}
          />
        ))}
        {ticks.map((t) => (
          <g key={`t${t}`}>
            <text
              className="acc-chart__tick"
              x={x(t)}
              y={y(0) + 14}
              textAnchor="middle"
            >
              {tickText(t)}
            </text>
            <text
              className="acc-chart__tick"
              x={MARGIN.l - 6}
              y={y(t) + 3}
              textAnchor="end"
            >
              {tickText(t)}
            </text>
          </g>
        ))}
        <text
          className="acc-chart__label"
          x={x(0.5)}
          y={H - 4}
          textAnchor="middle"
        >
          predicted probability
        </text>
        <text
          className="acc-chart__label"
          transform={`translate(10 ${y(0.5)}) rotate(-90)`}
          textAnchor="middle"
        >
          observed
        </text>
      </svg>
      <VisuallyHidden>
        <table>
          <caption>{tableCaption}</caption>
          <thead>
            <tr>
              <th scope="col">Predicted confidence</th>
              <th scope="col">Calls</th>
              <th scope="col">Came true</th>
              <th scope="col">Mean predicted</th>
              <th scope="col">Observed</th>
            </tr>
          </thead>
          <tbody>
            {bins.map((b) => (
              <tr key={`row${b.lower}`}>
                <td>
                  {b.lower.toFixed(1)} to {b.upper.toFixed(1)}
                  {b.n < minReadableN ? " (too few to read)" : ""}
                </td>
                <td>{COUNT.format(b.n)}</td>
                <td>{COUNT.format(b.hits)}</td>
                <td>{PCT.format(b.meanConfidence)}</td>
                <td>{PCT.format(b.observed)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </VisuallyHidden>
    </div>
  );
}
