# 00-PLAN-TEMPLATE — The shape every leaf plan follows

> Copy this template when authoring a new leaf plan. Strict structure intentional: AI agents pattern-match well on consistent shape; senior reviewers scan it in seconds.

---

# `<Plan ID>` — `<one-line title>`

> Owning phase: `phase-<N>-<name>` · Estimated effort: `<X–Y h>` · Author: `<git handle>` · Authored: `YYYY-MM-DD`

---

## Scope boundaries

**IN scope** — what this plan covers:
- ...
- ...

**OUT of scope** — explicitly NOT in this plan; points to sibling/later plan:
- `<thing>` → see `<plan-id>` (`<plan-file>.md`)
- `<thing>` → deferred to `<phase>` / v1.5

---

## Objectives

Numbered, verifiable. If you can't write a one-line acceptance check for an objective, it's not crisp enough yet.

1. ...
2. ...
3. ...

---

## Dependencies

**Upstream plans that must be complete**:
- `<plan-id>` — `<file>.md` — why this is required

**Decisions that constrain this plan** (from `../decisions.md`):
- `[N]` — short paraphrase
- `[N]` — short paraphrase

**Conventions referenced** (from `00-CONVENTIONS.md`):
- `<section>` — what aspect

**Risk Register entries this plan closes or partially addresses** (from `00-RISK-REGISTER.md`):
- `<G/I/C><N>` — short status note

---

## Required files / modules

**New files**:
- `<absolute-or-repo-relative path>` — purpose
- ...

**Modified files**:
- `<path>` — what kind of change (additive only? schema migration? refactor?)
- ...

**Touch nothing else** unless explicitly listed. If the work requires modifying a file outside this list, surface the scope drift and either expand the plan or split out a child plan.

---

## Step-by-step implementation tasks

Atomic, ordered, verifiable. An AI agent should be able to start at step 1 and proceed without re-reading any other doc once `00-MASTER.md` + `00-CONVENTIONS.md` + the relevant `design.md` section are in context.

1. ...
2. ...
3. ...
4. **Verify**: `<command or check>` — expected output

---

## Testing requirements

(Refer to `00-TESTING-STRATEGY.md` for framework choices.)

**Unit**:
- ...
- ...

**Integration**:
- ...

**Leakage / sanity** (Phase 2+ only, when applicable):
- ...

**CI gates** — which CI jobs must pass for this plan's PR:
- ...

---

## Acceptance criteria (Definition of Done)

Checkboxes; every item must be checkable as either passed or failed without ambiguity.

- [ ] `<observable outcome 1>`
- [ ] `<observable outcome 2>`
- [ ] All declared tests pass locally and in CI
- [ ] No regressions in upstream plan acceptance checks
- [ ] If the plan introduces public API: OpenAPI spec regenerates and frontend types regenerate cleanly
- [ ] If the plan introduces a migration: migration applied successfully against an empty DB AND a populated test DB
- [ ] If the plan touches secrets: `secrets.env` keys documented in `00-DEPLOYMENT-STRATEGY.md` and rotation runbook updated

---

## Known edge cases

Bullet what specifically goes wrong if this plan is implemented carelessly.

- ...
- ...

---

## Risks (link to RISK-REGISTER.md entries)

- `<G/I/C><N>` — closed by this plan? partially addressed? still open?

---

## Status log

> Append-only. Update when work starts and when it ships.

| Date | Event |
|---|---|
| `YYYY-MM-DD` | Authored. |
| `YYYY-MM-DD` | Started (commit `<sha>`). |
| `YYYY-MM-DD` | Shipped (PR `<#>`, merged `<sha>`). |

---

## How to fill out this template (for the author)

1. Replace `<Plan ID>` with the leaf ID from the phase INDEX (e.g., `2a.5`).
2. Title is one line, action-shaped: "Train and isotonic-calibrate the LightGBM pre-pitch model".
3. **Resist scope creep.** If a leaf plan exceeds ~300 lines or has more than 8 implementation steps, split it.
4. Always cite which `decisions.md` numbers constrain this work — that's the discipline check.
5. Always declare upstream dependencies. If you can start this leaf without finishing another, the upstream isn't actually a dependency.
6. Acceptance criteria must include a *verification* a human can run, not just "works on my machine".
