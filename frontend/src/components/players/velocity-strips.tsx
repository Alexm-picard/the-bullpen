/**
 * <VelocityStrips> - the pitcher file's hero (SPEC-players 6.4): one row per pitch type, the
 * velocity it covers drawn from min to max on a FIXED 65..105 mph scale (so any two pitchers
 * compare), the average as the ink tick. Every value is printed beside its strip; the strips are
 * aria-hidden and a hidden table carries the same numbers.
 */
import { VisuallyHidden } from "@mantine/core";

import type { ArsenalPitch } from "../../api/players";
import { rankArsenal } from "../../lib/player-dek";
import { pitchName } from "../../lib/pitch-type-prior";

const VELO_DOMAIN_MIN = 65;
const VELO_DOMAIN_MAX = 105;
const SCALE_TICKS = [70, 80, 90, 100];
const COUNT = new Intl.NumberFormat("en-US");

function domainPct(mph: number): number {
  const c = Math.min(Math.max(mph, VELO_DOMAIN_MIN), VELO_DOMAIN_MAX);
  return ((c - VELO_DOMAIN_MIN) / (VELO_DOMAIN_MAX - VELO_DOMAIN_MIN)) * 100;
}

export function VelocityStrips({
  arsenal,
}: {
  arsenal: readonly ArsenalPitch[];
}) {
  const rows = rankArsenal(arsenal);
  return (
    <div>
      <ul className="pl-velo" aria-hidden="true">
        {rows.map((p) => {
          const lo = domainPct(p.veloMinMph);
          const hi = domainPct(p.veloMaxMph);
          return (
            <li className="pl-velo__row" key={p.pitchType}>
              <span className="pl-velo__name">
                {pitchName(p.pitchType)}
                <small>
                  {p.pitchType}, {COUNT.format(p.count)} pitches
                </small>
              </span>
              <span className="pl-range">
                <span className="pl-range__axis" />
                <span
                  className="pl-range__span"
                  style={{
                    left: `${lo}%`,
                    width: `${Math.max(0.5, hi - lo)}%`,
                  }}
                />
                <span
                  className="pl-range__avg"
                  style={{ left: `${domainPct(p.veloAvgMph)}%` }}
                />
              </span>
              <span className="pl-velo__v">
                {p.veloAvgMph.toFixed(1)}
                <small>
                  {p.veloMinMph.toFixed(1)} to {p.veloMaxMph.toFixed(1)}
                </small>
              </span>
            </li>
          );
        })}
      </ul>
      <div className="pl-scale" aria-hidden="true">
        {SCALE_TICKS.map((t) => (
          <span key={t} style={{ left: `${domainPct(t)}%` }}>
            {t}
          </span>
        ))}
      </div>
      <VisuallyHidden>
        <table>
          <caption>Velocity by pitch type, mph</caption>
          <thead>
            <tr>
              <th scope="col">Pitch</th>
              <th scope="col">Pitches</th>
              <th scope="col">Low</th>
              <th scope="col">Average</th>
              <th scope="col">High</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.pitchType}>
                <td>{pitchName(p.pitchType)}</td>
                <td>{COUNT.format(p.count)}</td>
                <td>{p.veloMinMph.toFixed(1)}</td>
                <td>{p.veloAvgMph.toFixed(1)}</td>
                <td>{p.veloMaxMph.toFixed(1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </VisuallyHidden>
    </div>
  );
}
