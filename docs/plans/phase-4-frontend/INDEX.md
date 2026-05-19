# Phase 4 — Frontend Build-Out · INDEX

> Five pages exist and demonstrate the system meaningfully.
> Weeks 23–30 · ~70–90 hours. See [`../../plan.md`](../../plan.md) §Phase 4.
>
> **Phase exit criterion**: All 5 pages exist; loading/error/empty states; Lighthouse > 80; bundle < 300 KB gz.
>
> **Soft cuts** (in priority order):
> - End of Wk 28 if behind: drop Game / Live view (~12 h).
> - End of Wk 30 if behind: cut visual ambition on About page (~10 h).
>
> **Hard rule (Discipline 6)**: NEVER cut the Ops dashboard. It's the recruiter-clicked page.

---

## Cross-cutting docs to read alongside any leaf in this phase

- [`../00-MASTER.md`](../00-MASTER.md)
- [`../00-CONVENTIONS.md`](../00-CONVENTIONS.md) — TypeScript / Mantine / Tailwind conventions
- [`../00-RISK-REGISTER.md`](../00-RISK-REGISTER.md) — G10 (Ops auth split) directly addressed
- [`../../design.md`](../../design.md) §7, §8

---

## Why design tokens come first

Design tokens are 4a — the very first leaf — because every later leaf consumes them. If tokens land late, every component built earlier accumulates hex codes and becomes a Phase-5 cleanup target. Discipline rule 1 (no hex codes in components) is enforced from day 1 of Phase 4.

---

## Leaf plans

### 4a — `4a-design-tokens.md`
Mantine + Tailwind config wiring all design-system tokens (colors, typography, spacing, motion). Source Serif 4 loaded. Color palette as CSS variables. Type scale as utility classes. Tailwind plugin that warns on inline hex codes. Storybook NOT installed (cut for v1).
- **Decisions referenced**: [100], [103]–[114].
- **Acceptance**: a single sample component built using only tokens; Tailwind config exports the same tokens that `src/design/tokens.ts` declares; `pnpm lint:hex-codes` returns 0 hits.

### Phase 4b — Player Lookup → [`4b-player-lookup/`](4b-player-lookup/)

Weeks 24–25. Simplest analytical page. Good warm-up for the data-binding patterns the Ops dashboard reuses.

Leaf plans:
- `4b.1-search.md` — search box + autocomplete; `/v1/players/search?q=`
- `4b.2-profile-and-history.md` — pitcher / batter profile page with prediction-vs-actual rows
- `4b.3-calibration-plot.md` — per-player reliability diagram, Recharts/Mantine

### Phase 4c — Park Explorer → [`4c-park-explorer/`](4c-park-explorer/) ★ **MARQUEE**

Weeks 25–27. Highest-variance leaf in visual quality. Build basic version FIRST; iterate.

Leaf plans:
- `4c.1-stadium-svg-assets.md` — pre-rendered 30 stadium outlines as static SVG; only color overlays dynamic at runtime
- `4c.2-basic-30-grid.md` — simple grid of mini-charts with colors; deliberately not polished
- `4c.3-launch-param-sliders-debounced.md` — exit velocity, launch angle, spray angle sliders; TanStack Query refetch; 200ms debounce
- `4c.4-polished-heatmap-iteration.md` — typography, color scale (Viridis), interaction polish; the "fine → memorable" pass

### Phase 4d — Game / Live view → [`4d-game-live/`](4d-game-live/)

Weeks 27–28. Soft-cut candidate.

Leaf plans:
- `4d.1-tanstack-polling-10-15s.md` — TanStack Query refetch interval; cache invalidation pattern
- `4d.2-pitch-feed-with-predictions.md` — pitch-by-pitch feed; model predictions overlay; live state machine consumed (Risk Register G5)

### Phase 4e — Ops Dashboard → [`4e-ops-dashboard/`](4e-ops-dashboard/) ★ **RECRUITER-FACING**

Week 29. Never cut.

Leaf plans:
- `4e.1-registry-browser.md` — list / filter / search model_versions; show eval artifacts inline
- `4e.2-drift-charts.md` — drift_metrics over rolling windows; per-model and per-feature
- `4e.3-ab-status.md` — current routing config, traffic_pct, experiment_results
- `4e.4-retrain-queue.md` — show queue depth, last 10 runs, trigger button (gated to admin)
- `4e.5-reliability-diagrams.md` — per-class, per-model, fetched from eval artifact

**Closes / addresses**: G10 — read paths public at `/v1/ops/*`; write paths gated `/v1/admin/*`. Promotion / retrain trigger / traffic_pct slider live behind HTTP basic.

### 4f — `4f-about-methodology.md`
Editorial visual treatment. Source Serif headlines. Long-form prose: what the models do, training data, eval methodology, "v2 ideas" (cherry-picked from `design.md` §11).
- **Decisions referenced**: [100], [102], [111] (editorial layout).
- Visual ambition allowed here (decision [102]); soft-cut candidate if Phase 4 is at risk on Wk 30.

---

## Phase 4 exit gate

```bash
# All five pages reachable, with three states each:
# - loading skeleton visible
# - error state visible (kill backend, refresh)
# - empty state visible (search returning no results)

# Lighthouse score:
pnpm lighthouse https://thebullpen.net           # > 80 across all categories
pnpm lighthouse https://thebullpen.net/parks     # > 80
# ... (all 5 pages)

# Bundle budget:
pnpm build && du -sh frontend/dist/assets/*.js   # initial chunk < 300KB gz

# Hex-code discipline:
pnpm lint:hex-codes                              # 0 hits in src/ outside design/

# E2E happy path:
pnpm test:e2e                                    # passes
```

If all of the above pass: Phase 4 done. Move to Phase 5 (polish + operate).
