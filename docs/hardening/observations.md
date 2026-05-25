# Hardening observations

Running log. Anything that surprises you during operation lands here,
one line per item, dated. Don't pre-judge severity at capture. Read
the [README](README.md) for the triage process.

Format:

```
| YYYY-MM-DD | area  | one-line observation (link / metric if you have one) |
```

Areas: `infra`, `backend`, `training`, `frontend`, `ops`, `docs`,
`devx`, `security`, `perf`.

## Pre-season seed — items noticed during Phase 4/5 build

These ARE real things noticed during the build; they're listed here so
the first sweep has signal even before the season starts.

| Date       | Area     | Observation                                                                                                                                                                                                                                                                               |
| ---------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-05-25 | backend  | `ApiErrorAdvice` was missing a handler for `MissingServletRequestParameterException` — missing query param returned 500 instead of 400. Fixed in commit `7c6e1b6` (Phase 4b.1). Audit the rest of the Spring exception surface for similar gaps before the season opens.                  |
| 2026-05-25 | backend  | `@ConditionalOnBean(PlayerRepository.class)` on `PlayerController` broke 179 IT contexts — Spring's bean-name-conditional → bean-name-conditional ordering is fragile when both targets are component-scanned. Worked around with `@ConditionalOnProperty`; audit other controllers.      |
| 2026-05-25 | frontend | Initial JS bundle was a 542 kB / 165 kB-gz monolith before Phase 5.3 added lazy routes + vendor splits. Watch for this regressing — `npm run bundle-budget` is the gate.                                                                                                                  |
| 2026-05-25 | frontend | Mantine 9 renamed `<Grid gutter>` → `<Grid gap>`. Caught by `tsc -b`. Audit the rest of the Mantine surface against the v8→v9 migration notes; some props will silently no-op (don't fail to compile).                                                                                    |
| 2026-05-25 | frontend | eslint's `react-hooks/immutability` rule forbids `let` accumulators in component bodies. Hit twice this build (probability bar, viridis). Pattern: use `.reduce` for cumulative geometry. Document in the design system once a third hit lands.                                           |
| 2026-05-25 | devx     | The auto-formatter on this repo removes JSX imports the moment a symbol isn't yet referenced — interrupts the natural "import → use" rhythm. Investigate: switch to format-on-save with a per-file opt-out, or accept the friction.                                                       |
| 2026-05-25 | backend  | `prediction_log` → `pitches` truth-join can't happen without an indexed `pitch_id` column on `prediction_log`. Affects 4b.2 (history) / 4b.3 (calibration) / 4d.2 (agreement marker). One migration + a writer-side change unlocks all three. High-leverage hardening candidate.          |
| 2026-05-25 | backend  | `LivePollingService` + `MlbStatsApiClient` are the only un-wired piece of Phase 4d — endpoints, state machine, DTOs all live. One class addition. Becomes blocking the moment a real game runs against it.                                                                                |
| 2026-05-25 | training | `_dispatch.DISPATCH` for retraining holds sentinel `_not_yet_wired` stubs for all 5 model_names. Each becomes wireable when the corresponding trainer learns to accept `trigger_id`. Touch each trainer when convenient.                                                                  |
| 2026-05-25 | ops      | Ops dashboard `/v1/ops/retrain` lacks pagination — `findAllQueued()` returns the full list. Fine today (queue is small); becomes a problem when triggers accumulate over a season. Mitigate by capping in the repo or pushing a `limit=` param into the controller.                       |
| 2026-05-25 | ops      | A/B traffic-percent slider lives on the admin POST endpoint only — Ops UI is read-only. If we ever want a single page to walk the recruiter through "see me move traffic between champion and challenger live," wrap the existing endpoint behind HTTP Basic on a small admin page.       |
| 2026-05-25 | docs     | `docs/phase-status.json` drifted out of sync with reality across this session — said `current_phase: 2` while Phases 4 + 5 landed. Need a discipline rule that any phase-completing commit also bumps the status JSON, or a CI check that compares the status JSON against `git log`.     |
| 2026-05-25 | training | LightGBM's default logger uses bare `print` → block-buffered stdout under `tee` makes a 13-minute run look like a hang. The 2b.2 follow-up routed it through `logging`; verify the same fix is in place wherever LightGBM is called.                                                      |
| 2026-05-25 | frontend | No e2e harness today. Bundle-budget + static a11y are the surrogates. When Playwright lands, audit: Mantine modal focus traps, slider keyboard semantics, route-level lazy-load fallback visibility, and the Park Explorer thumbnail Enter-key path that 5.4 added but didn't e2e-verify. |
| 2026-05-25 | infra    | Restore drill + reboot drill have been run (rule 8). USB backup verified once. Consider a third drill: "Cloudflare Tunnel down" — currently the only path to api.thebullpen.net. Document the symptoms + the recovery path in `ops/runbooks/` before it happens for real.                 |

## Season — append below

(empty — fill during operation)
