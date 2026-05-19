# ADR-0005: Poll via TanStack Query for live updates, not WebSockets

- **Status**: Accepted
- **Date**: 2026-05-19
- **Deciders**: alex
- **Related**: `decisions.md` entries [95] [96], `plan.md` Phase 4d, `design.md` §7 §10

## Context

The Game / Live view shows pitch-by-pitch updates as an MLB game progresses.
Predictions arrive several seconds after each pitch (live polling against
the MLB Stats API is decision [89], a game-state-machine pull rather than
fixed-cadence). Park Explorer's "today's BIPs" panel and the Ops Dashboard's
drift counters are also semi-live: numbers that update on a tens-of-seconds
cadence, not millisecond cadence.

The frontend is a pure React SPA (decision [93]) running through TanStack
Query for all server state (decision [95]). The backend is a Spring Boot
JAR with the `api` profile.

The candidate transports:

- **HTTP polling** via `TanStack Query`'s `refetchInterval` — 10–15 second
  interval per active screen, automatic deduplication of in-flight requests,
  automatic stale-while-revalidate.
- **WebSockets** — long-lived bidirectional connection, server pushes
  updates as they happen, frontend subscribes per query.
- **Server-Sent Events (SSE)** — one-way stream from server to client, HTTP-
  shaped, simpler than WebSockets.

Real-time UI is genuinely useful for ~3% of the project's interaction
surface (the Game/Live pitch-by-pitch feed) and absent from the other 97%.
The deeper question is whether to introduce the complexity for that 3%.

The decision also has a sharper edge: WebSockets require pinning state on
the server (connection registry, subscription bookkeeping, reconnect logic).
That state defeats the "stateless, behind Cloudflare Tunnel, restartable"
operational story that backs the 98% uptime target (decision [11]).

## Decision

We use **HTTP polling via TanStack Query's `refetchInterval`** for all live
or semi-live updates. The default cadence is 10 seconds for the Game/Live
view and 30 seconds for Park Explorer / Ops counters. We do not run a
WebSocket server, we do not run SSE.

The polling cadence is configurable per query and tuned per page; nothing
about the choice ties us to a single global interval.

## Consequences

**Easier:**

- The backend stays stateless. Every prediction endpoint is a plain HTTP
  request that can be served by any instance, restarted at any time,
  load-balanced trivially.
- Cloudflare Tunnel (decision [9]) has no special configuration for
  WebSocket upgrade. Plain HTTPS the whole way.
- TanStack Query already owns caching, deduplication, and stale-while-
  revalidate for our other queries. Polling fits into the same mental
  model the rest of the data layer uses.
- No reconnect / heartbeat / subscription-bookkeeping code on the server.
  Less code, fewer failure modes, no "subscriptions leaked when client
  disconnected silently" class of bug.

**Harder:**

- The Game/Live view updates with up to 10 seconds of latency. For pitch-
  by-pitch this is acceptable (pitches are 10–30 seconds apart in a normal
  game); for a hypothetical future "every defensive shift" view it would
  not be.
- Polling generates baseline HTTP load even when nothing changed. For a
  portfolio site this is negligible (<1 req/sec), but it would be a real
  concern at production scale.

**New failure modes:**

- A client tab left open during a doubleheader can poll for hours. The
  backend's stateless design absorbs this; the only mitigation we'd want
  is `refetchIntervalInBackground: false` on inactive tabs (TanStack Query
  default behavior).
- Misconfigured polling intervals could create thundering-herd patterns
  against the MLB Stats API on the backend side. Mitigated by the
  scheduled-poll design (decision [89]) — the frontend polls _our_ backend,
  not MLB directly, and our backend caches MLB responses.

**Locked into:**

- The "real-time" framing in any future page must be reconsidered against
  the polling cadence. If a future page genuinely needs millisecond
  push, that's a re-decision via `/decide`, not a quiet addition.

## Alternatives Considered

### Alternative A: WebSockets

- Spring's `@MessageMapping` over STOMP/WebSocket; frontend uses the
  `@stomp/stompjs` client.
- Rejected: drags real server-side state (connection registry,
  subscription book-keeping, reconnect logic) into an app that's
  otherwise stateless. Coordination overhead is not justified at the
  polling cadence this project actually needs. The "live" framing reads
  more impressively in a screenshot but does not pay for the
  operational complexity it introduces.

### Alternative B: Server-Sent Events (SSE)

- One-way stream from server to client over plain HTTP.
- Rejected: simpler than WebSockets, still adds a long-lived connection
  per client and bookkeeping on the server side, and doesn't materially
  improve UX over a 10-second poll for any of our actual screens. The
  Ops Dashboard especially has no need for push — counters at 30s
  cadence are fine.

### Alternative C: GraphQL subscriptions

- Subscriptions over WebSockets with a GraphQL framing.
- Rejected: ships a whole GraphQL stack to deliver one capability we
  decided not to need. The plain REST + polling design fits the project's
  actual interaction patterns.

## Revision History

(none)
