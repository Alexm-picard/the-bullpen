/**
 * Sections 3-5 of the /ops register (SPEC-ops 6.5-6.7): the retrain queue, the event log, and the
 * static "watched by" register that replaced the fake service-status ribbon.
 */
import type { OpsLogEntry, RetrainEntry } from "../../api/ops-rows";
import { WATCHED_BY } from "../../data/ops-claims";

import { Breakable } from "./register-section";

export function RetrainTable({
  entries,
}: {
  entries: readonly RetrainEntry[];
}) {
  return (
    <table className="ed-agate" data-testid="ops-retrain">
      <thead>
        <tr>
          <th scope="col">Model, trigger</th>
          <th scope="col" className="op-num op-hide-phone">
            Queued
          </th>
          <th scope="col" className="op-num">
            Ran
          </th>
          <th scope="col" className="op-pad">
            Status
          </th>
        </tr>
      </thead>
      <tbody>
        {entries.map((e) => (
          <tr key={e.id}>
            <td>
              <Breakable name={e.modelLabel} />
              <span className="op-sub">{`${e.trigger.toLowerCase()} trigger${e.reason ? `, ${e.reason}` : ""}`}</span>
            </td>
            <td className="op-num op-hide-phone op-faint">{e.queuedAt}</td>
            <td className="op-num">{e.ranAt}</td>
            <td
              className={
                e.status === "Awaiting promotion"
                  ? "op-pad op-word op-strong"
                  : "op-pad op-word"
              }
            >
              {e.status}
            </td>
          </tr>
        ))}
      </tbody>
      <caption>
        Retraining is automatic; promotion is not. A finished job waits for a
        person and a passing evidence row.
      </caption>
    </table>
  );
}

export function EventLogTable({ rows }: { rows: readonly OpsLogEntry[] }) {
  return (
    <table className="ed-agate" data-testid="ops-log">
      <thead>
        <tr>
          <th scope="col">When, ET</th>
          <th scope="col">Event</th>
          <th scope="col">What happened</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id}>
            <td className="op-when">{r.when}</td>
            <td className="op-evt">{r.label}</td>
            <td className="op-detail">{r.detail}</td>
          </tr>
        ))}
      </tbody>
      <caption>
        Newest first, 20 to a page. Every row is written by the system itself,
        not by hand.
      </caption>
    </table>
  );
}

export function WatchedByTable() {
  return (
    <>
      <p className="ed-body">
        This page cannot see whether the services are up, so it does not say. It
        says where each one is watched instead.
      </p>
      <table
        className="ed-agate"
        data-testid="ops-watched"
        style={{ marginTop: "1rem" }}
      >
        <thead>
          <tr>
            <th scope="col">Service</th>
            <th scope="col">Watched by</th>
          </tr>
        </thead>
        <tbody>
          {WATCHED_BY.map((w) => (
            <tr key={w.service}>
              <td className="op-detail">{w.service}</td>
              <td>{w.watchedBy}</td>
            </tr>
          ))}
        </tbody>
        <caption>
          Prometheus alert rules route to Discord; public reachability is
          checked from outside by Uptime Robot.
        </caption>
      </table>
    </>
  );
}
