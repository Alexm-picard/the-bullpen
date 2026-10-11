/**
 * /ops - "The systems register" (SPEC-ops; decision [195] editorial identity).
 *
 * One numbered section per subsystem around the four champion models. Each section says where its
 * numbers come from (a source stamp whose state is a WORD: Live / Live, empty / Live, stale /
 * Showcase data / Static) and which threshold it is held to (the provenance margin, a disclosure
 * on phone). The contents list carries one rule-generated status line per section
 * (lib/ops-status.ts).
 *
 * Data (existing hooks only, TanStack Query, polling, no useEffect for server state):
 *   Serving ....... GET /v1/ops/registry/all x /routing x /latency?days=1 (fleet), ?days=7 (latency)
 *   Drift ......... GET /v1/ops/drift?model= per non-archived model -> toDriftCells
 *   Retraining .... GET /v1/ops/retrain (+ the registry, for the promotion-stage check)
 *   Event log ..... GET /v1/ops/events?page=0&size=20
 *   Infrastructure  static "watched by" claims (data/ops-claims.ts); no status endpoint exists.
 *
 * Honesty ([202]): when a source cannot be read, nothing invented takes its place. The fleet
 * falls back to the verified roster with every figure em-dashed; latency, queue and log say they
 * could not be read. A resolved EMPTY response is shown as empty, never backfilled.
 *
 * No hex: every colour is an --ed-* token through the .ed-* / .op-* classes in editorial.css.
 */
import { useEffect, useMemo, useState } from "react";

import {
  useAllRegistryRows,
  useDriftForModels,
  useLatency,
  useOpsEvents,
  useRetrainQueue,
  useRouting,
} from "../../api/ops";
import {
  opsEventToLogEntry,
  toDriftCells,
  toFleetRows,
  toLatencyRows,
  toRetrainEntries,
} from "../../api/ops-mappers";
import { BUILD_DATE, BUILD_SHA } from "../../build-info";
import {
  DriftKey,
  DrillNotice,
  EceTable,
  PsiTable,
} from "../../components/ops/drift-tables";
import { readTime } from "../../components/ops/ops-format";
import {
  RegisterSection,
  SourceStamp,
  type StampState,
} from "../../components/ops/register-section";
import {
  EventLogTable,
  RetrainTable,
  WatchedByTable,
} from "../../components/ops/register-tables";
import {
  FleetTable,
  LatencyTable,
  RosterTable,
} from "../../components/ops/serving-tables";
import {
  DRIFT_SCHEDULE,
  DRIFT_THRESHOLDS,
  LATENCY_SLO,
  WATCHED_ECE,
  WATCHED_PSI,
} from "../../data/ops-claims";
import {
  driftStatus,
  logStatus,
  retrainStatus,
  sectionsAnsweredLine,
  servingStatus,
  sourceState,
  type SourceState,
} from "../../lib/ops-status";

/** Module-level: the edition "arrives" once per session, as on the other [195] pages. */
let entranceShown = false;

const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "long",
  month: "long",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

const T = DRIFT_THRESHOLDS;

const Code = ({ children }: { children: string }) => <code>{children}</code>;

/** A section body that could not be read, or has nothing yet: one honest sentence. */
const Note = ({ children }: { children: string }) => (
  <p className="ed-note">{children}</p>
);

const Shell = ({ rows }: { rows: number }) => (
  <div
    className="ed-shell"
    aria-hidden="true"
    style={{ height: `${rows * 2.125}rem` }}
  />
);

/** Contents state word: what answered, never a verdict. */
function stateWord(s: StampState): string {
  switch (s) {
    case "loading":
      return "Loading";
    case "error":
      return "Showcase";
    case "static":
      return "Static";
    default:
      return "Live";
  }
}

