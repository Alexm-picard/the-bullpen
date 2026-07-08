// k6 CAPACITY discovery for The Bullpen serving path (Wave D / D-40).
//
// Purpose: find the MAX SUSTAINED request rate and document overload behavior - NOT the nightly
// SLO gate (that is predict-load.js, a constant-vus regression tripwire at p99<50ms). This script
// uses an OPEN model (ramping-arrival-rate): the arrival rate is driven independently of how fast
// the server responds, so a saturating server shows up as a growing p99 + rising non-2xx rate
// rather than as a silently back-pressured closed loop (coordinated omission). A closed-loop
// constant-vus test cannot find the max sustained RPS because each VU waits for its own response
// before issuing the next request, so the offered load collapses exactly when the server slows.
//
// Scenarios (pick one per run via SCENARIO):
//   battedball  POST /v1/predict/batted-ball          - the CH-free deterministic hot path
//   pitch       POST /v1/predict/pitch                - pre-pitch head (needs a registered champion)
//   allparks    POST /v1/predict/batted-ball/all-parks- 30-park fan-out
//   mixed       ~70% batted-ball predict + ~30% /v1/players/search - exercises the pool-8 ClickHouse
//               read ceiling ALONGSIDE the serving path (the predict hot path is CH-free at request
//               time; logging is async, so only the reads touch the CH pool).
//
// The ramp climbs to PEAK_RPS (the expected plateau) and then to 1.5x PEAK for one stage to
// document overload - that stage breaches the informational thresholds BY DESIGN. Read the
// end-of-run summary: the knee is where p99 turns up and http_req_failed leaves ~0.
//
//   # local prod-parity boot (real/toy model), find the batted-ball ceiling:
//   PEAK_RPS=1500 SCENARIO=battedball k6 run infra/k6/capacity.js
//   # CH-backed read ceiling (needs ClickHouse up + seeded players):
//   BASE_URL=http://localhost:8080 SCENARIO=mixed PEAK_RPS=400 k6 run infra/k6/capacity.js
//
// The full saturation curve is captured on a local prod-parity boot; a short fixed-rate
// sub-saturation confirmation run against the prod box (off-window) is D-41. See docs/capacity.md.
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const SCENARIO = __ENV.SCENARIO || "battedball"; // battedball | pitch | allparks | mixed
const PEAK = Number(__ENV.PEAK_RPS || 800); // target arrival rate (req/s) at the plateau stage
const READ_FRACTION = Number(__ENV.READ_FRACTION || 0.3); // mixed: fraction that is a CH read

const HEADERS = { "Content-Type": "application/json" };

// Superset payload valid for BOTH batted-ball endpoints (Jackson ignores unknown fields, Spring
// Boot default): the single-park /v1/predict/batted-ball uses launchSpeedMph/launchAngleDeg/
// releaseSpeedMph/parkId/stand; the /all-parks champion path STRICTLY requires sprayAngleDeg,
// hitDistanceFt, baseState, outs (omitting them 400s all-parks 100%, seen on the 2026-07-07 box run).
const BATTED_BALL = JSON.stringify({
  launchSpeedMph: 100.0,
  launchAngleDeg: 28.0,
  releaseSpeedMph: 95.0,
  parkId: "NYY",
  stand: "R",
  sprayAngleDeg: 10.0,
  hitDistanceFt: 380.0,
  baseState: 0,
  outs: 1,
});

// Pre-pitch head: Tier 1+2 required fields only (Tier 3/4 omitted -> model handles as missing).
const PITCH = JSON.stringify({
  countBalls: 1,
  countStrikes: 2,
  outs: 1,
  inning: 6,
  baseState: 0,
  scoreDiff: 0,
  dow: 3,
  pitcherThrows: "R",
  batterStand: "R",
  parkId: "NYY",
  pitcherId: 592789,
  batterId: 545361,
});

// warm -> approach -> plateau -> 1.5x overload -> drain. Arrival rate is req/s (open model).
const stages = [
  { target: Math.max(1, Math.round(PEAK * 0.15)), duration: "15s" },
  { target: Math.max(1, Math.round(PEAK * 0.4)), duration: "20s" },
  { target: Math.max(1, Math.round(PEAK * 0.7)), duration: "20s" },
  { target: PEAK, duration: "25s" },
  { target: Math.round(PEAK * 1.5), duration: "20s" }, // past saturation - documents overload
  { target: 0, duration: "10s" },
];

export const options = {
  scenarios: {
    [SCENARIO]: {
      executor: "ramping-arrival-rate",
      exec: SCENARIO === "mixed" ? "mixed" : "predict",
      startRate: Math.max(1, Math.round(PEAK * 0.1)),
      timeUnit: "1s",
      preAllocatedVUs: Number(__ENV.PRE_VUS || 400),
      maxVUs: Number(__ENV.MAX_VUS || 4000),
      stages,
    },
  },
  thresholds: {
    // INFORMATIONAL bounds - the 1.5x-PEAK overload stage breaches them on purpose. abortOnFail
    // keeps the run going so the whole curve (incl. overload) is captured. The nightly SLO gate is
    // predict-load.js, not this.
    http_req_failed: [{ threshold: "rate<0.05", abortOnFail: false }],
    "http_req_duration{expected_response:true}": [
      { threshold: "p(95)<200", abortOnFail: false },
      { threshold: "p(99)<500", abortOnFail: false },
    ],
  },
};

function predictTarget() {
  switch (SCENARIO) {
    case "pitch":
      return { url: `${BASE}/v1/predict/pitch`, body: PITCH, tag: "pitch" };
    case "allparks":
      return {
        url: `${BASE}/v1/predict/batted-ball/all-parks`,
        body: BATTED_BALL,
        tag: "allparks",
      };
    default:
      return {
        url: `${BASE}/v1/predict/batted-ball`,
        body: BATTED_BALL,
        tag: "battedball",
      };
  }
}

export function predict() {
  const t = predictTarget();
  const res = http.post(t.url, t.body, {
    headers: HEADERS,
    tags: { endpoint: t.tag },
  });
  check(res, { "predict 2xx": (r) => r.status >= 200 && r.status < 300 });
}

export function mixed() {
  if (Math.random() < READ_FRACTION) {
    const res = http.get(`${BASE}/v1/players/search?q=aaron&limit=10`, {
      tags: { endpoint: "player-search" },
    });
    check(res, { "read 2xx": (r) => r.status === 200 });
  } else {
    const res = http.post(`${BASE}/v1/predict/batted-ball`, BATTED_BALL, {
      headers: HEADERS,
      tags: { endpoint: "battedball" },
    });
    check(res, { "predict 2xx": (r) => r.status === 200 });
  }
}
