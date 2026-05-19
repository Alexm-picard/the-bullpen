# 00-TESTING-STRATEGY — What "tested" means in this project

> Read alongside any leaf plan that produces code. Every leaf plan declares its testing requirements; this doc gives the framework choices and CI gate definitions.

---

## Frameworks (locked)

| Layer | Framework | Why |
|---|---|---|
| Java unit | **JUnit 5** + **AssertJ** | Spring Boot default; AssertJ reads cleanly. |
| Java mocking | **Mockito** | Constructor-injected beans test cleanly with manual instantiation; Mockito for collaborators only. |
| Java integration | **Testcontainers** for ClickHouse, **embedded SQLite** for registry | Real DBs in tests; mocks lie about DB behavior. |
| Java HTTP integration | **`MockMvc`** for controller layer | Faster than full `@SpringBootTest`. |
| Python unit | **pytest** + **pytest-cov** | Default. |
| Python data | **pytest fixtures** with small Parquet samples | No mocking pandas. |
| Python ML | **deterministic seed + golden numbers** | Tests assert metric values match recorded baselines within tolerance. |
| Frontend unit | **Vitest** | Vite-native, fast. |
| Frontend component | **@testing-library/react** | Standard. |
| Frontend e2e | **Playwright** | Used sparingly — vertical-slice happy path + Park Explorer click. |

---

## What CI runs (each push)

GitHub Actions matrix:

```yaml
jobs:
  java:    runs JUnit + integration tests via Testcontainers
  python:  runs pytest + leakage-test suite (Phase 2 onwards)
  frontend: runs Vitest + typecheck + Lighthouse budget check (Phase 4 onwards)
  e2e:     runs Playwright against a built artifact (Phase 1 onwards, smoke only)
```

Branch must be green to merge. `main` cannot regress. Phases gate which suites are required to exist:

- **Phase 0**: Java + frontend smoke tests, no Python yet.
- **Phase 1**: + Python tests (model export parity), + Playwright happy-path.
- **Phase 2**: + leakage tests (mandatory, see below).
- **Phase 3**: + drift detector synthetic-shift tests (mandatory).
- **Phase 4**: + frontend visual smoke tests (Lighthouse threshold gate).
- **Phase 5**: + accessibility audit run.

---

## Leakage tests (non-negotiable, Phase 2 onwards)

Decision [63]. Four categories. Live in `ml/tests/leakage/`. CI fails if any test is added with `@pytest.mark.skip` without a written justification + linked decisions.md entry.

1. **`test_no_future_contamination.py`** — corrupt label values *after* the cutoff date for a held-out split, retrain, verify pipeline doesn't see the corruption (i.e., features used in training only depend on data before the cutoff).
2. **`test_shuffled_target.py`** — shuffle labels in training set, retrain, verify test-set Brier approaches random-guess floor (within tolerance). If the model still beats random, features leak.
3. **`test_calendar_date_trace.py`** — pick 10 random pitches; for each, walk the feature pipeline and assert every feature value uses only data with `as_of_date < pitch_date`.
4. **`test_id_consistency.py`** — same `pitcher_id` with same pre-pitch history yields identical target encoding regardless of position in the dataset.

Each test runs against the canonical sample dataset (small, committed under `ml/tests/fixtures/`). The full pipeline runs nightly against full data via the worker; CI uses the sample for speed.

---

## Synthetic drift tests (Phase 3 onwards)

Decision [64]. Live in `ml/tests/drift/` (Python sanity tests) and `backend/src/test/java/.../drift/` (Java integration tests).

- Inject a known PSI of 0.4 on a chosen feature → detector must fire `notice` within the synthetic time window.
- Inject a known calibration regression (1.6× baseline calibration error sustained 3 days) → detector must fire `page` alert.
- Inject zero drift on a real distribution → detector must NOT fire (false-positive guard).

These tests gate any change to the detector code.

---

## Parity tests (Phase 1.4 onwards)

Decision [28] / Risk Register G1.

- **`test_python_java_parity.py`** + Java equivalent — run a fixture pitch through the Python feature pipeline + ONNX model, then through the Java feature pipeline + ONNX model. Compare predictions: match within `1e-6` for both feature vectors and final probabilities.
- Fixture lives in `ml/tests/fixtures/parity_pitch_001.json` (input) and `parity_pitch_001_expected.json` (expected output). Updated only via the parity-test refresh script when models change.

---

## What "tested" means at the leaf-plan level

Every leaf plan's `## Testing requirements` section declares, at minimum:

- **Unit**: list of behaviors covered. e.g., "Murmur3 bucketing function is deterministic for the same input."
- **Integration**: at least one test exercising real I/O. e.g., "Registry CRUD against an embedded SQLite, asserting Flyway migration ran."
- **Leakage / sanity** (Phase 2+): which specific leakage test category this leaf needs.
- **CI gates**: which CI job(s) must pass. By default, all relevant jobs must pass; deviations are explicit.

Acceptance criteria includes "all declared tests pass locally and in CI". Without this line, the plan is not done.

---

## What we explicitly do NOT do

- ❌ **No 100% line coverage target.** Coverage is reported, not gated. Pursue 80%+ on `inference/`, `registry/`, `drift/`, feature pipeline. Skeleton config / wire-up code uncovered is fine.
- ❌ **No mutation testing in v1.** PIT/Stryker is overkill for solo dev.
- ❌ **No load/perf tests until Phase 5.3.** Premature optimization.
- ❌ **No cross-browser e2e.** Chromium via Playwright is enough.
- ❌ **No mock for ClickHouse.** Use Testcontainers — ClickHouse-specific SQL is too easy to get wrong against a fake.
- ❌ **No `@MockBean` for the registry in inference tests.** The registry is too central — use real registry against test SQLite.

---

## Local commands (target end state)

```bash
# Java
./mvnw test                         # unit + integration
./mvnw verify                       # + integration with Testcontainers

# Python
uv run pytest                       # all Python tests
uv run pytest ml/tests/leakage/     # leakage suite only
uv run pytest -k drift              # drift tests by name match

# Frontend
pnpm test                           # vitest
pnpm test:e2e                       # playwright (requires backend running)
pnpm typecheck

# All-in-one
./scripts/test-all.sh               # runs the matrix locally
```
