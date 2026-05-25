# Lessons learned — The Bullpen

Broader than any single drift postmortem. This doc covers what the
whole project taught — about ML systems, about operating an
unattended-but-not-really service for a season, about what was worth
the time and what wasn't.

Fill during operation, not after. Pre-seeded with what's already true
from Phases 0 – 5.

---

## What was worth the time

### Writing the ML systems wrapper from scratch in Java

> Pre-season note: this is the only choice that mattered. Hiring this
> at "I wrote the registry" beats hiring it at "I used MLflow" by a
> wide margin. Confirm after the postmortem lands — if the wrapper
> earned its keep during the drift event, this is a definitive
> "yes-do-this" lesson.

### Rolling-origin CV, never random splits

> Pre-season note: the leakage tests in CI exist because random splits
> on play-by-play data is the most common silent failure mode. Even
> the within-fold split is by date — never by game or pitch. This is
> defensible at hiring, and it's the right call for the modeling.

### Eval artifact directory per model version

> Pre-season note: registry rows pointing at a directory with
> reliability diagrams + per-fold metrics is the right shape. Don't
> store derivable analytics in the SQLite registry — files + a path.

### The decisions log + ADRs

> Pre-season note: the discipline of "no decision lands without a
> numbered entry + rationale" pays off when re-litigating obvious
> alternatives months later. Saves at least one bad pivot.

## What I'd do differently next time

### Wire prediction_log truth-join from day one

> Pre-season note: deferred truth-join blocked the per-player
> calibration view (4b.3), the agreement marker on the live game feed
> (4d.2), and the aggregate Ops reliability tab (4e.5). One indexed
> pitch_id column would unlock all three. Build it alongside the
> registry next time, not as a follow-up leaf.

### Single-source-of-truth observability discipline earlier

> Pre-season note: hardcoded fontSize literals proliferated across 6
> files before the 5.1 typography pass swept them. Catch with a
> custom-lint script in 4a, not in 5.1.

### Phase 4 ahead of Phase 2c

> Pre-season note: built the Park Explorer page against a per-park
> loop of the Phase-1 toy model because the 2c.5 30-park MLP wasn't
> built yet. Park Explorer is the marquee page — the model behind it
> ought to be the real one. Plan order: 2c before 4c.

## What surprised me

### LightGBM's default logger blocks tee buffering

> Pre-season note: looked like a 13-minute hang; was really stdout
> buffering. Always route framework loggers through Python's `logging`
> when running under `tee`.

### Spring conditional ordering is fragile across component-scanned beans

> Pre-season note: `@ConditionalOnBean(X.class)` on a component-scanned
> RestController doesn't reliably see another component-scanned
> Repository's own conditional. Use `@ConditionalOnProperty` against
> the underlying enabler instead.

### Mantine v9's prop renames don't always fail the build

> Pre-season note: `<Grid gutter>` → `<Grid gap>` was caught by tsc;
> others may silently no-op. Always read the migration notes; don't
> trust the type system as the only verifier.

### The Cloudflare Tunnel really is a SPOF

> Pre-season note: there is no other path to api.thebullpen.net. When
> the tunnel flaps, the app is down regardless of host health. Worth
> a runbook in `ops/runbooks/` documenting the symptoms + recovery
> before it happens for real (already in `observations.md` as a
> hardening candidate).

## What was overbuilt

> (Fill during operation. Candidates noticed pre-season: the eight-state
> game-status enum may collapse to four once the live poller proves
> half of them never fire; the 200-row pitches-since cap may be way
> too generous once real games show typical-page row counts.)

## What was underbuilt

> (Fill during operation. Pre-season suspicion: drift threshold
> tuning. Got reasonable defaults from the leaf plan; expect to revise
> after the first real drift event lands.)

## Process lessons

### Append-only `decisions.md` was the right call

> Pre-season note: zero re-litigation cost. The git hook enforces it;
> reversals are new entries referencing the original. Keeps a clean
> audit trail through the 130+ decisions logged.

### `docs/phase-status.json` drifted out of sync

> Pre-season note: dropped to `current_phase: 2` while Phases 4 + 5
> landed in one autonomous session. No discipline mechanism kept it in
> sync. Add a CI check that compares the status JSON to recent leaf
> doc status-log entries, or fold the status JSON bump into the leaf
> commit hook.

### Two-week reviews are real

> Pre-season note: not done yet — first review lands when the season
> opens. The soft-cut priority list exists; whether I actually use it
> when energy gets low is the test.

---

## Format for new entries (during operation)

```
### <one-line lesson, present tense>

> Date: YYYY-MM-DD. Trigger: <what surfaced this>. Evidence: <link to
> postmortem / sweep doc / commit>. Carrying forward: <what changes
> in the next project>.
```
