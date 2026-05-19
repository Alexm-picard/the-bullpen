---
name: ci-add
description: Add a new job or workflow to .github/workflows/ following The Bullpen's CI conventions. Trigger when the user says "add a CI job", "add a workflow", "wire X into CI", or wants to extend GitHub Actions coverage.
---

# ci-add

Adds new CI coverage without breaking the existing conventions.

## Existing workflows (don't duplicate)

| Workflow | Triggers on | Jobs |
|---|---|---|
| `backend.yml` | `backend/**` | test (build, test, spotlessCheck, spotbugs) |
| `training.yml` | `training/**`, `contracts/**` | lint-and-type, unit-tests, **leakage-tests (REQUIRED)** |
| `frontend.yml` | `frontend/**` | test (tsc, eslint, prettier, vitest, build), e2e (Playwright) |

Before adding a new job, check whether the existing workflow can absorb it. New workflows are for genuinely separate concerns.

## Conventions

- **Workflow names** are lowercase, single word: `backend`, `training`, `frontend`, `nightly`, etc.
- **Path filters** must be present — never run a workflow on every push
- **Conditional `if:`** guards check for file presence (`hashFiles('backend/build.gradle.kts') != ''`) so the workflow no-ops gracefully before scaffolding lands
- **Required jobs** (must pass to merge) get `name:` prefixed with `REQUIRED — ` so branch protection rules are unambiguous
- **Caching** — use the language-specific cache action (`actions/setup-node` with cache, `astral-sh/setup-uv` with enable-cache, `gradle/actions/setup-gradle` auto-caches)
- **Services** — declare in the job, not as standalone steps. ClickHouse uses `clickhouse/clickhouse-server:24-alpine`
- **Upload artifacts on failure only** — keeps the Actions tab clean
- **No secrets in this project's CI** until there's a concrete need; the public dataset means most things can run unauthenticated

## Procedure

1. **Clarify** with the user:
   - What does the new job check?
   - Is it required to merge (becomes a branch protection rule) or advisory?
   - What triggers it (path filter, schedule, manual `workflow_dispatch`)?
   - Does it need any services (ClickHouse, Postgres, browsers)?
2. **Pick the file**:
   - Extends an existing area → add a job to that workflow
   - New concern (nightly batch, release, deploy verification) → new file
3. **Write the job** following conventions above
4. **Update branch protection** instructions in the PR description if the job is REQUIRED
5. **Confirm** the workflow passes on a draft PR before requiring it

## Anti-patterns to refuse

- Running on every push (no path filter) — burns Actions minutes for nothing
- Hardcoded secrets or paths
- Skipping the conditional file-presence guard — leads to red CI before scaffolding exists
- Adding a workflow without a clear failure mode (i.e., what does failure prevent?)
- Adding "informational" jobs that don't gate anything — either it matters or it doesn't
