/**
 * Park Explorer API client (leaf 4c.2+).
 *
 * One endpoint: POST /v1/predict/batted-ball/all-parks. The response is a
 * 30-entry map keyed by park id with the model's P(HR) for the given launch
 * parameters at that park.
 */
import { useQuery } from "@tanstack/react-query";

import { API_BASE } from "./base";

export type AllParksRequest = {
  launchSpeedMph: number;
  launchAngleDeg: number;
  releaseSpeedMph: number;
  /** Ignored by the all-parks endpoint but required by the shared schema. */
  parkId: string;
  stand: "L" | "R";
};

export type AllParksResponse = {
  probHrByPark: Record<string, number>;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

export class ParksApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function predictAllParks(
  req: AllParksRequest,
): Promise<AllParksResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/batted-ball/all-parks`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw new ParksApiError(
      res.status,
      `all-parks predict failed: HTTP ${res.status}`,
    );
  }
  return (await res.json()) as AllParksResponse;
}

/**
 * 4c.2 canonical input: 110 mph / 28° / 94 mph release / R-handed batter.
 * Park is irrelevant (endpoint ignores it) but the field is required by the
 * request schema.
 */
export const CANONICAL_BBE_INPUT: AllParksRequest = {
  launchSpeedMph: 110,
  launchAngleDeg: 28,
  releaseSpeedMph: 94,
  parkId: "NYY",
  stand: "R",
};

export function useAllParksPrediction(req: AllParksRequest) {
  return useQuery<AllParksResponse, ParksApiError>({
    queryKey: ["parks", "all-parks", req],
    queryFn: () => predictAllParks(req),
    staleTime: 30_000,
  });
}
