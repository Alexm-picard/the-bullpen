# Frontend IA Audit (Phase A)

Discussion basis for `/decide`. Not a decision record. Options-with-recommendations, not conclusions.

Screenshots captured 2026-08-09 from `thebullpen.net` at 1440px (desktop) and 390px (mobile).
See `docs/design/screenshots/{desktop,mobile}/` for reference images.

---

## Section 1 - Inventory

### Navigation

7 flat NavLinks in a horizontal header bar (desktop) or a right-side Drawer (mobile < 768px):
HOME | PARKS | PLAYERS | GAMES | OPS | ACCURACY | ABOUT

Unlisted routes: `/admin/routing` (operator-only, reached by URL), `/players/:id` (sub-route from player search), `/games/:id` (sub-route from games board or home slate).

### 1.1 Home (`/`)

**What it shows:** Tonight's slate header (date, live-ingest badge), a fleet-status strip (model chips with stage badges), a live-tonight ticker strip linking to games, a featured matchup panel (highest battle-score game), and a matchups board table. When the backend is unreachable or no games are posted, all sections fall back to showcase fixtures captioned honestly ("showcase data - backend unreachable").

**Data source:** MIXED (live with fixture fallback). Four live hooks: `useAllRegistryRows`, `useRouting`, `useTodaysGames` (60s refetch), `useTodaysMatchups` (5min refetch). Fallback fixtures from `home-fixtures.ts`, `matchups-showcase.ts`, `slate-fixtures.ts`.

**Entry points:** Root URL, nav "HOME" link, browser default.

**Mobile:** Mostly well-shaped. Fleet strip scrolls horizontally (`overflowX: auto`), tonight strip wraps, featured matchup stacks. The matchups board table requires horizontal scroll on phones (6-column desktop layout, functional via `overflowX: auto` but not card-adapted). The h1 (48px) has no 600px shrink rule, unlike about/ops/parks which drop their titles at that breakpoint - minor inconsistency since "Tonight's Slate" is short.

### 1.2 Games Board (`/games`)

**What it shows:** Today's date, live-ingest badge, status filter buttons (All/Live/Scheduled/Completed), and a slate of game cards in a responsive grid. Each card shows teams, score, inning, starting pitchers, battle score, matchup lean classification, and an "Open Game" link. Filters are client-side against the live games list.

**Data source:** MIXED (live with fixture fallback). `useTodaysGames` + `useTodaysMatchups`. Showcase fallback captioned.

**Entry points:** Nav "GAMES" link, home page "View all games" link.

**Mobile:** Well-shaped. Grid uses `repeat(auto-fill, minmax(330px, 1fr))` - single column on phones, cards fill width. Filter buttons are compact (12px mono). Header wraps.

### 1.3 Live Game (`/games/:id`)

**What it shows:** The densest page on the site. Top to bottom: team matchup h1, context line (date, live-ingest), scorebug (score, inning, current state), big-stat row (count, outs, batter handedness, pitcher handedness), current-batter chyron, then four model panels of equal visual weight stacked vertically:

1. Next-Pitch Model (pre-pitch outcome prediction, 5-class bar chart)
2. Pitch-Type Model (7-class pitch-type prior, bar chart)
3. Batted-Ball Model (per-park HR comparison when a ball is in play, or team-contact pre-BIP)
4. Live Pitch Log (last 50 pitches, 7-column table)

Plus a retrospective post-prediction panel (logged champion predictions replayed against outcomes).

**Data source:** LIVE. Ten hooks covering game state, pitches, player names, three prediction endpoints, and post-predictions. No fixture fallback - unavailable data renders explicit empty/gated states. This is the only page where all three prediction models fire live.

**Entry points:** Games board card links, home tonight strip links, home featured matchup link.

**Mobile:** Mixed. BigStat row wraps to 2x2. Prediction panels (next-pitch, pitch-type) use compact grids that fit. The pitch log table scrolls horizontally (7 columns, `overflowX: auto`). The scorebug has a horizontal overflow risk at very narrow widths (< 340px) with long detail strings. The batted-ball explorer's park comparison grid uses `auto-fill minmax(240px, 1fr)` and stacks to single column.

**Accretion note:** The four model panels were shipped across four separate PRs. They have equal visual weight and no temporal hierarchy - during an at-bat the next-pitch and pitch-type panels are primary, but after a ball in play the batted-ball panel becomes primary. The layout currently ignores these natural temporal roles.

### 1.4 Players (`/players`)

**What it shows:** A player search box, a browse-by-position/team grid, featured scouting reports (showcase fixtures captioned "SHOWCASE"), and model standouts (fixture, "no leaders endpoint yet").

