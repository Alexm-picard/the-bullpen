import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  AdminApiError,
  clearChallenger,
  setChallenger,
  setRoutingMode,
  setTrafficPct,
} from "./admin";

const CREDS = { user: "admin", password: "pw" };

function mockOnce(body: unknown, ok = true, status = 200) {
  (fetch as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
    ok,
    status,
    json: async () => body,
  });
}

function lastCall() {
  return (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.at(-1) as [
    string,
    RequestInit,
  ];
}

describe("admin API client", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("setRoutingMode POSTs to the mode path with Basic auth + body", async () => {
    mockOnce({ modelName: "pitch_outcome_pre", mode: "AB" });
    await setRoutingMode(CREDS, "pitch_outcome_pre", "AB", "cutover test");
    const [url, init] = lastCall();
    expect(url).toContain("/v1/admin/routing/pitch_outcome_pre/mode");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>).Authorization).toContain(
      "Basic ",
    );
    expect(JSON.parse(init.body as string)).toEqual({
      mode: "AB",
      reason: "cutover test",
    });
  });

  it("setTrafficPct POSTs the pct + reason", async () => {
    mockOnce({});
    await setTrafficPct(CREDS, "pitch_outcome_pre", 25, "ramp to 25");
    const [url, init] = lastCall();
    expect(url).toContain("/traffic-pct");
    expect(JSON.parse(init.body as string)).toEqual({
      pct: 25,
      reason: "ramp to 25",
    });
  });

  it("setChallenger POSTs the challenger version id", async () => {
    mockOnce({});
    await setChallenger(CREDS, "pitch_outcome_pre", 42, "new shadow");
    const [url, init] = lastCall();
    expect(url).toContain("/challenger");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      challengerVersionId: 42,
      reason: "new shadow",
    });
  });

  it("clearChallenger uses DELETE", async () => {
    mockOnce({});
    await clearChallenger(CREDS, "pitch_outcome_pre");
    const [url, init] = lastCall();
    expect(url).toContain("/challenger");
    expect(init.method).toBe("DELETE");
  });

  it("urlencodes the model name", async () => {
    mockOnce({});
    await setRoutingMode(CREDS, "weird/name", "SHADOW", "x");
    expect(lastCall()[0]).toContain("/v1/admin/routing/weird%2Fname/mode");
  });

  it("throws AdminApiError surfacing the backend error message", async () => {
    mockOnce(
      { error: { message: "challenger must be at SHADOW stage" } },
      false,
      400,
    );
    const err = await setChallenger(CREDS, "m", 1, "r").catch((e) => e);
    expect(err).toBeInstanceOf(AdminApiError);
    expect(err.status).toBe(400);
    expect(err.message).toBe("challenger must be at SHADOW stage");
  });
});
