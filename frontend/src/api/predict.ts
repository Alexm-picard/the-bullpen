import { useMutation } from "@tanstack/react-query";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:8080";

export type BattedBallRequest = {
  launchSpeedMph: number;
  launchAngleDeg: number;
  releaseSpeedMph: number;
  parkId: string;
  stand: "L" | "R";
};

export type PredictionResponse = {
  probHr: number;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

export type ApiErrorBody = {
  error: {
    code: string;
    message: string;
    correlationId: string;
    details: Array<{ field: string; message: string }>;
  };
};

export class PredictError extends Error {
  readonly code: string;
  readonly details: Array<{ field: string; message: string }>;
  constructor(body: ApiErrorBody) {
    super(body.error.message);
    this.code = body.error.code;
    this.details = body.error.details ?? [];
  }
}

export async function predictBattedBall(
  req: BattedBallRequest,
): Promise<PredictionResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/batted-ball`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiErrorBody | null;
    if (body && "error" in body) throw new PredictError(body);
    throw new Error(`predict failed: HTTP ${res.status}`);
  }
  return (await res.json()) as PredictionResponse;
}

export function usePredictBattedBall() {
  return useMutation<PredictionResponse, Error, BattedBallRequest>({
    mutationFn: predictBattedBall,
  });
}
