# Issue label conventions

Apply these via `gh label create` or the GitHub UI. Templates already preset `type:*` for new issues.

## Type (mutually exclusive)
- `type:bug` — broken behavior, regressions
- `type:idea` — new feature, experiment, improvement
- `type:cut` — proposal to drop scope per soft-cut priority list
- `type:chore` — refactor, deps, infra hygiene
- `type:docs` — documentation only

## Severity (only on bugs)
- `sev:p0` — prod broken or data integrity at risk; drop everything
- `sev:p1` — feature broken, blocks development
- `sev:p2` — annoying but workable
- `sev:p3` — nice to fix

## Area (multiple allowed)
- `area:backend`, `area:training`, `area:frontend`, `area:infra`, `area:contracts`, `area:registry`, `area:drift`, `area:retraining`, `area:ingest`, `area:ops-dashboard`

## Phase
- `phase:0`, `phase:1`, `phase:2`, `phase:3`, `phase:4`, `phase:5`, `phase:v1.5`

## Status
- `status:blocked` — waiting on something external (data, decision, hardware)
- `status:in-progress`
- `status:needs-decide` — has open question that needs `/decide` first
- `status:wontfix`

## Create them all in one go

```bash
gh label create type:bug --color FF0000 --description "Broken behavior"
gh label create type:idea --color 0E8A16
gh label create type:cut --color FBCA04
gh label create type:chore --color CFD3D7
gh label create type:docs --color 1D76DB

gh label create sev:p0 --color B60205
gh label create sev:p1 --color D93F0B
gh label create sev:p2 --color FBCA04
gh label create sev:p3 --color C5DEF5

for area in backend training frontend infra contracts registry drift retraining ingest ops-dashboard; do
  gh label create "area:$area" --color BFD4F2
done

for phase in 0 1 2 3 4 5 v1.5; do
  gh label create "phase:$phase" --color 5319E7
done

gh label create status:blocked --color B60205
gh label create status:in-progress --color 0E8A16
gh label create status:needs-decide --color FBCA04
gh label create status:wontfix --color CFD3D7
```

## What does NOT go in Issues

- **Decisions.** They live in `docs/decisions.md`. If you need to decide, use `/decide` — when locked, you can reference the decision number in related issues.
- **Long-form planning.** Goes in `docs/plan.md` (phase scope), `docs/design.md` (system design).
- **Drill reports.** Live in `docs/drills/`.
- **Postmortems.** Live in `docs/postmortems/`.

Issues are for **trackable individual items**: bugs to fix, ideas to consider, cuts to propose. Everything else is documentation.
