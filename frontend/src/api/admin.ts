/**
 * Admin write-API client (B7) for the routing-override page. Every call carries HTTP Basic
 * credentials (the {@code /v1/admin/**} surface is auth-gated by the backend SecurityConfig) and
 * returns the updated {@link RoutingConfig}. Errors surface the backend's `ApiError.error.message`.
 *
 * Credentials are passed per-call and held only in the page's in-memory React state — never
 * persisted to localStorage — so closing the tab forgets them.
 */
import type { RoutingConfig } from "./ops";

import { API_BASE } from "./base";

export type AdminCreds = { user: string; password: string };

export class AdminApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function basicHeader(creds: AdminCreds): string {
  return "Basic " + btoa(`${creds.user}:${creds.password}`);
}

async function send<T>(
  path: string,
  method: "POST" | "DELETE",
  creds: AdminCreds,
  body?: unknown,
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      "Content-Type": "application/json",
      Authorization: basicHeader(creds),
    },
    body: body == null ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `${path} failed: HTTP ${res.status}`;
    try {
      const parsed = (await res.json()) as { error?: { message?: string } };
      if (parsed?.error?.message) message = parsed.error.message;
    } catch {
      // non-JSON body (e.g. a bare 401 from the security filter) — keep the status message
    }
    throw new AdminApiError(res.status, message);
  }
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

/** Flip SHADOW ↔ AB. SHADOW resets traffic to 0 server-side. */
export const setRoutingMode = (
  creds: AdminCreds,
  modelName: string,
  mode: "SHADOW" | "AB",
  reason: string,
) =>
  send<RoutingConfig>(
    `/v1/admin/routing/${enc(modelName)}/mode`,
    "POST",
    creds,
    {
      mode,
      reason,
    },
  );

/** Move the challenger traffic slider [0, 100]. Rejected (400) when mode=SHADOW and pct > 0. */
export const setTrafficPct = (
  creds: AdminCreds,
  modelName: string,
  pct: number,
  reason: string,
) =>
  send<RoutingConfig>(
    `/v1/admin/routing/${enc(modelName)}/traffic-pct`,
    "POST",
    creds,
    {
      pct,
      reason,
    },
  );

/** Set the challenger version (must be a SHADOW-stage version). Resets traffic to 0. */
export const setChallenger = (
  creds: AdminCreds,
  modelName: string,
  challengerVersionId: number,
  reason: string,
) =>
  send<RoutingConfig>(
    `/v1/admin/routing/${enc(modelName)}/challenger`,
    "POST",
    creds,
    {
      challengerVersionId,
      reason,
    },
  );

/** Clear the challenger slot. */
export const clearChallenger = (creds: AdminCreds, modelName: string) =>
  send<RoutingConfig>(
    `/v1/admin/routing/${enc(modelName)}/challenger`,
    "DELETE",
    creds,
  );