**Data source:** MIXED. Search (`usePlayerSearch`) and roster browse (`usePlayerRoster`) are live. Featured reports and model standouts are pure fixture.

**Entry points:** Nav "PLAYERS" link.

**Mobile:** Well-shaped. Single-column layout. Search box and browse grid stack naturally.

### 1.5 Player Profile (`/players/:id`)

**What it shows:** For real players (numeric ID from search): player name/position/team header, showcase scouting card (fixture for slug demos, live data for searched players), recent predictions table (live from prediction_log), calibration chart (live), pitcher arsenal velocity card (live, pitchers only), batter batted-balls view (live, position players only).

**Data source:** MIXED. Five live hooks for numeric IDs (player info, arsenal, predictions, calibration, batted balls). Non-numeric slugs fall back to showcase matchup fixtures.

**Entry points:** Player search results, browse-by-team/position links, game page batter/pitcher name links.

**Mobile:** Mostly well-shaped. Two-column grids collapse to single column at 900px. Stat tables scroll horizontally. SVG-based charts (pitch location heatmap, spray chart) scale with container.

### 1.6 Parks (`/parks`)

**What it shows:** A large page with three distinct sections: (1) the live HR probability heatmap - user-adjustable launch parameters (EV, LA, spray) fed to the all-parks model, producing a ranked 30-park bar chart; (2) a fixture overview table of park factors (30 parks, multiple columns); (3) a Coors Field spotlight panel. The heatmap is the hero; the rest is editorial context from fixtures.

**Data source:** MIXED. The heatmap is live via `useAllParksPrediction` (POST to all-parks endpoint). The overview table, park thumbnails, and Coors spotlight are pure fixture from `parks-fixtures.ts`, captioned "Showcase data - GET /v1/parks/factors not yet implemented."

**Entry points:** Nav "PARKS" link.

**Mobile:** Well-shaped. Explicit CSS breakpoints at 900px (spotlight stacks) and 600px (title shrinks). Heatmap controls wrap. Park grid fits. Overview table scrolls horizontally.

### 1.7 Ops (`/ops`)

**What it shows:** The operational dashboard: model fleet table (registry rows with stage/version/schema), routing config, p99 latency (1d + 7d), retrain queue, ops event log, drift snapshot (PSI + ECE per model), and an infra services ribbon. Dense, multi-section, aimed at the operator.

**Data source:** MIXED (predominantly live with fixture fallback). Seven live hooks. The infra ribbon is always fixture ("no status endpoint yet"). Drift values render as em-dashes until live data lands. All fallbacks captioned.

**Entry points:** Nav "OPS" link.

**Mobile:** Well-shaped. Explicit CSS breakpoints at 900px (drift pair stacks, retrain rows go vertical), 768px (infra ribbon wraps to 2-column), 600px (title shrinks). Tables scroll horizontally.

### 1.8 Accuracy (`/accuracy`)

**What it shows:** Two strictly separated accuracy surfaces: (1) Live Scorecard - rolling 7-day realized accuracy from truth-joined prediction_log (per-model cards with top-1 accuracy or honest "no live truth" states); (2) Held-Out Scorecard - offline rolling-origin CV metrics (Brier, ECE, confusion matrices). Plus a batted-ball backfill accuracy section. The page enforces hard separation between live and offline: "The two surfaces never mix."

**Data source:** LIVE. Three hooks (`useModelScorecard`, `useBattedBallBackfill`, `useRollingAccuracy`), no fixtures. Empty states are explicit `NoHistoryNote` components.

**Entry points:** Nav "ACCURACY" link.

**Mobile:** Well-shaped. Live scorecard cards use `auto-fit minmax(190px, 1fr)`. Tables scroll. Confusion matrix scrolls.

### 1.9 About (`/about`)

**What it shows:** A colophon-style methodology page. Sections: Opening Pitch (project description), The Stack (technology table), Model Fleet (live registry table with fixture fallback), Operational Discipline (notes on observability, rules), Intentionally Not Here (rejected alternatives tag cloud), Roadmap Honesty. Built as editorial content, not a data surface.

**Data source:** MIXED (mostly fixture by nature). One live hook (`useAllRegistryRows`) for the Model Fleet table; all editorial prose from `about-fixtures.ts`. The fixture content is editorial (it describes the project, not live data), so "fixture" is the correct posture.

**Entry points:** Nav "ABOUT" link. Footer colophon links.

**Mobile:** Well-shaped. Explicit CSS at 600px (title shrinks, facts ribbon goes 2x2). Prose constrained to 62ch. Tables scroll.

### 1.10 Admin Routing (`/admin/routing`) - unlisted

