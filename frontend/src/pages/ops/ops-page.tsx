/**
 * /ops — Operator's Marginalia (Stage 3b, decision [133] identity).
 *
 * Single-column scouting-report back-page over the ML-systems wrapper. Visual
 * vocabulary lifts from /home's cover sheet — same shell, masthead, StatTable
 * chrome.
 *
 * Data sourcing (C3, Threshold A):
 *   - Model Fleet ........ LIVE — registry × routing × latency
 *     (GET /v1/ops/registry/all + /v1/ops/routing + /v1/ops/latency?days=1),
 *     mapped by ops-mappers.toFleetRows.
 *   - Latency Detail ..... LIVE — GET /v1/ops/latency?days=7 → toLatencyRows.
 *   - Retrain Queue ...... LIVE — GET /v1/ops/retrain → toRetrainEntries
 *     (built-in empty state when the queue is empty).
 *   - Ops Log ............ LIVE — GET /v1/ops/events (B3).
 *   - Drift Snapshot ..... watched-surface skeleton with honest em-dashes —
 *     PSI / ECE populate once the nightly drift jobs run in-season (Threshold
 *     B). We never render the old illustrative drift numbers as if real (C4).
 *   - Infra Ribbon ....... showcase chrome (service status has no endpoint yet).
 *
 * Fixtures (`ops-fixtures.ts`) are the fallback ONLY when the backend is
 * unreachable / has no registered models, and that case is marked in the table
 * captions so a showcase render never reads as live (C4).
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 */

import { useMemo } from "react";

import {
  opsEventToLogEntry,
  useAllRegistryRows,
  useDriftForModels,
  useLatency,
  useOpsEvents,
  useRetrainQueue,
  useRouting,
} from "../../api/ops";
import {
  toDriftRows,
  toFleetRows,
  toLatencyRows,
  toRetrainEntries,
} from "../../api/ops-mappers";
import { LowerThird } from "../../components/broadcast/lower-third";
import { DriftSnapshotGrid } from "../../components/ops/drift-snapshot-grid";
import { InfraRibbon } from "../../components/ops/infra-ribbon";
import { LatencyDetailTable } from "../../components/ops/latency-detail-table";
import { ModelFleetTable } from "../../components/ops/model-fleet-table";
import { OpsHeader } from "../../components/ops/ops-header";
import { OpsLogTable } from "../../components/ops/ops-log-table";
import { RetrainQueueList } from "../../components/ops/retrain-queue-list";
import {
  ECE_BY_OUTPUT,
  INFRA_SERVICES,
  LATENCY_BY_MODEL,
  MODEL_FLEET,
  OPS_LOG,
  OPS_META,
  PSI_BY_FEATURE,
  RETRAIN_QUEUE,
} from "../../data/ops-fixtures";
import type { DriftFeatureRow, DriftOutputRow } from "../../data/ops-fixtures";
import {
  BroadcastFooter,
  PageChrome,
} from "../../components/shared/page-chrome";
import { colors, typography } from "../../design/broadcast";

import "./ops.css";

const ET_TIME = new Intl.DateTimeFormat("en-US", {
  hour: "numeric",
  minute: "2-digit",
  hour12: false,
  timeZone: "America/New_York",
});
const ET_DATE = new Intl.DateTimeFormat("en-US", {
  weekday: "short",
  month: "short",
  day: "numeric",
  year: "numeric",
  timeZone: "America/New_York",
});

// Watched-surface skeletons: keep WHICH features/outputs are monitored (config,
// not data) but null the values, so the grid renders the drift surface with
// honest em-dashes until the first in-season sweep populates it.
const PSI_SKELETON: DriftFeatureRow[] = PSI_BY_FEATURE.map((f) => ({
  feature: f.feature,
  byModel: {},
}));
const ECE_SKELETON: DriftOutputRow[] = ECE_BY_OUTPUT.map((o) => ({
  output: o.output,
  byModel: {},
}));

const noteStyle: React.CSSProperties = {
  margin: "0 0 8px",
  fontFamily: typography.fonts.body,
  fontSize: 13,
  color: colors.textMuted,
};

