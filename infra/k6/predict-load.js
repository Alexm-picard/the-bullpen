// k6 load test for the prediction hot path (plan S1h).
//
// SLA (design.md): p99 prediction latency < 50ms, error rate < 0.1%. The 50ms
// target is the in-process server-side latency, so the nightly CI run points
// BASE_URL at a CI-booted instance (localhost, no network) and the p99 threshold
// is meaningful. Point BASE_URL at https://api.thebullpen.net for an on-demand
// prod load test during the season (the round-trip p99 there is dominated by the
// Cloudflare Tunnel hop, so loosen the duration threshold for that run).
//
//   k6 run infra/k6/predict-load.js
//   BASE_URL=https://api.thebullpen.net k6 run infra/k6/predict-load.js
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const P99_MS = Number(__ENV.P99_MS || 50); // server SLA; raise for over-internet runs

export const options = {
  scenarios: {
    predict: {
      executor: "constant-vus",
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || "30s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.001"], // < 0.1% errors
    "http_req_duration{endpoint:battedball}": [`p(99)<${P99_MS}`],
  },
};

const battedBall = JSON.stringify({
  launchSpeedMph: 100.0,
  launchAngleDeg: 28.0,
  releaseSpeedMph: 95.0,
  parkId: "NYY",
  stand: "R",
});

const HEADERS = { "Content-Type": "application/json" };

export default function () {
  const res = http.post(`${BASE}/v1/predict/batted-ball`, battedBall, {
    headers: HEADERS,
    tags: { endpoint: "battedball" },
  });
  check(res, {
    "status is 200": (r) => r.status === 200,
    "has probHr": (r) => r.json("probHr") !== undefined,
  });
}