**What it shows:** A/B routing controls: model selector, mode toggle (SHADOW/AB), traffic percentage slider, challenger selector, clear-challenger. Auth-gated with HTTP Basic.

**Data source:** LIVE. Read via `useRouting`, writes via four mutations.

**Entry points:** Direct URL only. Not in nav.

**Mobile:** Well-shaped (Mantine Container/Stack/Group). Internal tool.

### 1.11 404 (`/*`)

**What it shows:** "No play at this base." with a link home.

**Data source:** Static.

---

## Section 2 - Three-Audience Walkthrough

### 2.1 Recruiter/interviewer (60 seconds)

**First impression:** Lands on `/`. Sees "Tonight's Slate" with live game data, team colors, a model-fleet strip showing 4 models with CHAMPION/SHADOW badges. The broadcast-graphics identity is distinctive and polished. Within 10 seconds they understand: this is a live baseball analytics platform with real ML models serving.

**What works:**

- The fleet strip immediately signals "real models, real deployment" - the CHAMPION/SHADOW stage badges are the right vocabulary for an ML engineering audience.
- The Ops page is strong: model fleet + latency + drift + retrain queue in one view. An interviewer clicking OPS sees production ML systems work (registry, routing, monitoring).
- The Accuracy page is honestly framed: live vs offline surfaces never mixed, honest empty states.

**What confuses or undersells:**

- **Navigation order undersells depth.** PARKS before PLAYERS before GAMES before OPS: the recruiter hits a fixture-heavy park-factors page before seeing the live game engine or the ops dashboard. The engineering depth is behind the third and fifth nav items.
- **The home page undersells the model count.** "Tonight's Slate" reads as a game-tracker dashboard, not as "four calibrated ML models serving live." The fleet strip is there but visually subordinate to the matchup board.
- **Accuracy is a separate nav item** that a recruiter might skip. If it were surfaced as part of an "ML" or "Models" grouping, the calibration/drift story would be harder to miss.
- **The about page is fixture-heavy** and reads as a placeholder. The facts ribbon (133 decisions, 11 ADRs) is good signal but buried. The methodology prose is dense and not scannable.

### 2.2 Baseball fan during a live game

**The journey:** Arrives from a link or the home page. Needs to find "my game" fast and understand what the models are saying about the current at-bat.

**What works:**

- The games board (`/games`) is fast: status filters, live-first ordering, clear "OPEN GAME" links. The cards show score, inning, and starting pitchers at a glance.
- The home page's tonight strip provides quick links to live games.
- Inside `/games/:id`, the scorebug is always visible and the pitch log updates live.

**What fails:**

- **The four model panels have equal visual weight.** A fan during a live at-bat sees next-pitch, pitch-type, batted-ball, and pitch-log stacked vertically with identical LowerThird chrome. There is no hierarchy signaling "this is what matters right now." During an at-bat, next-pitch and pitch-type are primary; after a BIP, batted-ball becomes primary. The layout ignores these temporal roles entirely.
- **The game page is long.** On mobile, a fan scrolls past the scorebug, past the BigStat row, past the chyron, past next-pitch, past pitch-type, to reach the pitch log. The most frequently consulted section (what just happened) is below three model panels that may or may not be active.
- **Finding a specific game from the home page** requires knowing the games board exists. The tonight strip shows abbreviated game chips; a fan looking for "NYY vs BOS" may not recognize "NYY" in a horizontal ticker.

### 2.3 The owner operating

**The daily check:** Open `/ops`, scan the model fleet for unexpected stage changes, check latency for spikes, glance at drift values, check the retrain queue for stuck items, skim the ops log for events.

**What works:**

- The ops page is genuinely useful for the daily check. Model fleet, latency, drift, retrain, and events are all on one page with live data.
- The accuracy page provides the "are the models still calibrated" answer in one view.

**What still needs Grafana:**

- **Infra services status** is always fixture ("no status endpoint yet"). The operator cannot check ClickHouse/Prometheus/Grafana health from `/ops`.
- **Historical latency trends** beyond 7 days require Grafana.
- **Alert history and fired alerts** are not on `/ops` - the operator checks Discord or Grafana for alert state.
- **Backup status** (snapshot freshness, offsite push, registry backup) is not surfaced. The operator checks `systemctl status` on the box.

---

## Section 3 - IA Options

### 3.1 Home page's job

The home page currently serves as a tonight-slate dashboard. Three options:

**A. Portfolio landing first (recommended).** Lead with a 2-sentence "what this is" + the model fleet strip + a count/badge strip (4 models serving, 138k prediction_log rows, 25-min proven RTO). The tonight slate moves below as the "and here it is, live." A recruiter gets the pitch in 10 seconds; a fan scrolls to the game links they came for.