export default function OpsPage() {
  const [animate] = useState(() => !entranceShown);
  useEffect(() => {
    entranceShown = true;
  }, []);

  const registry = useAllRegistryRows();
  const routing = useRouting();
  const latency24h = useLatency(1);
  const latency7d = useLatency(7);
  const retrain = useRetrainQueue();
  const events = useOpsEvents();

  const driftModelNames = useMemo(
    () => [
      ...new Set(
        (registry.data ?? [])
          .filter((v) => v.stage.toUpperCase() !== "ARCHIVED")
          .map((v) => v.modelName),
      ),
    ],
    [registry.data],
  );
  const drift = useDriftForModels(driftModelNames);

  const now = new Date();

  // ── 1. Serving ──────────────────────────────────────────────────────────
  const fleet = registry.data
    ? toFleetRows(registry.data, routing.data ?? [], latency24h.data ?? [])
    : undefined;
  const servingState = sourceState(
    { data: fleet, isError: registry.isError },
    (rows) => rows.length === 0,
  );
  const latencyRows = latency7d.data
    ? toLatencyRows(latency7d.data)
    : undefined;
  const latencyState = sourceState(
    { data: latencyRows, isError: latency7d.isError },
    (rows) => rows.length === 0,
  );

  // ── 2. Drift ────────────────────────────────────────────────────────────
  const cells = toDriftCells(drift.metrics, WATCHED_PSI, WATCHED_ECE);
  let driftState: SourceState;
  if (servingState === "error" || drift.isError) driftState = "error";
  else if (servingState === "loading" || drift.isLoading)
    driftState = "loading";
  else if (drift.isStale) driftState = "stale";
  else driftState = drift.metrics.length === 0 ? "empty" : "live";
  const driftOffline = driftState === "error";

  // ── 3. Retraining ───────────────────────────────────────────────────────
  const retrainEntries = retrain.data
    ? toRetrainEntries(retrain.data, registry.data ?? [], now)
    : undefined;
  const retrainState = sourceState(
    { data: retrainEntries, isError: retrain.isError },
    (rows) => rows.length === 0,
  );

  // ── 4. Event log ────────────────────────────────────────────────────────
  const logPage = events.data
    ? {
        rows: events.data.rows.map((e) => opsEventToLogEntry(e, now)),
        hasNext: events.data.hasNext,
      }
    : undefined;
  const logState = sourceState(
    { data: logPage, isError: events.isError },
    (p) => p.rows.length === 0,
  );

  const contents: {
    id: string;
    name: string;
    state: StampState;
    status: string;
  }[] = [
    {
      id: "ops-serving",
      name: "Serving",
      state: servingState,
      status: servingStatus(servingState, fleet, latencyRows),
    },
    {
      id: "ops-drift",
      name: "Drift",
      state: driftState,
      status: driftStatus(driftState, cells.psi, cells.ece, T),
    },
    {
      id: "ops-retraining",
      name: "Retraining",
      state: retrainState,
      status: retrainStatus(retrainState, retrainEntries),
    },
    {
      id: "ops-log",
      name: "Event log",
      state: logState,
      status: logStatus(logState, logPage, now),
    },
    {
      id: "ops-infrastructure",
      name: "Infrastructure",
      state: "static",
      status: "No status endpoint; where each service is watched",
    },
  ];

  const lastRead = Math.max(
    registry.dataUpdatedAt,
    latency7d.dataUpdatedAt,
    retrain.dataUpdatedAt,
    events.dataUpdatedAt,
    drift.dataUpdatedAt,
  );

  const enter = (base: string, i: number) =>
    animate
      ? {
          className: `${base} ed-enter`,
          style: { "--ed-stagger": i } as React.CSSProperties,
        }
      : { className: base };

  return (
    <div className="ed-page">
      <div className="ed-col">
        <p className="ed-dateline">
          <span>{ET_DATE.format(now)}</span>
          <span className="ed-dateline__issued">
            {lastRead > 0 ? `Ops desk, read ${readTime(lastRead)}` : "Ops desk"}
          </span>
          <span>
            {sectionsAnsweredLine([
              servingState,
              driftState,
              retrainState,
              logState,
            ])}
          </span>
        </p>

        <div className="op-head">
          <div>
            <p {...enter("ed-slug", 0)}>Ops</p>
            <h1 {...enter("ed-h1", 1)}>The systems register</h1>
            <p {...enter("ed-dek", 2)}>
              One section for each system around the four champion models. Each
              says where its numbers come from and which threshold it is held
              to.
            </p>
          </div>
          <nav aria-labelledby="ops-toc-h" {...enter("", 3)}>
            <p className="ed-label" id="ops-toc-h">
              In this register
            </p>
            <ol className="op-toc">
              {contents.map((c, i) => (
                <li key={c.id}>
                  <span className="op-toc__n" aria-hidden="true">
                    {i + 1}
                  </span>
                  <span>
                    <a className="ed-link op-toc__name" href={`#${c.id}`}>
                      {c.name}
                    </a>
                    <span className="op-toc__status">{c.status}</span>
                  </span>
                  <span className="op-toc__state" data-state={c.state}>
                    {stateWord(c.state)}
                  </span>
                </li>
              ))}
            </ol>
          </nav>
        </div>

        <RegisterSection
          n={1}
          id="ops-serving"
          title="Serving"
          stamp={
            <SourceStamp
              state={servingState}
              source="registry x routing x latency"
              updatedAt={registry.dataUpdatedAt}
            />
          }
          provenance={[
            {
              term: "Reads",
              detail: (
                <>
                  <Code>/v1/ops/registry/all</Code>, <Code>/routing</Code>,{" "}
                  <Code>/latency?days=1</Code> and <Code>?days=7</Code>
                </>
              ),
            },
            {
              term: "Held to",
              detail: `p99 latency alert over ${LATENCY_SLO.p99Ms} ms (${LATENCY_SLO.alert}); a champion changes only by a human promotion with a passing evidence row.`,
            },
          ]}
        >
          <div className="op-tables">
            {servingState === "loading" ? (
              <Shell rows={7} />
            ) : servingState === "error" ? (
              <RosterTable />
            ) : servingState === "empty" || !fleet ? (
              <Note>The registry returned no models.</Note>
            ) : (
              <FleetTable rows={fleet} />
            )}
            {latencyState === "loading" ? (
              <Shell rows={5} />
            ) : latencyState === "error" ? (
              <Note>
                Latency could not be read. Nothing is shown in its place.
              </Note>
            ) : latencyState === "empty" || !latencyRows ? (
              <Note>No predictions logged in the last 7 days.</Note>
            ) : (
              <LatencyTable rows={latencyRows} />
            )}
          </div>
        </RegisterSection>

        <RegisterSection
          n={2}
          id="ops-drift"
          title="Drift"
          stamp={
            <SourceStamp
              state={driftState}
              source="drift_metrics"
              updatedAt={drift.dataUpdatedAt}
            />
          }
          provenance={[
            {
              term: "Reads",
              detail: (
                <>
                  <Code>/v1/ops/drift?model=</Code>, one per non-archived model,
                  every 30 s
                </>
              ),
            },
            { term: "Written", detail: DRIFT_SCHEDULE },
            {
              term: "Held to",
              detail: `PSI notice over ${T.psiNotice.toFixed(2)} for ${T.psiNoticeDays} days at n of ${T.psiMinSample} or more; ECE page over ${T.ecePage.toFixed(2)} for ${T.ecePageDays} days.`,
            },
          ]}
        >
          <div className="op-tables">
            <div>
              <DriftKey />
              {driftOffline ? null : <DrillNotice psi={cells.psi} />}
            </div>
            <PsiTable groups={cells.psi} offline={driftOffline} />
            <EceTable cells={cells.ece} offline={driftOffline} />
          </div>
        </RegisterSection>

        <RegisterSection
          n={3}
          id="ops-retraining"
          title="Retraining"
          stamp={
            <SourceStamp
              state={retrainState}
              source="retraining queue"
              updatedAt={retrain.dataUpdatedAt}
            />
          }
          provenance={[
            {
              term: "Reads",
              detail: (
                <>
                  <Code>/v1/ops/retrain</Code>, every 30 s
                </>
              ),
            },
            {
              term: "Held to",
              detail:
                "Triggers are automatic (drift, schedule); promotion is never automatic.",
            },
          ]}
        >
          {retrainState === "loading" ? (
            <Shell rows={2} />
          ) : retrainState === "error" ? (
            <Note>
              The retrain queue could not be read. Nothing is shown in its
              place.
            </Note>
          ) : retrainState === "empty" || !retrainEntries ? (
            <Note>
              The queue is empty. Nothing is training or waiting for promotion.
            </Note>
          ) : (
            <RetrainTable entries={retrainEntries} />
          )}
        </RegisterSection>

        <RegisterSection
          n={4}
          id="ops-log"
          title="Event log"
          stamp={
            <SourceStamp
              state={logState}
              source="ops_events"
              updatedAt={events.dataUpdatedAt}
            />
          }
          provenance={[
            {
              term: "Reads",
              detail: (
                <>
                  <Code>/v1/ops/events?page=0&size=20</Code>, every 30 s
                </>
              ),
            },
            {
              term: "Written by",
              detail:
                "deploy.sh, the registry, the drift and retrain jobs, the restore drill.",
            },
          ]}
        >
          {logState === "loading" ? (
            <Shell rows={6} />
          ) : logState === "error" ? (
            <Note>
              The event log could not be read. No invented events are shown in
              its place.
            </Note>
          ) : logState === "empty" || !logPage ? (
            <Note>No events recorded yet.</Note>
          ) : (
            <EventLogTable rows={logPage.rows} />
          )}
        </RegisterSection>

        <RegisterSection
          n={5}
          id="ops-infrastructure"
          title="Infrastructure"
          stamp={<SourceStamp state="static" source="no status endpoint yet" />}
        >
          <WatchedByTable />
        </RegisterSection>

        <footer className="ed-footer">
          <span>The Bullpen. Self-hosted, honestly scored.</span>
          <span>
            build {BUILD_SHA}, {BUILD_DATE}
          </span>
        </footer>
      </div>
    </div>
  );
}
