/**
 * Section 1, Serving (SPEC-ops 5 / 6.2): the live fleet, the offline roster, and latency.
 * Data in ink; stage words are the registry's own; every missing figure is an em-dash.
 */
import type { FleetRow, LatencyRow } from "../../api/ops-rows";
import { ROSTER, ROSTER_VERIFIED, LATENCY_SLO } from "../../data/ops-claims";
import { fmtMs } from "../../lib/ops-status";

import { DASH } from "./ops-format";
import { Breakable, Fresh } from "./register-section";

const COUNT = new Intl.NumberFormat("en-US");

function FleetHead() {
  return (
    <thead>
      <tr>
        <th scope="col">Model</th>
        <th scope="col" className="op-pad">
          Stage
        </th>
        <th scope="col" className="op-num op-hide-phone">
          Traffic
        </th>
        <th scope="col" className="op-num">
          Calls, 24 h
        </th>
        <th scope="col" className="op-num">
          p99 ms
        </th>
        <th scope="col" className="op-num op-hide-phone">
          Trained
        </th>
      </tr>
    </thead>
  );
}

function ModelCell({ name, version }: { name: string; version: string }) {
  return (
    <td className="op-model">
      <Breakable name={name} /> <span className="op-faint">{version}</span>
    </td>
  );
}

function StageCell({ stage }: { stage: FleetRow["stage"] }) {
  return (
    <td className="op-pad">
      <span
        className={
          stage === "Champion" ? "op-stage op-stage--champion" : "op-stage"
        }
      >
        {stage}
      </span>
    </td>
  );
}

export function FleetTable({ rows }: { rows: readonly FleetRow[] }) {
  return (
    <table className="ed-agate" data-testid="ops-fleet">
      <FleetHead />
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.modelName} ${r.version}`}>
            <ModelCell name={r.modelName} version={r.version} />
            <StageCell stage={r.stage} />
            <td className="op-num op-hide-phone">
              {r.traffic === "none" ? (
                <span className="op-faint">none</span>
              ) : (
                r.traffic
              )}
            </td>
            <td className="op-num">
              {r.calls24h === null ? (
                <span className="op-faint">{DASH}</span>
              ) : (
                <Fresh value={COUNT.format(r.calls24h)} />
              )}
            </td>
            <td className="op-num">
              {r.p99Ms === null ? (
                <span className="op-faint">{DASH}</span>
              ) : (
                <Fresh value={fmtMs(r.p99Ms)} />
              )}
            </td>
            <td className="op-num op-hide-phone op-faint">
              {r.trained ?? DASH}
            </td>
          </tr>
        ))}
      </tbody>
      <caption>
        Stage is the registry&rsquo;s own word. Traffic is the user-facing share
        from the A/B router; shadows and candidates serve nobody.
      </caption>
    </table>
  );
}

/** Backend unreachable: the verified roster, every figure em-dashed ([202]). */
export function RosterTable() {
  return (
    <table className="ed-agate" data-testid="ops-roster">
      <FleetHead />
      <tbody>
        {ROSTER.map((r) => (
          <tr key={`${r.modelName} ${r.version}`}>
            <ModelCell name={r.modelName} version={r.version} />
            <StageCell stage={r.stage} />
            <td className="op-num op-hide-phone op-faint">{DASH}</td>
            <td className="op-num op-faint">{DASH}</td>
            <td className="op-num op-faint">{DASH}</td>
            <td className="op-num op-hide-phone op-faint">{DASH}</td>
          </tr>
        ))}
      </tbody>
      <caption>
        {`Roster as verified against the registry on ${ROSTER_VERIFIED}. With the backend unreachable no figure is shown; a showcase never reads as a reading.`}
      </caption>
    </table>
  );
}

export function LatencyTable({ rows }: { rows: readonly LatencyRow[] }) {
  return (
    <table className="ed-agate" data-testid="ops-latency">
      <thead>
        <tr>
          <th scope="col">Model</th>
          <th scope="col" className="op-num op-hide-phone">
            Calls
          </th>
          <th scope="col" className="op-num">
            p50
          </th>
          <th scope="col" className="op-num">
            p95
          </th>
          <th scope="col" className="op-num">
            p99
          </th>
          <th scope="col" className="op-num">
            p99.9
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.label}>
            <td className="op-model">
              <Breakable name={r.label} />
            </td>
            <td className="op-num op-hide-phone op-faint">
              {COUNT.format(r.calls)}
            </td>
            <td className="op-num">
              <Fresh value={fmtMs(r.p50)} />
            </td>
            <td className="op-num">
              <Fresh value={fmtMs(r.p95)} />
            </td>
            <td className="op-num op-strong">
              <Fresh value={fmtMs(r.p99)} />
            </td>
            <td className="op-num">
              <Fresh value={fmtMs(r.p999)} />
            </td>
          </tr>
        ))}
      </tbody>
      <caption>
        {`Milliseconds, last 7 days, ONNX Runtime in the API process. p99 is set heavier because the latency alert watches it (${LATENCY_SLO.alert}, over ${LATENCY_SLO.p99Ms} ms).`}
      </caption>
    </table>
  );
}
