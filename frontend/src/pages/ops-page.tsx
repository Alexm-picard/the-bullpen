/**
 * /ops — Operator's Marginalia (Stage 3b, decision [133] identity).
 *
 * Replaces the editorial-data tabbed ops dashboard (leaves 4e.1 – 4e.5) with
 * a single-column scouting-report packet back-page. Visual vocabulary lifts
 * from /home's cover sheet and /players/:id's matchup report — same shell,
 * same masthead pattern, same StatTable chrome.
 *
 * Composition order (top → bottom, inside <ReportSheet> shell):
 *   1. <OpsHeader />            — masthead with alert + awaiting-promo counts
 *                                 in the byline strip (locked pick: no triage
 *                                 band; the counts live in the byline)
 *   2. <InfraRibbon />          — navy strip, 5 service chips, non-interactive
 *   3. <ModelFleetTable />      — the hero — fleet w/ p99 inline (locked L3)
 *   4. <DriftSnapshotGrid />    — PSI | ECE Δ subgrid, stacks at <900px
 *   5. <LatencyDetailTable />   — companion table — full per-percentile (L3)
 *   6. <RetrainQueueList />     — 3-row compact list w/ AWAITING-PROMOTION
 *                                 carrying a <abbr title> for rule-6 meaning
 *   7. <OpsLogTable />          — recent ops events as StatTable rows
 *                                 (TIMESTAMP · TYPE · DETAIL) — locked O2
 *   8. <CoverSheetFooter />     — reused, bookends the infra ribbon
 *
 * Fixture-driven (`ops-fixtures.ts`); no API calls. The old ops/*-section.tsx
 * tabs (drift / registry / reliability / retrain-queue / routing) have been
 * removed — they were the only consumers of api/ops.ts on this page.
 *
 * Constraints honored:
 *   - One <Title order={1}> only (the masthead h1).
 *   - No hex codes — every color via tokens or CSS-var utilities.
 *   - No live data fetches; the page is a design-system showcase in v1.
 *   - Reuses CornerStripes + SectionLabel + CoverSheetFooter from shared/.
 */

import { Stack } from "@mantine/core";

import { DriftSnapshotGrid } from "../components/ops/drift-snapshot-grid";
import { InfraRibbon } from "../components/ops/infra-ribbon";
import { LatencyDetailTable } from "../components/ops/latency-detail-table";
import { ModelFleetTable } from "../components/ops/model-fleet-table";
import { OpsHeader } from "../components/ops/ops-header";
import { OpsLogTable } from "../components/ops/ops-log-table";
import { RetrainQueueList } from "../components/ops/retrain-queue-list";
import { CoverSheetFooter } from "../components/scouting/cover-sheet-footer";
import { ReportSheet } from "../components/shared/report-sheet";
import { SectionLabel } from "../components/shared/section-label";
import {
  ECE_BY_OUTPUT,
  INFRA_SERVICES,
  LATENCY_BY_MODEL,
  MODEL_FLEET,
  OPS_LOG,
  OPS_META,
  PSI_BY_FEATURE,
  RETRAIN_QUEUE,
} from "../data/ops-fixtures";

import "./ops/ops.css";

export default function OpsPage() {
  return (
    <ReportSheet>
      <Stack gap={28}>
        <OpsHeader
          issueDate={OPS_META.issueDate}
          modelCount={OPS_META.modelCount}
          alertCount={OPS_META.alertCount}
          awaitingPromotionCount={OPS_META.awaitingPromotionCount}
          issuedAt={OPS_META.issuedAt}
          window={OPS_META.window}
        />

        <InfraRibbon services={INFRA_SERVICES} />

        <section aria-labelledby="ops-fleet-section-label">
          <div id="ops-fleet-section-label">
            <SectionLabel>
              Model Fleet · {OPS_META.modelCount} Registered
            </SectionLabel>
          </div>
          <ModelFleetTable
            rows={MODEL_FLEET}
            caption="Registry · state, traffic, 24h drift + p99 latency"
          />
        </section>

        <DriftSnapshotGrid
          models={MODEL_FLEET}
          psiByFeature={PSI_BY_FEATURE}
          eceByOutput={ECE_BY_OUTPUT}
        />

        <section aria-labelledby="ops-latency-section-label">
          <div id="ops-latency-section-label">
            <SectionLabel>Latency Detail · By Percentile</SectionLabel>
          </div>
          <LatencyDetailTable
            rows={LATENCY_BY_MODEL}
            caption="Latency by percentile · last 24h · onnxruntime-java in-process"
          />
        </section>

        <RetrainQueueList entries={RETRAIN_QUEUE} />

        <section aria-labelledby="ops-log-section-label">
          <div id="ops-log-section-label">
            <SectionLabel>Ops Log · Last 24h Window</SectionLabel>
          </div>
          <OpsLogTable entries={OPS_LOG} />
        </section>

        <CoverSheetFooter
          buildSha={OPS_META.buildSha}
          buildDate={OPS_META.buildDate}
        />
      </Stack>
    </ReportSheet>
  );
}
