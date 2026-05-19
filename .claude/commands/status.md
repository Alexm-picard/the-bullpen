---
description: Report phase progress from docs/phase-status.json with on-disk evidence and next-action suggestions
---

Read `docs/phase-status.json`. This is the project's single source of truth for phase progress.

Produce a sharp status snapshot:

## 1. Header (3 lines)
```
Current phase: <id> — <name> (weeks <weeks>, estimated <hours>)
Status: <not_started | in_progress | exit_criterion_met | complete>
Last updated: <last_updated>
```

## 2. Exit-criteria table
Pull the `exit_criteria_checklist` for the current phase. Render as a table:

| ✔/✘ | Item | On-disk evidence |
|---|---|---|

For each item, look for plausible on-disk evidence (don't trust the JSON's `done` field blindly — verify):
- "Spring Boot skeleton" → does `backend/build.gradle.kts` exist? Does the api+worker profile config exist?
- "Leakage tests in CI" → does `.github/workflows/training.yml` have the `leakage-tests` job? Does `training/tests/leakage/` have all four test files?
- "ClickHouse via Docker" → does `infra/docker-compose.yml` exist with the clickhouse service?
- "registered in registry, served at /predict/X" → grep backend for the endpoint
- etc.

If JSON says ✔ but evidence is missing, flag with ⚠ and a note "JSON says done but no evidence — update JSON or add the missing piece".
If JSON says ✘ but evidence exists, flag with ⚠ and a note "evidence on disk — bump JSON".

## 3. Sub-phase progress (if the phase has sub_phases)
List each sub-phase's status with one-line note.

## 4. Drills section
From `drills`:
- Restore drill: last_run = <date or 'never'>. ⚠ if `must_run_before_season=true` and never run.
- Reboot drill: last_run = <date or 'never'>. Same.

## 5. Soft-cut risk
If the current phase has gone N weeks past the estimate, or if exit criteria coverage is < 50% with < 25% of estimated hours remaining (rough heuristic), flag soft-cut consideration with the *first* item from the phase's `mvp_cuts` (or `soft_cut_priority` if none on the phase).

## 6. Recent decisions
Last 3 entries from `docs/decisions.md` — just the one-line summary, no full body.

## 7. Next actions (max 3)
Concrete, file-specific. Examples:
- "Write `backend/src/main/resources/application-api.yml` for the api profile"
- "Add `infra/docker-compose.yml` with ClickHouse + Grafana"
- "Run `/drill restore` — never run, season starts in <X weeks>"

## Output rules
- Total output under 60 lines
- Use the markdown link format for file references (so the IDE can navigate to them)
- Be honest about JSON-vs-evidence divergence — that's the highest-value thing this command does
- Do NOT modify `phase-status.json` yourself; suggest what the user should bump
