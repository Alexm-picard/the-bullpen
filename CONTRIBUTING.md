# Contributing

The Bullpen is a **solo portfolio project** ([thebullpen.net](https://thebullpen.net)) - a
self-hosted baseball-analytics platform with a custom ML systems wrapper. There is no open
contribution process; this file exists to document the engineering discipline the build runs on,
because that discipline is the point. The authoritative, always-current rules live in
[`CLAUDE.md`](./CLAUDE.md) and the planning docs under [`docs/`](./docs/) - this is the short version.

## Ground rules (the ones that bite if ignored)

- **Local dev on macOS, prod on the self-hosted Linux box.** All code is authored on the laptop (or
  in CI) and deployed by `./deploy.sh`; the box is never an editing target. Remote access to the box
  is read-only (logs, Grafana, ClickHouse queries). See [ADR-0006](./docs/adr/0006-dev-prod-boundary.md).
- **Trunk-based on `main`, small single-purpose PRs, no stacking.** One change per PR, merged on green.
- **Conventional Commits** (`feat:` / `fix:` / `docs:` / `refactor:` / `chore:` / `test:` / `perf:`).
- **No em-dashes anywhere** - prose, commits, comments, docs. Use a hyphen, colon, parens, or two
  sentences. (Keeps hashed contract files ASCII-clean too.)
- **Decisions are append-only.** Substantial choices land in [`docs/decisions.md`](./docs/decisions.md)
  (numbered, never edited retroactively - a git hook enforces it) plus an ADR under `docs/adr/` for the
  top ~15%. The `/decide` flow runs the conversational lock.

## Setup

```bash
# Backend (Java 21 + Spring Boot 3)
cd backend && ./gradlew build            # compile + test + spotless + spotbugs + errorprone

# Training (Python 3.11+, uv-managed)
cd training && uv sync && uv run pytest  # OMP_NUM_THREADS=1 on macOS to avoid the libomp segfault

# Frontend (React 19 + TS + Vite)
cd frontend && npm install && npm run dev
```

The full command list (formatters, static analysis, profiles, migrations, e2e) is in `CLAUDE.md`
under "Build / test / run commands". Hooks auto-format Java/Python/TS on edit.

## Before you push (the CI gates, run locally)

- **Backend:** `cd backend && ./gradlew spotlessCheck test spotbugsMain errorproneMain`
  (CI's `backend-test` runs the full `build`, ClickHouse integration tests included - `spotbugsMain` is
  easy to forget and it _will_ fail the gate).
- **Frontend:** `cd frontend && npx tsc -b && npm run lint && npm run test:coverage`
  (`tsc -b`, not `tsc --noEmit` - the latter is a no-op here).
- **Training:** `cd training && uv run ruff check training && uv run pyright && uv run pytest`.

Coverage floors are enforced regression floors (backend JaCoCo, frontend vitest, training coverage.py);
do not cite a coverage number you cannot reproduce from a CI run.

## Non-negotiables

- **Testing posture: prefer real dependencies over mocks.** ClickHouse via Testcontainers, SQLite via
  temp file / `:memory:`, ONNX via a real Runtime session on a fixture model. Mocks only at hard
  external boundaries (Discord webhook, MLB Stats API).
- **No hex codes in frontend components** - Mantine theme tokens or Tailwind `@theme` colors only.
- **ML: temporal splits only, never `random_state` on a data split.** Rolling-origin CV, 4 folds
  2015-2025. The four leakage tests in `training/tests/leakage/` are CI-required. 2026 data is
  holdout-only (rule 13). Feature-schema hashing is enforced at model registration (rule 7).
- **Never commit a trained model artifact** - only metadata. Models live in S3-compatible storage
  (R2 / MinIO), the registry stores the path.

## Where things live

| What                                          | Where                                                                                          |
| --------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| System design, locked tech choices, rationale | `docs/design.md`, `docs/decisions.md`, `docs/adr/`                                             |
| Phase progress (machine-readable)             | `docs/phase-status.json` (read by `/status`)                                                   |
| Python <-> Java file contracts                | `contracts/`                                                                                   |
| Runbooks / drills / postmortems               | `docs/runbooks/`, `docs/postmortems/`                                                          |
| Backlog                                       | GitHub Issues (see `.github/labels.md`) - **not** decisions, which live in `docs/decisions.md` |

Review agents (`ml-leakage-auditor`, `registry-guard`, `java-reviewer`, `python-training-reviewer`,
`frontend-reviewer`) and skills (`register-model`, `promote-model`, `lock-decision`, ...) live under
`.claude/`. Registry/inference PRs get `registry-guard`; training feature/split PRs get `ml-leakage-auditor`.