export default function OpsPage() {
  const registry = useAllRegistryRows();
  const routing = useRouting();
  const latency24h = useLatency(1); // fleet p99 + 24h prediction counts
  const latency7d = useLatency(7); // latency-detail percentile table
  const retrain = useRetrainQueue();
  const opsEvents = useOpsEvents();

  // Drift: one query per non-archived model name (the endpoint requires ?model=).
  // Memoised so the queryKey set only changes when the registry roster changes.
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

  // Fleet: live registry × routing × latency. Fall back to the showcase fixture
  // only when the registry call returned nothing (offline / empty registry).
  const liveFleet =
    registry.data && registry.data.length > 0
      ? toFleetRows(registry.data, routing.data ?? [], latency24h.data ?? [])
      : null;
  const fleet = liveFleet ?? MODEL_FLEET;
  const fleetIsLive = liveFleet !== null;

  const liveLatency =
    latency7d.data && latency7d.data.length > 0
      ? toLatencyRows(latency7d.data)
      : null;
  const latencyRows = liveLatency ?? LATENCY_BY_MODEL;
  const latencyIsLive = liveLatency !== null;

  // Retrain: render real data whenever the query resolved (an empty array is a
  // legitimate "queue empty" state via the list's built-in empty path); only
  // fall back to the fixture when the call never resolved (offline).
  const retrainIsLive = retrain.data !== undefined;
  const retrainEntries = retrainIsLive
    ? toRetrainEntries(retrain.data ?? [])
    : RETRAIN_QUEUE;

  // Ops log: live whenever the query resolved - an empty list is a legitimate
  // "no events yet" state (the table has its own empty path), never the fixture.
  const opsLogIsLive = opsEvents.data !== undefined;
  const opsLog = opsLogIsLive
    ? opsEvents.data.rows.map(opsEventToLogEntry)
    : OPS_LOG;

  // Drift snapshot: live values overlaid on the watched-surface skeleton.
  // While drift_metrics is empty (no traffic yet) this renders the same
  // honest em-dash grid the skeleton did. drillTags labels [175] induced-drill
  // evidence rows so a synthetic PSI spike is never presented as organic (E-4).
  const { psiByFeature, eceByOutput, drillTags } = toDriftRows(
    drift.metrics,
    PSI_SKELETON,
    ECE_SKELETON,
  );

  const now = new Date();
  const issuedAt = `${ET_TIME.format(now)} ET`;
  const issueDate = ET_DATE.format(now).replace(",", " ·");
  const alertCount = opsLog.filter((e) => e.type === "ALERT").length;
  const awaitingPromotionCount = fleet.filter(
    (m) => m.state === "AWAITING-PROMOTION",
  ).length;

  const showcaseSuffix = (live: boolean) =>
    live ? "" : " · showcase data (backend unreachable)";

  return (
    <PageChrome>
      <OpsHeader
        issueDate={issueDate}
        modelCount={fleet.length}
        alertCount={alertCount}
        awaitingPromotionCount={awaitingPromotionCount}
        issuedAt={issuedAt}
        window={OPS_META.window}
      />

      <InfraRibbon services={INFRA_SERVICES} />

      <section aria-labelledby="ops-fleet-section-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird
            id="ops-fleet-section-label"
            meta={`${fleet.length} REGISTERED`}
          >
            Model Fleet
          </LowerThird>
        </div>
        <ModelFleetTable
          rows={fleet}
          caption={`Registry · state, traffic, 24h drift + p99 latency${showcaseSuffix(fleetIsLive)}`}
        />
      </section>

      <section aria-labelledby="ops-drift-section-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird
            id="ops-drift-section-label"
            meta="PSI | ECE · NIGHTLY WINDOWS"
          >
            Drift Snapshot
          </LowerThird>
        </div>
        <p style={noteStyle}>
          Monitored surface shown; cells fill from the nightly drift jobs
          (champions and shadows both watched) as predictions accumulate.
        </p>
        {drillTags.length > 0 && (
          // role="status": announced to screen readers when a drill tag appears
          // mid-session (the 30s poll can flip this on). goldInk + 600 weight
          // out-ranks the neutral note above - during a drill this is the most
          // important sentence on the panel (frontend-reviewer, E-4).
          <p
            role="status"
            style={{ ...noteStyle, color: colors.goldInk, fontWeight: 600 }}
          >
            Includes induced-drill evidence rows (deliberate synthetic drift,
            decision [175]; tag: {drillTags.join(", ")}) - not organic
            production drift.
          </p>
        )}
        <DriftSnapshotGrid
          models={fleet}
          psiByFeature={psiByFeature}
          eceByOutput={eceByOutput}
        />
      </section>

      <section aria-labelledby="ops-latency-section-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird id="ops-latency-section-label" meta="BY PERCENTILE">
            Latency Detail
          </LowerThird>
        </div>
        <LatencyDetailTable
          rows={latencyRows}
          caption={`Latency by percentile · onnxruntime-java in-process${showcaseSuffix(latencyIsLive)}`}
        />
      </section>

      <RetrainQueueList entries={retrainEntries} />

      <section aria-labelledby="ops-log-section-label">
        <div style={{ marginBottom: 12 }}>
          <LowerThird
            id="ops-log-section-label"
            meta={
              opsLogIsLive
                ? "RECENT EVENTS"
                : "SHOWCASE DATA (BACKEND UNREACHABLE)"
            }
          >
            Ops Log
          </LowerThird>
        </div>
        <OpsLogTable entries={opsLog} />
      </section>

      <BroadcastFooter>OPS</BroadcastFooter>
    </PageChrome>
  );
}
