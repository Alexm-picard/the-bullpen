/**
 * Ops dashboard API client (leaves 4e.1 + 4e.2 + 4e.3 + 4e.4 + 4e.5).
 *
 * Five thin GETs, all under {@code /v1/ops/*} — public reads, no auth.
 * Hooks default to a 30-second staleTime since dashboard data doesn't change
 * by the second (drift / routing / queue are all on minutes-to-hours scales).
 */
import { useQuery } from "@tanstack/react-query";

import type { OpsLogEntry, OpsLogType } from "../data/ops-fixtures";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export type ModelVersion = {
  id: number;
  modelName: string;
  version: string;
  artifactPath: string;
  metadataPath: string;
  trainingDataHash: string;
  trainingDataWindow: string;
  featureSchemaHash: string;
  evalMetrics: string;
  trainedAt: string;
  promotedAt: string | null;
  stage: string;
  createdBy: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
};

export type DriftMetric = {
  computedAt: string;
  modelName: string;
  modelVersionId: number;
  metricType: string;
  featureOrSegment: string;
  metricValue: number;
  sampleSize: number;
  windowStart: string;
  windowEnd: string;
};

export type RoutingConfig = {
  id: number;
  modelName: string;
  championVersionId: number;
  challengerVersionId: number | null;
  challengerTrafficPct: number;
  mode: string;
  updatedAt: string;
};

export type RetrainingTrigger = {
  id: number;
  triggerId: string;
  modelName: string;
  triggerType: string;
  triggerMetadata: string | null;
  status: string;
  enqueuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  producedVersionId: number | null;
  errorMessage: string | null;
};

export class OpsApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new OpsApiError(res.status, `${path} failed: HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

// --- 4e.1 ----------------------------------------------------------------

export const fetchAllModelNames = () => get<string[]>("/v1/ops/registry");
export const fetchRegistryRows = (modelName: string) =>
  get<ModelVersion[]>(`/v1/ops/registry/${encodeURIComponent(modelName)}`);
/** Every registered version across all models, one round-trip — feeds the Model Fleet table. */
export const fetchAllRegistryRows = () =>
  get<ModelVersion[]>("/v1/ops/registry/all");

export function useAllRegistryRows() {
  return useQuery<ModelVersion[], OpsApiError>({
    queryKey: ["ops", "registry", "all"],
    queryFn: fetchAllRegistryRows,
    staleTime: 30_000,
  });
}

export function useAllModelNames() {
  return useQuery<string[], OpsApiError>({
    queryKey: ["ops", "modelNames"],
    queryFn: fetchAllModelNames,
    staleTime: 60_000,
  });
}

export function useRegistryRows(modelName: string | null) {
  return useQuery<ModelVersion[], OpsApiError>({
    queryKey: ["ops", "registry", modelName],
    queryFn: () => {
      if (modelName == null) throw new Error("modelName required");
      return fetchRegistryRows(modelName);
    },
    enabled: modelName != null,
    staleTime: 30_000,
  });
}

// --- 4e.2 ----------------------------------------------------------------

export const fetchDrift = (modelName: string) =>
  get<DriftMetric[]>(`/v1/ops/drift?model=${encodeURIComponent(modelName)}`);

export function useDrift(modelName: string | null) {
  return useQuery<DriftMetric[], OpsApiError>({
    queryKey: ["ops", "drift", modelName],
    queryFn: () => {
      if (modelName == null) throw new Error("modelName required");
      return fetchDrift(modelName);
    },
    enabled: modelName != null,
    staleTime: 30_000,
    refetchInterval: 30_000,
  });
}

// --- 4e.3 ----------------------------------------------------------------

export const fetchRouting = () => get<RoutingConfig[]>("/v1/ops/routing");

export function useRouting() {
  return useQuery<RoutingConfig[], OpsApiError>({
    queryKey: ["ops", "routing"],
    queryFn: fetchRouting,
    staleTime: 30_000,
  });
}

// --- 4e.4 ----------------------------------------------------------------

export const fetchRetrainQueue = (modelName?: string) =>
  get<RetrainingTrigger[]>(
    modelName
      ? `/v1/ops/retrain?model=${encodeURIComponent(modelName)}`
      : "/v1/ops/retrain",
  );

export function useRetrainQueue(modelName?: string) {
  return useQuery<RetrainingTrigger[], OpsApiError>({
    queryKey: ["ops", "retrain", modelName ?? "_all"],
    queryFn: () => fetchRetrainQueue(modelName),
    staleTime: 30_000,
    refetchInterval: 30_000,
  });
}

// --- 4e.5 ----------------------------------------------------------------

export const fetchCalibrationSummary = () =>
  get<Record<string, string>>("/v1/ops/calibration-summary");

export function useCalibrationSummary() {
  return useQuery<Record<string, string>, OpsApiError>({
    queryKey: ["ops", "calibration-summary"],
    queryFn: fetchCalibrationSummary,
    staleTime: 60_000,
  });
}

// --- C: per-model serving latency (GET /v1/ops/latency) -----------------

/**
 * One row from {@code GET /v1/ops/latency} — p50/p95/p99 serving latency (ms)
 * computed from {@code prediction_log.latency_ms}, one per registered model
 * version that served a logged prediction in the window. The first real
 * (non-fixture) latency on the Ops dashboard.
 */
export type LatencyStat = {
  modelName: string;
  modelVersion: string;
  sampleCount: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  p999Ms: number;
};

export const fetchLatency = (days = 7) =>
  get<LatencyStat[]>(`/v1/ops/latency?days=${days}`);

export function useLatency(days = 7) {
  return useQuery<LatencyStat[], OpsApiError>({
    queryKey: ["ops", "latency", days],
    queryFn: () => fetchLatency(days),
    staleTime: 30_000,
    refetchInterval: 30_000,
  });
}

// --- B3: ops-event log (live Ops Log) -----------------------------------

/** One row from {@code GET /v1/ops/events}. {@code type} is the backend enum name. */
export type OpsEvent = {
  id: number;
  occurredAt: string; // ISO-8601
  type: string; // e.g. "PROMOTE", "REGISTER", "DRIFT_OK"
  detail: string;
};

export const fetchOpsEvents = (limit = 20) =>
  get<OpsEvent[]>(`/v1/ops/events?limit=${limit}`);

export function useOpsEvents(limit = 20) {
  return useQuery<OpsEvent[], OpsApiError>({
    queryKey: ["ops", "events", limit],
    queryFn: () => fetchOpsEvents(limit),
    staleTime: 30_000,
    refetchInterval: 30_000,
  });
}

const OPS_EVENT_ET_FMT = new Intl.DateTimeFormat("en-US", {
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
  hour12: false,
  timeZone: "America/New_York",
});

/**
 * Map a backend {@link OpsEvent} to the {@link OpsLogEntry} shape the OpsLogTable renders. Backend
 * underscore type names become the frontend's hyphenated display labels (DRIFT_OK → DRIFT-OK), and
 * the ISO instant becomes a short ET display string.
 */
export function opsEventToLogEntry(e: OpsEvent): OpsLogEntry {
  return {
    id: `oe-${e.id}`,
    timestamp: `${OPS_EVENT_ET_FMT.format(new Date(e.occurredAt))} ET`,
    type: e.type.replace(/_/g, "-") as OpsLogType,
    detail: e.detail,
  };
}