**B. Tonight-slate dashboard (current).** Keep the slate as the hero. The engineering depth is discoverable via OPS and ABOUT. Risk: the 60-second recruiter never finds the depth.

**C. Hybrid hero.** A split layout: left column is the portfolio pitch (what + why + numbers), right column is the live slate. Tighter than A but harder to make work on mobile without one side collapsing to a subordinate position anyway.

### 3.2 Model surface organization

Currently: `/accuracy` (live + offline scorecards) and `/parks` (HR heatmap + fixture factor table) are separate nav items. The retrospective post-head surface lives inside `/games/:id`.

**A. Fold into a "Models" hub (recommended).** A new top-level group in the nav: Models > {Accuracy, Parks Explorer, (future) Player Splits}. One click from the nav says "this project has a model portfolio." The post-head retrospective moves to the accuracy page as a "Live Retrospective" section alongside the rolling scorecard.

**B. Keep separate.** Accuracy and Parks serve different audiences (ops vs exploration) and collapsing them makes each harder to find. Counter: neither is discoverable as-is (accuracy is the 6th nav item).

**C. Parks absorbs accuracy.** The park explorer becomes a "Model Surfaces" page that includes accuracy as a section. Counter: accuracy is about ALL models, not just batted-ball, so this misframes it.

### 3.3 Game-page panel hierarchy

The four panels (next-pitch, pitch-type, batted-ball, pitch-log) currently stack with equal visual weight. They have natural temporal roles:

**A. Primary-secondary with temporal switching (recommended).** During an at-bat (no ball in play): next-pitch and pitch-type are PRIMARY (full-width, above the fold), pitch-log is SECONDARY (collapsed to a compact last-3-pitches strip, expandable), batted-ball is HIDDEN (no ball in play). After a ball in play: batted-ball becomes PRIMARY, next-pitch and pitch-type move to SECONDARY (the at-bat is over), pitch-log stays SECONDARY. The page layout adapts to what matters NOW rather than showing everything always.

**B. Fixed hierarchy.** Next-pitch is always primary (largest), pitch-type secondary (smaller), pitch-log third, batted-ball conditional. Simpler to implement, doesn't adapt to game state.

**C. Tab/accordion.** Group the panels behind tabs (Predictions | Pitch Log | Batted Ball). Compact, but hides content that a fan might want to glance at without clicking.

### 3.4 Navigation grouping and order

Currently 7 flat links: HOME | PARKS | PLAYERS | GAMES | OPS | ACCURACY | ABOUT.

**A. Reorder + group (recommended).**

- Primary: HOME | GAMES | PLAYERS
- Models: PARKS | ACCURACY (or a "Models" dropdown if 3.2A is chosen)
- Operations: OPS
- Meta: ABOUT (demoted to footer or a secondary position)

This puts the live-game engine and player lookup first (what a visitor uses), engineering depth second (what a recruiter evaluates), and editorial last.

**B. Reorder only.** HOME | GAMES | PLAYERS | OPS | ACCURACY | PARKS | ABOUT. Puts the live surfaces first without grouping. Simpler, still an improvement.

**C. Keep current.** The current order was feature-shipping order, not user-journey order. No recommendation to keep it.

### 3.5 About/methodology page role

Currently pure fixture content. The Model Fleet table is the only live element.

**A. Demote to footer link (recommended).** Remove from the primary nav, keep as a footer "Colophon" link. The methodology content is real but does not serve any of the three audiences in the nav's scan-and-click model. A recruiter finds it via the about link in the footer or a direct URL; a fan never needs it; the operator never checks it.

**B. Keep in nav, make scannable.** Rewrite as a "60-second resume" page: a one-paragraph pitch, three headline metrics (models, decisions, coverage), and expandable sections for depth. Keeps it discoverable.

**C. Merge into a "Models" hub.** The methodology content (stack table, rejected alternatives) becomes the "about" section of a Models page. Counter: mixes editorial with live data surfaces.

---

## Constraints

- No new pages without strong cause. The rework is organization, not expansion.
- Every "fixtures-as-live" disclosure (D4 honesty labels) must survive relocation. A caption that says "showcase data" today must still say it after the rework.
- Token discipline: anything the visual phase needs goes into the broadcast token layer, not component hex codes.
- Testing impact per option: existing axe routes, Playwright specs, and vitest coverage floors must stay green through Phase C. Options that restructure routes need explicit migration plans for test references.
- The broadcast-graphics identity ([160]) is locked. The rework evolves within it: same fonts, same token palette, same angled-chip language.
