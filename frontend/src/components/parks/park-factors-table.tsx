/**
 * <ParkFactorsTable> - the ONE fenced Showcase section on /parks (SPEC-parks, decision [202]).
 *
 * Fixture values only (PARK_ROWS), labelled inline by the page's ShowcaseNote. Data in ink: the
 * distance from league average (1.00) is a short tick either side of a centre line on the track,
 * sign by SIDE, never red/green (the retired cellColor tints). Every value is printed; the tick is
 * aria-hidden. On a phone the Climate, BABIP, Wind and K columns hide (the caption says which show).
 */
import { VisuallyHidden } from "@mantine/core";

import type { ParkRow, WindBias } from "../../data/parks-fixtures";

const F2 = new Intl.NumberFormat("en-US", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const CLIMATE: Record<ParkRow["climate"], string> = {
  ALTITUDE: "Altitude",
  DOME: "Dome",
  COASTAL: "Coastal",
  TEMPERATE: "Temperate",
};

const WIND: Record<WindBias, string> = {
  "LF→RF": "LF to RF",
  "RF→LF": "RF to LF",
  IN: "In",
  OUT: "Out",
  NEUTRAL: "Neutral",
};

/** Half-width of the tick's domain either side of 1.00 (the fixture spread is about 0.80-1.45). */
const DEV_HALF = 0.45;

function DevTick({ value }: { value: number }) {
  const d = Math.max(-DEV_HALF, Math.min(DEV_HALF, value - 1));
  const w = (Math.abs(d) / DEV_HALF) * 50;
  return (
    <span className="pk-dev" aria-hidden="true">
      <span className="pk-dev__mid" />
      <span
        className="pk-dev__bar"
        style={{ left: `${d >= 0 ? 50 : 50 - w}%`, width: `${w}%` }}
      />
    </span>
  );
}

export function ParkFactorsTable({ rows }: { rows: readonly ParkRow[] }) {
  const sorted = [...rows].sort(
    (a, b) => b.hr - a.hr || a.id.localeCompare(b.id),
  );
  return (
    <table className="ed-agate pk-factors">
      <caption>
        Showcase factors, illustrative (1.00 = league average), sorted by the HR
        factor. The tick runs right of centre above average, left below. On a
        phone only HR, triples and OPS show.
      </caption>
      <thead>
        <tr>
          <th scope="col">Park</th>
          <th scope="col" className="pk-hide-phone">
            Climate
          </th>
          <th scope="col" className="ed-num">
            HR
          </th>
          <th scope="col" className="pk-devcell">
            <VisuallyHidden>HR </VisuallyHidden>vs 1.00
          </th>
          <th scope="col" className="ed-num pk-hide-phone">
            BABIP
          </th>
          <th scope="col" className="ed-num">
            3B
          </th>
          <th scope="col" className="pk-hide-phone pk-wind">
            Wind
          </th>
          <th scope="col" className="ed-num pk-hide-phone">
            K
          </th>
          <th scope="col" className="ed-num">
            OPS
          </th>
        </tr>
      </thead>
      <tbody>
        {sorted.map((r) => (
          <tr key={r.id}>
            <th scope="row" className="pk-factors__park">
              <span className="ed-agate__code pk-hide-phone">{r.id}</span>
              {r.parkName}
            </th>
            <td className="pk-hide-phone pk-faint">{CLIMATE[r.climate]}</td>
            <td className="ed-num">{F2.format(r.hr)}</td>
            <td className="pk-devcell">
              <DevTick value={r.hr} />
            </td>
            <td className="ed-num pk-hide-phone">{F2.format(r.babip)}</td>
            <td className="ed-num">{F2.format(r.triples)}</td>
            <td className="pk-hide-phone pk-wind">{WIND[r.wind]}</td>
            <td className="ed-num pk-hide-phone">{F2.format(r.k)}</td>
            <td className="ed-num">{F2.format(r.ops)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
