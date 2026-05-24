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

// ---------------------------------------------------------------------------
// Pitch outcome (Phase 2a.8) — 5-class calibrated multinomial
// ---------------------------------------------------------------------------

export const PITCH_OUTCOME_CLASSES = [
  "ball",
  "called_strike",
  "swinging_strike",
  "foul",
  "in_play",
] as const;
export type PitchOutcomeClass = (typeof PITCH_OUTCOME_CLASSES)[number];

export type PitchRequest = {
  countBalls: number;
  countStrikes: number;
  outs: number;
  inning: number;
  baseState: number;
  scoreDiff: number;
  dow: number;
  pitcherThrows: "L" | "R";
  batterStand: "L" | "R";
  parkId: string;
  pitcherId: number;
  batterId: number;
  // Tier 3 form fields — optional; null/undefined lets the model handle NaN.
  pitcherPitchesLast28d?: number | null;
  pitcherPitchesInGame?: number | null;
  daysSinceLastAppearance?: number | null;
  pitcherStrikeRate28d?: number | null;
  pitcherSwstrikeRate28d?: number | null;
  pitcherInplayRate28d?: number | null;
  pitcherStrikeRateStd?: number | null;
  batterStrikeRate28d?: number | null;
  batterInplayRate28d?: number | null;
  batterBallRate28d?: number | null;
  batterInplayRateStd?: number | null;
};

export type PitchPredictionResponse = {
  probabilities: Record<PitchOutcomeClass, number>;
  winner: PitchOutcomeClass;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

export async function predictPitch(
  req: PitchRequest,
): Promise<PitchPredictionResponse> {
  const res = await fetch(`${API_BASE}/v1/predict/pitch`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiErrorBody | null;
    if (body && "error" in body) throw new PredictError(body);
    throw new Error(`predict failed: HTTP ${res.status}`);
  }
  return (await res.json()) as PitchPredictionResponse;
}

export function usePredictPitch() {
  return useMutation<PitchPredictionResponse, Error, PitchRequest>({
    mutationFn: predictPitch,
  });
}

// ---------------------------------------------------------------------------
// Forward simulator (Phase 2a.9) — analytical (production) + Monte Carlo
// ---------------------------------------------------------------------------

export type SimulateRequest = {
  startBalls: number;
  startStrikes: number;
  outs: number;
  inning: number;
  baseState: number;
  scoreDiff: number;
  dow: number;
  pitcherThrows: "L" | "R";
  batterStand: "L" | "R";
  parkId: string;
  pitcherId: number;
  batterId: number;
  pitcherPitchesLast28d?: number | null;
  pitcherPitchesInGame?: number | null;
  daysSinceLastAppearance?: number | null;
  pitcherStrikeRate28d?: number | null;
  pitcherSwstrikeRate28d?: number | null;
  pitcherInplayRate28d?: number | null;
  pitcherStrikeRateStd?: number | null;
  batterStrikeRate28d?: number | null;
  batterInplayRate28d?: number | null;
  batterBallRate28d?: number | null;
  batterInplayRateStd?: number | null;
  // Monte-Carlo only:
  mcTrials?: number | null;
  mcSeed?: number | null;
};

export type SimulateMethod = "analytical" | "monte_carlo";

export type SimulateResponse = {
  method: SimulateMethod;
  startBalls: number;
  startStrikes: number;
  expectedPitches: number;
  pWalk: number;
  pStrikeout: number;
  pInPlay: number;
  mcTrials: number | null;
  modelName: string;
  modelVersion: string;
  latencyMicros: number;
  correlationId: string;
};

async function postSimulate(
  path: string,
  req: SimulateRequest,
): Promise<SimulateResponse> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ApiErrorBody | null;
    if (body && "error" in body) throw new PredictError(body);
    throw new Error(`simulate failed: HTTP ${res.status}`);
  }
  return (await res.json()) as SimulateResponse;
}

export function simulatePlateAppearance(
  req: SimulateRequest,
): Promise<SimulateResponse> {
  return postSimulate("/v1/simulate/plate-appearance", req);
}

export function simulatePlateAppearanceMonteCarlo(
  req: SimulateRequest,
): Promise<SimulateResponse> {
  return postSimulate("/v1/simulate/plate-appearance/monte-carlo", req);
}

export function useSimulatePlateAppearance() {
  return useMutation<SimulateResponse, Error, SimulateRequest>({
    mutationFn: simulatePlateAppearance,
  });
}

export function useSimulatePlateAppearanceMonteCarlo() {
  return useMutation<SimulateResponse, Error, SimulateRequest>({
    mutationFn: simulatePlateAppearanceMonteCarlo,
  });
}
